package com.tongluosao.gallerysync.hook;

import android.content.res.Resources;

import com.tongluosao.gallerysync.core.Logger;

import java.lang.reflect.Method;
import java.util.function.BooleanSupplier;

import io.github.libxposed.api.XposedInterface;

/**
 * 相册首页那句"正在同步 N 个照片和视频"的完成态。
 *
 * <p>渲染点是 {@code com.miui.gallery.ui.SyncTextLine.onSyncStatusChange(SyncStateInfo)}
 * （classes3.dex，方法在 dump 里的偏移 0x219cc8）。它先把 {@code SyncStateInfo.getDirtyCount()}
 * 返回的两个数加起来当 N，然后在 SYNCING 分支里**不做 0 判断**，直接
 * {@code Resources.getQuantityString(R.plurals.syncing_title_link, N, N)}（流量分支同理用
 * {@code syncing_gprs_title_link}）—— 所以队列见底之后页面就停在"正在同步0个照片和视频"。
 *
 * <p>相册自己的完成态文案是同一族字符串里的 {@code string/synced_title}（"照片和视频已同步完成"），
 * 与 {@code sync_off_title}/{@code sync_pending_title}/{@code sync_state_unknow} 等同步条标题
 * 排在同一个 id 段（0x7f1210a0..0x7f1210b1）。这里就只截那一处
 * {@code getQuantityString}：资源是那两个"正在同步"复数、数量是 0、且本模块的上传队列
 * 已经一张不剩时，换成相册自己的完成态字符串。
 *
 * <p>只改这一句文字的返回值：不动 {@code SyncStatus}（{@code SyncManager.needShowSyncBar} 等
 * 都看它），不动 {@code getDirtyCount} 的数字口径，更不碰 {@code SyncStateUtil.getDirtyCount}
 * 那条调度链路。非替换模式、队列还没建、资源取不到时一律放行原调用。
 */
final class SyncTextHook {

    private static final String GALLERY_PACKAGE = "com.miui.gallery";

    /** 相册首页"正在同步 N 个照片和视频"的两个复数资源（普通 / 流量） */
    private static final String[] SYNCING_PLURALS = {
            "syncing_title_link", "syncing_gprs_title_link"};

    /** 相册自己的完成态文案，按优先级取第一个存在的；硬编码的只有资源名 */
    private static final String[] DONE_STRINGS = {
            "synced_title", "all_items_synced", "backuped_desc", "backuped_title"};

    /** 资源名解析失败后的重试间隔：解析不出来时不要每个 0 都去 getIdentifier */
    private static final long RESOLVE_RETRY_MS = 60_000L;

    private final XposedInterface xposed;
    /** 队列是不是真的空了（在传的项也留在队列里，所以 0 就是"不在同步中"） */
    private final BooleanSupplier idle;

    private volatile int[] syncingIds;
    private volatile int doneStringId;
    private volatile long nextResolveAt;
    private volatile boolean loggedShown;
    private volatile boolean loggedFailure;

    SyncTextHook(XposedInterface xposed, BooleanSupplier idle) {
        this.xposed = xposed;
        this.idle = idle;
    }

    void install() {
        try {
            // 相册那句文字走的是带 formatArgs 的重载（方法内 0x0204/0x0211 两处 invoke-virtual），
            // 两参重载在框架里转调这一个，所以只装这一处
            Method method = Resources.class.getDeclaredMethod(
                    "getQuantityString", int.class, int.class, Object[].class);
            method.setAccessible(true);
            xposed.hook(method)
                    .setId("gallery_sync_done_text")
                    .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                    .intercept(chain -> {
                        Object replaced = null;
                        try {
                            replaced = doneText(chain.getThisObject(),
                                    chain.getArg(0), chain.getArg(1));
                        } catch (Throwable t) {
                            if (!loggedFailure) {
                                loggedFailure = true;
                                Logger.w("event=sync_done_text_failed detail=" + t);
                            }
                        }
                        return replaced != null ? replaced : chain.proceed();
                    });
            Logger.i("event=hook_registered method=Resources.getQuantityString");
        } catch (Throwable t) {
            Logger.e("event=hook_failed method=Resources.getQuantityString", t);
        }
    }

    /** 该换成完成态时返回相册自己的字符串，否则 null 表示放行原调用 */
    private String doneText(Object target, Object idArg, Object quantityArg) {
        if (!(target instanceof Resources)
                || !(idArg instanceof Number)
                || !(quantityArg instanceof Number)) {
            return null;
        }
        if (((Number) quantityArg).intValue() != 0) {
            return null;
        }
        Resources resources = (Resources) target;
        int id = ((Number) idArg).intValue();
        if (!isSyncingPlural(resources, id)) {
            return null;
        }
        if (!idle.getAsBoolean()) {
            return null;
        }
        int textId = doneStringId;
        if (textId == 0) {
            return null;
        }
        String text;
        try {
            text = resources.getString(textId);
        } catch (Resources.NotFoundException e) {
            return null;
        }
        if (text == null || text.isEmpty()) {
            return null;
        }
        if (!loggedShown) {
            loggedShown = true;
            Logger.i("event=sync_done_text_shown resource=synced_title");
        }
        return text;
    }

    private boolean isSyncingPlural(Resources resources, int id) {
        int[] ids = resolve(resources);
        return ids != null && (id == ids[0] || id == ids[1]);
    }

    /**
     * 按资源名解析出宿主那套 id，成功一次就固定下来。
     *
     * <p>不写死 0x7f1000cf / 0x7f1210ac：相册换版本时 id 会整体平移，资源名不会。
     * 解析不出来（换皮的 ROM、莫名其妙被裁掉的资源）就按间隔重试，期间一律放行。
     */
    private int[] resolve(Resources resources) {
        int[] ids = syncingIds;
        if (ids != null) {
            return ids;
        }
        long now = System.currentTimeMillis();
        if (now < nextResolveAt) {
            return null;
        }
        synchronized (this) {
            ids = syncingIds;
            if (ids != null) {
                return ids;
            }
            if (now < nextResolveAt) {
                return null;
            }
            int[] resolved = new int[SYNCING_PLURALS.length];
            boolean complete = true;
            for (int i = 0; i < SYNCING_PLURALS.length; i++) {
                resolved[i] = resources.getIdentifier(
                        SYNCING_PLURALS[i], "plurals", GALLERY_PACKAGE);
                if (resolved[i] == 0) {
                    complete = false;
                }
            }
            int done = 0;
            for (String name : DONE_STRINGS) {
                done = resources.getIdentifier(name, "string", GALLERY_PACKAGE);
                if (done != 0) {
                    break;
                }
            }
            if (!complete || done == 0) {
                nextResolveAt = now + RESOLVE_RETRY_MS;
                return null;
            }
            doneStringId = done;
            syncingIds = resolved;
            Logger.i("event=sync_text_resolved plurals="
                    + Integer.toHexString(resolved[0]) + "/" + Integer.toHexString(resolved[1])
                    + " done=" + Integer.toHexString(done));
            return resolved;
        }
    }
}
