package com.tongluosao.gallerysync.hook;

import android.content.Context;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import com.tongluosao.gallerysync.core.Logger;

/**
 * 反射驱动相册自己的同步状态机，让原生界面显示真实进度。
 *
 * <p>为什么需要这一层：相册的"正在备份 N 张"不是读模块的状态，而是它自己的
 * {@code SyncStateInfo} 判出来的 —— 状态要走到 SYNCING，判据是
 * {@code (isSyncActive() && 原因含 32/512) || isUploading()}；而 {@code isUploading()}
 * 看的是 {@code UploadStatusController.mCount}，那个计数只在 {@code BackUploadTask.onPreExecute()}
 * 里加一。替换模式把上传截在 {@code invokeList}，BackUploadTask 压根不会被创建，
 * 于是计数永远是 0 —— 界面既不会显示"正在备份"，也不会去重查待备份数量，
 * 用户看到的就是"什么都看不到"。
 *
 * <p>所以模块得自己把这两件事顶上：
 * <ol>
 *   <li>{@code startUpload/endUpload}：让相册认为"正在上传"，状态才会走到 SYNCING；</li>
 *   <li>{@code triggerMediaChanged}：它才会去重查待备份数量（{@code updateDirtyCount}），
 *       而数量是靠 {@code MarkSynced} 写 localFlag=0 一项项减下去的。</li>
 * </ol>
 *
 * <p>全部反射且失败即吞：界面显示不出来只该是显示问题，不能反过来影响上传本身。
 */
public final class HostSyncState {

    private static final String CLS_UPLOAD_STATUS =
            "com.miui.gallery.util.deviceprovider.UploadStatusController";
    private static final String CLS_SYNC_STATE_MANAGER =
            "com.miui.gallery.base.syncstate.SyncStateManager";
    private static final String CLS_SYNC_UTIL = "com.miui.gallery.util.SyncUtil";
    private static final String CLS_SYNC_REQUEST = "com.miui.gallery.base.syncresult.SyncRequest";
    private static final String CLS_SYNC_TYPE = "com.miui.gallery.base.syncresult.SyncType";

    /** 触发界面重查的最小间隔：SyncStateInfo.invalidate() 自己会合并，这里只是别刷得太勤 */
    private static final long REFRESH_INTERVAL = 1_000L;
    /** 主动补一轮同步的最小间隔，避免把相册的同步框架刷爆 */
    private static final long REQUEST_INTERVAL = 60_000L;
    /**
     * 只点 push 两位：32=PushOwnerDataAdapter、512=PushShareDataAdapter。
     * 见 {@link #requestSync} 里对"为什么不用 Long.MAX_VALUE"的说明。
     */
    private static final long SYNC_REASON_PUSH = 32L | 512L;

    private final Object mController;
    private final Method mStartUpload;
    private final Method mEndUpload;
    private final Object mStateManager;
    private final Method mTriggerMediaChanged;
    private final Method mRequestSync;
    private final Constructor<?> mRequestCtor;
    private final Object mPowerForceType;

    /**
     * 正在上传的路数。上传队列是 3 路并发，不能再用一个布尔表示"在传" ——
     * 任何一路先结束就把状态置假的话，宿主的 {@code UploadStatusController.mCount}
     * 会在另外两路还在传时归零，原生界面立刻从"正在备份"退回"未备份"。
     */
    private int mActive;
    private boolean mUploading;
    private long mLastRefresh;
    private long mLastRequest;

    private HostSyncState(ClassLoader loader) {
        Object controller = null;
        Method start = null;
        Method end = null;
        Object manager = null;
        Method trigger = null;
        Method requestSync = null;
        Constructor<?> requestCtor = null;
        Object powerForce = null;
        try {
            Class<?> ctrlCls = loader.loadClass(CLS_UPLOAD_STATUS);
            controller = ctrlCls.getMethod("getInstance").invoke(null);
            start = ctrlCls.getMethod("startUpload");
            end = ctrlCls.getMethod("endUpload");
        } catch (Throwable t) {
            Logger.w("event=sync_state_unavailable target=UploadStatusController", t);
            controller = null;
        }
        try {
            Class<?> mgrCls = loader.loadClass(CLS_SYNC_STATE_MANAGER);
            manager = mgrCls.getMethod("getInstance").invoke(null);
            trigger = mgrCls.getMethod("triggerMediaChanged");
        } catch (Throwable t) {
            Logger.w("event=sync_state_unavailable target=SyncStateManager", t);
            manager = null;
        }
        try {
            Class<?> typeCls = loader.loadClass(CLS_SYNC_TYPE);
            powerForce = typeCls.getField("POWER_FORCE").get(null);
            Class<?> requestCls = loader.loadClass(CLS_SYNC_REQUEST);
            // 直接拿构造器，省掉 SyncRequest.Builder 那一串：签名是
            // (SyncType, long syncReason, boolean delayUpload, boolean manual)
            requestCtor = requestCls.getConstructor(typeCls, long.class, boolean.class, boolean.class);
            Class<?> utilCls = loader.loadClass(CLS_SYNC_UTIL);
            requestSync = utilCls.getMethod("requestSync", Context.class, requestCls);
        } catch (Throwable t) {
            Logger.w("event=sync_state_unavailable target=SyncUtil", t);
            requestSync = null;
            requestCtor = null;
            powerForce = null;
        }
        mController = controller;
        mStartUpload = start;
        mEndUpload = end;
        mStateManager = manager;
        mTriggerMediaChanged = trigger;
        mRequestSync = requestSync;
        mRequestCtor = requestCtor;
        mPowerForceType = powerForce;
    }

    public static HostSyncState create(ClassLoader loader) {
        return new HostSyncState(loader);
    }

    /**
     * 标记"正在上传"。按引用计数：只有第一路进来时才真的告诉宿主。
     *
     * <p>必须与 {@link #endUpload} 严格配对（调用方用 {@code try/finally} 把整个上传包住）。
     */
    public synchronized void beginUpload() {
        mActive++;
        if (mUploading || mController == null) {
            return;
        }
        try {
            mStartUpload.invoke(mController);
            mUploading = true;
            Logger.i("event=host_upload_state uploading=true");
        } catch (Throwable t) {
            Logger.w("event=host_upload_state_failed action=start", t);
        }
    }

    /**
     * 交还一路。**这里刻意不告诉宿主"传完了"** —— 一整批传输期间宿主的界面应当一直是
     * "正在备份"，每两张之间闪一下会很难看。真正收尾由 {@link #endAll} 在队列真空闲时做。
     */
    public synchronized void endUpload() {
        if (mActive > 0) {
            mActive--;
        }
    }

    /**
     * 队列真空闲：强制收尾，让宿主知道这一轮结束了。
     *
     * <p>与 {@link #endUpload} 的分工是：前者只减引用（并发下"谁最后结束"不确定），
     * 后者由队列在"一项都没人在传"时调用。少了它，宿主界面会一直挂在"正在上传"上，
     * 停在一个不再变化的数字上。
     */
    public synchronized void endAll() {
        mActive = 0;
        if (!mUploading || mController == null) {
            return;
        }
        try {
            mEndUpload.invoke(mController);
            mUploading = false;
            Logger.i("event=host_upload_state uploading=false");
        } catch (Throwable t) {
            Logger.w("event=host_upload_state_failed action=end", t);
        }
    }

    /**
     * 请相册再跑一轮同步，好让它把还没枚举到的脏项交给我们。
     *
     * <p>为什么需要：相册只在它自己的排期上枚举脏项（实测空闲时一两小时才一轮），
     * 而每轮交给模块的数量受 {@code max_items_per_sync} 限制。队列一旦传完，
     * 模块就只能干等下一轮 —— 一万两千张照片要等几十轮，用户感受到的就是"备份很慢"。
     * 队列见底且有进展时主动补一轮，把整条流水线接起来。
     *
     * <p>不会自激：调用方（{@code UploadQueue.onQueueIdle}）每空转一次才叫一下，
     * 间隔逐次翻倍到 1 小时封顶，有新项入队又重置回 1 分钟；脏项清零后相册枚举不到东西，
     * 模块也没有新进展，催促的频率会自己退到最低。
     *
     * <p>用 POWER_FORCE + manual 且 delayUpload=false：跟界面上"立即备份"按的是同一套参数，
     * 确保这一轮真的会把上传排进去，而不是只走一遍元数据。
     *
     * <p>sync reason 只点 push 两位（32=PushOwnerDataAdapter、512=PushShareDataAdapter），
     * 不用相册自己的默认值 {@code Long.MAX_VALUE}。原因是适配器按注册顺序串行跑，
     * 而 PullOwnerDataAdapter 排在 push 之前 —— 用默认值的话每补一轮都要先走一遍
     * 从云端拉数据，既慢、又可能把相册带回"正在恢复"状态（实测那个阶段 push 完全不动），
     * 反而把要补的 push 挡在后面。我们只需要相册枚举脏项，那就只点 push。
     */
    public void requestSync(Context context) {
        if (mRequestSync == null || context == null) {
            return;
        }
        long now = System.currentTimeMillis();
        synchronized (this) {
            if (now - mLastRequest < REQUEST_INTERVAL) {
                return;
            }
            mLastRequest = now;
        }
        try {
            Object request = mRequestCtor.newInstance(mPowerForceType, SYNC_REASON_PUSH, false, true);
            mRequestSync.invoke(null, context, request);
            Logger.i("event=host_sync_requested reason=queue_idle");
        } catch (Throwable t) {
            Logger.w("event=host_sync_request_failed", t);
        }
    }

    /** 让界面重查一次待备份数量；内部按最小间隔节流 */
    public void refresh() {
        if (mStateManager == null) {
            return;
        }
        long now = System.currentTimeMillis();
        synchronized (this) {
            if (now - mLastRefresh < REFRESH_INTERVAL) {
                return;
            }
            mLastRefresh = now;
        }
        try {
            mTriggerMediaChanged.invoke(mStateManager);
        } catch (Throwable t) {
            Logger.w("event=host_sync_refresh_failed", t);
        }
    }
}
