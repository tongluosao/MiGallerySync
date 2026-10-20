package com.tongluosao.gallerysync.hook;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import com.tongluosao.gallerysync.core.Config;
import com.tongluosao.gallerysync.core.DownloadQueue;
import com.tongluosao.gallerysync.core.Logger;
import com.tongluosao.gallerysync.core.OriginalStore;
import com.tongluosao.gallerysync.core.ProgressProtocol;
import com.tongluosao.gallerysync.core.SyncMarker;
import com.tongluosao.gallerysync.core.TrashQueue;
import com.tongluosao.gallerysync.core.UploadQueue;
import com.tongluosao.gallerysync.core.WebDavClient;

import io.github.libxposed.api.XposedInterface;

/**
 * 相册同步链路 Hook。
 *
 * <p>H1 {@code UpDownloadManager.invokeList}：同步任务入队口，所有原图/视频/缩略图上传都经过它，
 * 是替换模式的唯一切断位置（返回值在调用点被忽略，返回 0 不会破坏调用方逻辑）。
 *
 * <p>H2 {@code GallerySFSFileTransferManager.doUpload}：文件真正上行小米云的出口，
 * 用于观测兜底——替换模式生效后这里不应再被触发，一旦触发说明出现了未覆盖的旁路。
 *
 * <p>H3 {@code SyncStateInfo.getDirtyCount}：相册界面上"正在同步 N 个照片和视频"里那个 N。
 * 纯显示口径，不参与同步调度（调度用的是 {@code SyncStateUtil.getDirtyCount}）。
 */
public final class GalleryHooks {

    private static final String CLS_UP_DOWNLOAD_MANAGER = "com.miui.gallery.cloud.UpDownloadManager";
    private static final String CLS_GALLERY_APP = "com.miui.gallery.GalleryApp";
    private static final String CLS_SFS_TRANSFER_MANAGER =
            "com.miui.gallery.cloud.e2ee.GallerySFSFileTransferManager";
    private static final String CLS_REQUEST_CLOUD_ITEM = "com.miui.gallery.data.remote.RequestCloudItem";
    private static final String CLS_UPLOAD_DESCRIPTOR = "cn.kuaipan.android.kss.upload.UploadDescriptorFile";
    private static final String CLS_UPLOAD_CONTROLLER =
            "com.miui.gallery.cloud.e2ee.CloudGalleryFileUploadControllerBase";
    private static final String CLS_PROGRESS_LISTENER =
            "cn.kuaipan.android.kss.transferclient.TransferProgressListener";
    private static final String CLS_SYNC_STATE_INFO =
            "com.miui.gallery.base.syncstate.SyncStateInfo";

    private final XposedInterface mXposed;
    private final ClassLoader mClassLoader;
    private final CloudItemReader mReader;
    private volatile UploadQueue mQueue;
    /** 下载队列；第一次上传见底时才建（开关关着也建，方便用户随时打开） */
    private volatile DownloadQueue mDownload;
    /** 回收站：删除 hook 往里排，"上传空转"时搬。与下载同一个节拍 */
    private volatile TrashQueue mTrash;
    /** 删除方向的 hook。它只需要一个 Context 与回收站队列，与上传队列无关 */
    private volatile DeleteHook mDeleteHook;
    /** WebDAV original restore path, entered only by the Gallery's verified native request chain. */
    private volatile OriginalRestoreHook mOriginalRestoreHook;
    /** One-shot, registry-verified restore for a fixed Gallery-private request file. */
    private final MetadataRestoreJob mMetadataRestoreJob;
    /** Protects registered WebDAV originals when Gallery's native slim operation runs. */
    private volatile SlimHook mSlimHook;
    private volatile NativeDownloadHook mNativeDownloadHook;
    private volatile Config mConfig;
    /** 宿主 Application 是否已经 onCreate：配置与它谁先到都有可能，两边都要能补建队列 */
    private volatile boolean mAppStarted;
    /** 模块 App 发来的命令广播的接收器；注册后要留着引用，注销时才找得到 */
    private volatile BroadcastReceiver mCommandReceiver;

    /**
     * 同一页任务被重复交回来的合并窗口。空间优化每完成一张都会把同一页再交一遍，
     * 每遍都要反射读完上千项再逐项查账本，真正干活的是同一批。窗口内首尾项与条数
     * 都一致时沿用上一次结论：拦截/放行不变，没入队的项下一轮照样会再交回来。
     */
    private static final long BATCH_COALESCE_MS = 1500L;
    private long mLastBatchAt;
    private int mLastBatchSize = -1;
    private String mLastBatchFirstKey;
    private String mLastBatchLastKey;
    /** 上一次确实把这一批交给队列了；没交成功时不许合并，否则会把上传饿死 */
    private boolean mLastBatchEnqueued;

    private GalleryHooks(XposedInterface xposed, ClassLoader classLoader) {
        this.mXposed = xposed;
        this.mClassLoader = classLoader;
        this.mReader = CloudItemReader.create(classLoader);
        this.mMetadataRestoreJob = new MetadataRestoreJob(classLoader);
    }

    public static GalleryHooks install(XposedInterface xposed, ClassLoader classLoader) {
        GalleryHooks hooks = new GalleryHooks(xposed, classLoader);
        hooks.installInvokeListHook();
        hooks.installUploadOutletHook();
        hooks.installAppStartHook();
        hooks.installDirtyCountHook();
        hooks.installOriginalRestoreHook();
        hooks.installSlimHook();
        hooks.installNativeDownloadHook();
        return hooks;
    }

    /**
     * 热重载换代后的自举。
     *
     * <p>新 generation 的静态量与方法区都是新的，{@code mAppStarted} 必然是 false —— 可宿主
     * Application 明明早就 onCreate 过了（我们正跑在它的进程里），而框架**不会**为新一代重放
     * {@code GalleryApp.onCreate}，新的 {@code invokeList} hook 也要等相册下一轮脏项枚举
     * （真机实测 1~3 小时）才会被调到。
     * 少这一步的后果很直接：装完新版本，日志里 {@code install_done} 明明打了，
     * 备份却一动不动，看起来像"改坏了"，其实只是队列没起来。
     */
    public void resumeAfterHotReload() {
        Logger.i("event=queue_resume reason=hot_reloaded");
        mAppStarted = true;
        ensureQueue();
        NativeDownloadHook nativeDownload = mNativeDownloadHook;
        if (nativeDownload != null) nativeDownload.resume();
    }

    public void setConfig(Config config) {
        mConfig = config;
        mMetadataRestoreJob.setConfig(config);
        OriginalRestoreHook originalRestore = mOriginalRestoreHook;
        if (originalRestore != null) {
            originalRestore.setConfig(config);
        }
        SlimHook slimHook = mSlimHook;
        if (slimHook != null) {
            slimHook.setConfig(config);
        }
        UploadQueue queue = mQueue;
        if (queue != null) {
            queue.setConfig(config);
        } else if (mAppStarted) {
            // 进程已经起来了，队列却因为"配置还没下发"没建成：配置一到就补建，别再等一轮同步
            ensureQueue();
        }
        // 下载队列在第一次上传见底时才建，所以这里通常还是 null；建了的话就要把新配置推下去
        DownloadQueue download = mDownload;
        if (download != null) {
            download.setConfig(config);
        }
        // 删除 hook 与回收站同理：一个可能还没装（拿不到 Context），一个可能还没建
        DeleteHook deleteHook = mDeleteHook;
        if (deleteHook != null) {
            deleteHook.setConfig(config);
        }
        TrashQueue trash = mTrash;
        if (trash != null) {
            trash.setConfig(config);
        }
    }

    private void installInvokeListHook() {
        try {
            Class<?> manager = mClassLoader.loadClass(CLS_UP_DOWNLOAD_MANAGER);
            Method invokeList = manager.getDeclaredMethod("invokeList", List.class, boolean.class);
            invokeList.setAccessible(true);
            mXposed.hook(invokeList)
                    .setId("gallery_invoke_list")
                    .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                    .intercept(chain -> {
                        boolean blocked = false;
                        try {
                            blocked = handleInvokeList(chain.getArg(0));
                        } catch (Throwable t) {
                            // 替换模式下这里也要拦住。放行 chain.proceed() 就是把这批照片送给小米云 ——
                            // 用户明确要求"只传 WebDAV，不回退"，那宁可这一批这轮不动
                            // （localFlag 没被改，下一轮枚举还会交回来），也不能偷偷传上去
                            Config config = mConfig;
                            blocked = config != null && config.isReplace();
                            Logger.e("event=invoke_list_hook_failed blocked=" + blocked, t);
                        }
                        // 接管时不再入队到相册自己的执行器，返回值在调用点被忽略，返回 0 是安全的
                        if (blocked) {
                            return 0;
                        }
                        return chain.proceed();
                    });
            Logger.i("event=hook_registered method=UpDownloadManager.invokeList");
        } catch (NoSuchMethodException e) {
            Logger.e("event=method_not_found method=UpDownloadManager.invokeList", e);
        } catch (ClassNotFoundException e) {
            Logger.e("event=class_not_found class=" + CLS_UP_DOWNLOAD_MANAGER, e);
        } catch (Throwable t) {
            Logger.e("event=hook_failed method=UpDownloadManager.invokeList", t);
        }
    }

    private void installUploadOutletHook() {
        try {
            Class<?> manager = mClassLoader.loadClass(CLS_SFS_TRANSFER_MANAGER);
            Class<?> itemCls = mClassLoader.loadClass(CLS_REQUEST_CLOUD_ITEM);
            Class<?> descCls = mClassLoader.loadClass(CLS_UPLOAD_DESCRIPTOR);
            Class<?> ctrlCls = mClassLoader.loadClass(CLS_UPLOAD_CONTROLLER);
            Class<?> listenerCls = mClassLoader.loadClass(CLS_PROGRESS_LISTENER);
            Method doUpload = manager.getDeclaredMethod(
                    "doUpload", itemCls, descCls, ctrlCls, listenerCls);
            doUpload.setAccessible(true);
            Field filePath = descCls.getField("filePath");
            Field fileSize = descCls.getField("fileSize");
            mXposed.hook(doUpload)
                    .setId("gallery_upload_outlet")
                    .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                    .intercept(chain -> {
                        OriginalStore.PathLease uploadLease = null;
                        try {
                            try {
                                Object desc = chain.getArg(1);
                                String path = (String) filePath.get(desc);
                                long size = fileSize.getLong(desc);
                                CloudItemReader.Item item = mReader == null
                                        ? null : mReader.read(chain.getArg(0));
                                Context context = HostContext.get(mClassLoader);
                                if (context == null || path == null || path.isEmpty()) {
                                    Logger.w("event=upload_outlet_hook_failed blocked=true reason="
                                            + (context == null ? "no_host_context" : "path_unavailable"));
                                    return null;
                                }
                                OriginalStore store = OriginalStore.get(context.getApplicationContext());
                                // Hold this read lease until native upload completes. SlimHook takes
                                // the matching write lease before switching localFile or deleting a
                                // source, so an upload cannot race the source replacement.
                                uploadLease = store.lockForUpload(new java.io.File(path));
                                boolean protectedPath = store.isProtectedUpload(new java.io.File(path),
                                        item == null ? null : item.rowId,
                                        item == null ? null : item.sha1);
                                if (protectedPath) {
                                    Logger.w("event=native_upload_blocked reason=managed_or_modified_original path=" + path);
                                    return null;
                                }
                                Config config = mConfig;
                                boolean shouldBeBlocked = config != null && config.isReplace();
                                if (shouldBeBlocked) {
                                    Logger.w("event=upload_outlet_unexpected file=" + path
                                            + " size=" + size
                                            + " reason=replace_mode_must_not_reach_xiaomi_cloud");
                                    // H1 normally consumes these requests. If an uncovered Gallery path
                                    // reaches the transport hook, do not leak it to Xiaomi cloud.
                                    return null;
                                } else {
                                    Logger.d("event=upload_outlet file=" + path + " size=" + size);
                                }
                            } catch (Throwable t) {
                                Logger.e("event=upload_outlet_hook_failed blocked=true", t);
                                // A read/registry failure at the last native upload boundary is not
                                // permission to upload an unclassified file. H1/UploadQueue can retry
                                // when the next batch arrives.
                                return null;
                            }
                            return chain.proceed();
                        } finally {
                            if (uploadLease != null) {
                                uploadLease.close();
                            }
                        }
                    });
            Logger.i("event=hook_registered method=GallerySFSFileTransferManager.doUpload");
        } catch (NoSuchMethodException e) {
            Logger.e("event=method_not_found method=GallerySFSFileTransferManager.doUpload", e);
        } catch (ClassNotFoundException e) {
            Logger.e("event=class_not_found class=" + CLS_SFS_TRANSFER_MANAGER, e);
        } catch (Throwable t) {
            Logger.e("event=hook_failed method=GallerySFSFileTransferManager.doUpload", t);
        }
    }

    /**
     * 进程启动即把队列拉起来。
     *
     * <p>队列原来只在第一次 {@code invoke_list} 时才创建，于是"相册什么时候枚举脏项"就决定了
     * 模块什么时候才开始干活。而相册只在它自己的排期上枚举（真机实测间隔 1~3 小时），
     * 重启相册后可能几小时都不动一下 —— 实测重启后连续观察几分钟，模块一条日志都没有。
     * 可 state.json 里存着几千条待传项，进程一起来就该接着传，没道理等相册发话。
     *
     * <p>在 {@code super.onCreate()} 之后建：那时 {@code GalleryApp.sGetAndroidContext()}
     * 一定已经可用，队列的持久化目录才拿得到。
     */
    private void installAppStartHook() {
        try {
            Class<?> appCls = mClassLoader.loadClass(CLS_GALLERY_APP);
            Method onCreate = appCls.getDeclaredMethod("onCreate");
            onCreate.setAccessible(true);
            mXposed.hook(onCreate)
                    .setId("gallery_app_start")
                    .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        try {
                            mAppStarted = true;
                            ensureQueue();
                            NativeDownloadHook nativeDownload = mNativeDownloadHook;
                            if (nativeDownload != null) nativeDownload.resume();
                            OriginalRestoreHook restoreHook = mOriginalRestoreHook;
                            if (restoreHook != null) {
                                restoreHook.onAppStart();
                            }
                            // 命令接收器与删除 hook 都要宿主 Context，而 install() 那一刻
                            // 启动还没走到这里（实测日志就是 command_receiver_unavailable
                            // reason=no_host_context）。放在 super.onCreate() 之后补装，
                            // 那时 GalleryApp.sGetAndroidContext() 一定可用
                            ensureCommandReceiver();
                            installDeleteHook();
                        } catch (Throwable t) {
                            // 建不起来只是"这一代没提前接上"，不能影响相册自己的启动
                            Logger.e("event=app_start_bootstrap_failed", t);
                        }
                        return result;
                    });
            Logger.i("event=hook_registered method=GalleryApp.onCreate");
        } catch (NoSuchMethodException e) {
            Logger.e("event=method_not_found method=GalleryApp.onCreate", e);
        } catch (ClassNotFoundException e) {
            Logger.e("event=class_not_found class=" + CLS_GALLERY_APP, e);
        } catch (Throwable t) {
            Logger.e("event=hook_failed method=GalleryApp.onCreate", t);
        }
    }

    private void installOriginalRestoreHook() {
        OriginalRestoreHook hook = new OriginalRestoreHook(mXposed, mClassLoader);
        hook.setConfig(mConfig);
        hook.install();
        mOriginalRestoreHook = hook;
    }

    private void installSlimHook() {
        SlimHook hook = new SlimHook(mXposed, mClassLoader);
        hook.setConfig(mConfig);
        hook.setVideoRestorePolicy(() -> {
            OriginalRestoreHook restore = mOriginalRestoreHook;
            return restore != null && restore.canRestoreVideos();
        });
        hook.install();
        mSlimHook = hook;
    }

    private void installNativeDownloadHook() {
        NativeDownloadHook hook = new NativeDownloadHook(mXposed, mClassLoader,
                this::onNativeDownloadRequested);
        mNativeDownloadHook = hook;
        hook.install();
    }

    private void onNativeDownloadRequested() {
        ensureDownloadQueue();
        DownloadQueue queue = mDownload;
        NativeDownloadHook hook = mNativeDownloadHook;
        if (queue != null && hook != null) {
            queue.setHighQualityPolicy(hook::isHighQuality);
            queue.setDownloadPolicy(hook);
            queue.requestFromNative();
        }
    }

    /**
     * 接管相册界面上那句"正在同步 N 个照片和视频"里的 N。
     *
     * <p>那个 N 来自 {@code SyncStateInfo.getDirtyCount()}，也就是数据库里
     * {@code localFlag IN (7,8)} 的行数。替换模式下相册一批只交给我们 3 项（枚举是一批一批来的），
     * 这 3 项传完之前 localFlag 一直是 7/8，于是数字永远停在 3 —— 后面还积压着几千项，
     * 界面上完全看不出来。改成本模块队列的（在传 + 排队）之后才是真的"还剩多少要传"。
     *
     * <p>只改显示：同步调度用的是 {@code SyncStateUtil.getDirtyCount}（走
     * {@code MiSyncProcessInfoImpl} 的 SQL），不经过这个 getter，所以不会因为报了个大数字
     * 就被反复唤起同步任务。相册自己的 {@code updateDirtyCount} 照常按真实行数更新内部状态，
     * 我们只是把读出来的值换掉。
     */
    private void installDirtyCountHook() {
        try {
            Class<?> infoCls = mClassLoader.loadClass(CLS_SYNC_STATE_INFO);
            Method getDirtyCount = infoCls.getDeclaredMethod("getDirtyCount");
            getDirtyCount.setAccessible(true);
            mXposed.hook(getDirtyCount)
                    .setId("gallery_dirty_count")
                    .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                    .intercept(chain -> {
                        try {
                            int[] override = overrideDirtyCount();
                            if (override != null) {
                                return override;
                            }
                        } catch (Throwable t) {
                            Logger.w("event=dirty_count_hook_failed detail=" + t);
                        }
                        return chain.proceed();
                    });
            Logger.i("event=hook_registered method=SyncStateInfo.getDirtyCount");
        } catch (NoSuchMethodException e) {
            Logger.e("event=method_not_found method=SyncStateInfo.getDirtyCount", e);
        } catch (ClassNotFoundException e) {
            Logger.e("event=class_not_found class=" + CLS_SYNC_STATE_INFO, e);
        } catch (Throwable t) {
            Logger.e("event=hook_failed method=SyncStateInfo.getDirtyCount", t);
        }
    }

    /**
     * 该报给相册的"还剩多少要传"，不该接管时返回 null 表示沿用宿主自己的数字。
     *
     * <p>替换模式下**一律**用队列口径，哪怕队列不健康：既然不再放行原链路，宿主那套
     * "localFlag IN (7,8)" 的数字就永远是它自己那 3 项的账，越大越离谱。
     * 非替换模式（原链路照常在跑）与队列还没建时仍返回 null。
     */
    private int[] overrideDirtyCount() {
        Config config = mConfig;
        if (config == null || !config.isReplace()) {
            return null;
        }
        UploadQueue queue = mQueue;
        if (queue == null) {
            return null;
        }
        return queue.queuedCounts();
    }

    /**
     * 装删除 hook，并把回收站队列建起来。
     *
     * <p>与上传/下载队列一样要等宿主 Context：回收站要读 cloud 表、要落盘 state.json，
     * 两件事都得有相册进程的 Context。所以这个钩子只负责"先把观察者挂上"，
     * 队列本身在 {@link #ensureQueue} 里一起建（那里已经拿到了 Context）。
     */
    private void installDeleteHook() {
        try {
            if (mDeleteHook != null) {
                return;
            }
            Context context = HostContext.get(mClassLoader);
            if (context == null) {
                // 与命令接收器同因：install() 那一刻启动还没走到 GalleryApp.onCreate。
                // 启动钩子会再调一次，所以这里不当作错误
                Logger.d("event=delete_hook_deferred reason=no_host_context");
                return;
            }
            DeleteHook hook = new DeleteHook(mXposed, mClassLoader, context.getApplicationContext());
            hook.setConfig(mConfig);
            hook.install();
            mDeleteHook = hook;
        } catch (Throwable t) {
            Logger.e("event=delete_hook_failed", t);
        }
    }

    /** 调用方必须持有 mLock */
    private void ensureTrashLocked() {
        if (mTrash != null) {
            return;
        }
        Config config = mConfig;
        if (config == null) {
            return;
        }
        Context context = HostContext.get(mClassLoader);
        if (context == null) {
            return;
        }
        mTrash = TrashQueue.get(context, config);
        DeleteHook hook = mDeleteHook;
        if (hook != null) {
            hook.setTrash(mTrash);
        }
        Logger.i("event=trash_queue_ready");
    }

    /**
     * 处理一次入队调用。
     *
     * @return true 表示模块已接管这批任务，调用方应直接返回 0 不再入队到相册执行器
     */
    @SuppressWarnings("unchecked")
    private boolean handleInvokeList(Object listObj) {
        if (!(listObj instanceof List)) {
            return false;
        }
        List<Object> raw = (List<Object>) listObj;
        Config config = mConfig;
        if (config == null || config.isOff()) {
            Logger.d("event=invoke_list skipped=" + raw.size() + " reason=module_off");
            return false;
        }
        // 替换模式的语义是"只传 WebDAV，不碰小米云"。所以这里的判据是**模式**，不是
        // "WebDAV 现在能不能用"：地址没填、服务器挂了、队列健康度掉到阈值 —— 这些都不该
        // 让照片改道小米云。拦下来最多是晚一点传（相册的 localFlag 没被改成 0，下一轮枚举
        // 还会把它们交回来），放行则是真的把数据传去了用户不想要的地方。
        boolean replaceMode = config.isReplace();
        if (mReader == null) {
            if (replaceMode) {
                Logger.w("event=invoke_list blocked_no_upload reason=reader_unavailable");
                return true;
            }
            // 非替换模式下读不到同步项时不能拦截，否则会出现相册不上传、模块也拿不到文件的空窗
            Logger.w("event=invoke_list not_blocked reason=reader_unavailable");
            return false;
        }

        List<CloudItemReader.Item> backup = new ArrayList<>();
        int thumbnails = 0;
        int others = 0;
        int unreadable = 0;
        String firstKey = batchEdgeKey(raw, 0);
        String lastKey = batchEdgeKey(raw, raw.size() - 1);
        long nowMs = System.nanoTime() / 1_000_000L;
        if (mLastBatchEnqueued && raw.size() == mLastBatchSize
                && nowMs - mLastBatchAt < BATCH_COALESCE_MS
                && firstKey != null && firstKey.equals(mLastBatchFirstKey)
                && lastKey != null && lastKey.equals(mLastBatchLastKey)) {
            Logger.d("event=invoke_list_coalesced total=" + raw.size());
            return replaceMode;
        }
        mLastBatchAt = nowMs;
        mLastBatchSize = raw.size();
        mLastBatchFirstKey = firstKey;
        mLastBatchLastKey = lastKey;
        mLastBatchEnqueued = false;
        // 这里**故意不截断**：以前有个 max_items_per_sync 上限，真机量下来它从来没生效过 ——
        // 相册每次只交来一页（实测最大 998 行），而上限默认就是 1000，等于永远不裁。
        // 调小它也只是把"一轮喂多少项"切碎，队列该涨还是涨（实测 12649 项待传），
        // 却会多出"这轮没入队的项下轮再来一遍"的空转，所以整条链路连同设置页那一项一起删了。
        // 每批的规模由宿主的分页决定，不由模块决定。
        for (Object rawItem : raw) {
            CloudItemReader.Item item = mReader.read(rawItem);
            if (item == null) {
                unreadable++;
                continue;
            }
            if (CloudItemReader.isThumbnailPriority(item.priority)) {
                thumbnails++;
            } else if (CloudItemReader.isBackupPriority(item.priority)) {
                backup.add(item);
            } else {
                others++;
            }
        }

        Logger.i("event=invoke_list total=" + raw.size()
                + " backup=" + backup.size()
                + " thumb=" + thumbnails
                + " other=" + others
                + " unreadable=" + unreadable
                + " mode=" + config.getReplaceMode()
                + " ready=" + config.isReady());

        if (!backup.isEmpty()) {
            StringBuilder sample = new StringBuilder();
            int n = Math.min(3, backup.size());
            for (int i = 0; i < n; i++) {
                sample.append(backup.get(i).toString()).append(" ");
            }
            Logger.d("event=invoke_list_sample " + sample);
        }

        boolean replace = config.isReplace();

        if (backup.isEmpty()) {
            if (replace) {
                // 替换模式下没有可入队的备份项也要拦住：放行就等于让这批去小米云。
                // 而它们没被标记（localFlag 没被改成 0），下一轮枚举还会再交回来
                Logger.w("event=invoke_list blocked_no_upload reason="
                        + (unreadable > 0 ? "items_unreadable" : "batch_unknown")
                        + " others=" + others + " thumb=" + thumbnails
                        + " unreadable=" + unreadable);
                return true;
            }
            if (unreadable > 0) {
                // 非替换模式：本批次有项读不出来，放行原链路比两边都不传安全
                Logger.w("event=invoke_list not_blocked reason=items_unreadable count=" + unreadable);
                return false;
            }
            if (thumbnails > 0 && others == 0) {
                // 纯缩略图批次：派生数据，不上行任何云
                return false;
            }
            Logger.w("event=invoke_list not_blocked reason=batch_unknown others=" + others
                    + " thumb=" + thumbnails);
            return false;
        }

        UploadQueue queue = ensureQueue();
        if (queue == null) {
            if (replace) {
                // 队列都建不起来（拿不到宿主 Context）也不能改道小米云：整批留在相册那边，
                // 下一轮枚举再试。宁可一直不动，也不能传到用户不想要的云上
                Logger.w("event=invoke_list blocked_no_upload reason=queue_unavailable");
                return true;
            }
            Logger.w("event=invoke_list not_blocked reason=queue_unavailable");
            return false;
        }
        try {
            queue.enqueue(backup);
            mLastBatchEnqueued = true;
        } catch (Throwable t) {
            Logger.e("event=enqueue_failed", t);
            mLastBatchEnqueued = false;
            // 入队炸了同样别改道：这批留在相册侧，下轮再来
            return replace;
        }
        return replace;
    }

    /** 一页任务的首/尾指纹：优先挑那种同一批里每次都一样、又不必整页反射读的字段。 */
    private String batchEdgeKey(List<Object> raw, int index) {
        if (index < 0 || index >= raw.size()) {
            return null;
        }
        CloudItemReader.Item item = mReader.read(raw.get(index));
        if (item == null) {
            return "null";
        }
        return item.priority + "|" + item.path + "|" + item.size;
    }

    /**
     * 建队列。启动时与首次入队时都会走到这里。
     *
     * <p>拿到宿主 Context 是前提：{@code GalleryApp.sGetAndroidContext()} 在 onCreate 之后
     * 一定可用，所以启动钩子里建是安全的。配置还没下发时不建 —— worker 起来第一圈会读到
     * null 而白睡 120 秒，不如等 {@link #setConfig} 到了再补建。
     */
    private UploadQueue ensureQueue() {
        UploadQueue queue = mQueue;
        if (queue != null) {
            return queue;
        }
        if (mConfig == null) {
            Logger.d("event=queue_deferred reason=config_not_ready");
            return null;
        }
        synchronized (this) {
            if (mQueue == null) {
                Context context = HostContext.get(mClassLoader);
                if (context != null) {
                    // WebDavClient 需要宿主 Context 才能挑"能出网的网络"（见 addressFor 的说明）：
                    // 设备上装了纯组网的 VPN 时，相册 UID 的默认路由会被送进一张没有默认路由、
                    // 也没有 DNS 的表，只有显式绑定到 Wi-Fi/移动网络那条路才解析得了、连得通
                    WebDavClient.setHostContext(context);
                    // config 必须随构造传入：worker 在构造函数里就启动，晚一步 setConfig
                    // 它第一圈就会读到 null 而白睡 120 秒
                    mQueue = UploadQueue.get(context, mConfig);
                    mQueue.setMarker(markSynced(context));
                    mQueue.setProgress(HostSyncState.create(mClassLoader));
                    // Downloads share progress reporting but follow Gallery's own native events.
                    // Upload-idle callbacks only drain the remote trash queue.
                    mQueue.setIdleListener(this::onUploadIdle);
                    Logger.i("event=queue_ready");
                } else {
                    Logger.w("event=queue_unavailable reason=no_host_context");
                }
            }
            // 回收站要读 cloud 表、要落盘，与上传队列同一条件（拿到宿主 Context）
            ensureTrashLocked();
            return mQueue;
        }
    }

    /**
     * 建下载队列。与上传队列同一时刻、同一条件：都在拿到宿主 Context 之后。
     *
     * <p>下载线程等待相册原生自动下载事件；是否可下载由相册开关和原生条件判断。
     */
    private void ensureDownloadQueue() {
        synchronized (this) {
            if (mDownload != null) {
                return;
            }
            Config config = mConfig;
            if (config == null) {
                return;
            }
            Context context = HostContext.get(mClassLoader);
            if (context == null) {
                return;
            }
            DownloadQueue queue = DownloadQueue.get(context, config);
            NativeDownloadHook nativeDownload = mNativeDownloadHook;
            if (nativeDownload != null) {
                queue.setHighQualityPolicy(nativeDownload::isHighQuality);
                queue.setDownloadPolicy(nativeDownload);
            }
            UploadQueue upload = mQueue;
            if (upload != null) {
                queue.addQueueView(upload.publisher());
            }
            // 用实例而不是方法引用：隐藏目录里的预览要走 adoptPreview（改既有云行），
            // 不是 ingest（宿主扫描器会建新行）
            queue.setIngest(new ScannerIngest(mClassLoader));
            mDownload = queue;
            Logger.i("event=download_queue_ready");
        }
    }

    /** Upload-idle cleanup is independent of Gallery's native download schedule. */
    private void onUploadIdle() {
        TrashQueue trash;
        synchronized (this) {
            trash = mTrash;
        }
        if (trash == null) {
            synchronized (this) {
                ensureTrashLocked();
                trash = mTrash;
            }
        }
        if (trash != null) {
            // 搬回收站不与下载抢线程：它整轮只有几个 MOVE，几十毫秒的事。
            // 真放到独立线程反而多一处生命周期要管
            try {
                trash.drain();
            } catch (Throwable t) {
                Logger.w("event=trash_drain_failed detail=" + t);
            }
        }
    }

    /**
     * 只有替换模式才需要替相册标记：镜像模式下原链路照常跑，宿主上传成功自己会写 localFlag=0。
     * 模式可能被用户随时改，所以判据放在调用时而不是装配时。
     *
     * <p>分开记录模式（{@code local_flag_mode=per_target}）下更是**一个字节都不写**：
     * 那个开关的意思就是"别动相册那条记录，模块自己按目标分开记录"。此时相册的 {@code localFlag}
     * 会一直停在 7/8，界面上"正在同步 N 个"由模块接管（数字来自队列，仍然是对的），
     * 相册每轮枚举都会把同一批项重新交下来 —— 队列按 路径+大小+mtime 在当前目标那条记录里
     * 命中就直接跳过，不产生流量。代价是相册自己那份"脏项计数"永远归不了零，
     * 设置页的说明里写清楚了。
     */
    private SyncMarker markSynced(Context context) {
        MarkSynced target = new MarkSynced(context);
        return rowId -> {
            Config config = mConfig;
            if (config == null || !config.isReplace()) {
                return;
            }
            if (config.isLocalFlagPerTarget()) {
                // 只在 verbose 下输出：每传成功一项都会走这里，默认级别刷屏
                Logger.d("event=mark_synced_skipped reason=per_target row=" + rowId);
                return;
            }
            target.markSynced(rowId);
        };
    }

    /**
     * 接收模块 App 发来的命令广播（见 {@link ProgressProtocol#ACTION_COMMAND}）。
     *
     * <p>目前只有一条命令：把当前账号的"已上传"标记全部重置回未上传。它必须在**相册进程**
     * 里执行 —— 要写的是相册自己的 cloud provider，而注入进这个进程的代码用的是相册的 UID，
     * 天然有写权限；模块 App 进程没有。
     *
     * <p>注册时用 {@code RECEIVER_EXPORTED}：发送方是另一个 App（模块 App）的 UID，
     * 不导出的话一个字节也收不到。这不会扩大攻击面 —— action 是带包名的私有字符串，
     * 而框架本身已经用 {@code setPackage} 把它定向到了模块 App。
     */
    private void ensureCommandReceiver() {
        try {
            if (mCommandReceiver != null) {
                return;
            }
            Context context = HostContext.get(mClassLoader);
            if (context == null) {
                Logger.d("event=command_receiver_deferred reason=no_host_context");
                return;
            }
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    try {
                        handleCommand(intent.getStringExtra(ProgressProtocol.EXTRA_COMMAND));
                    } catch (Throwable t) {
                        // 广播接收器里漏出去的异常会打死宿主的接收线程
                        Logger.e("event=command_failed", t);
                    }
                }
            };
            IntentFilter filter = new IntentFilter(ProgressProtocol.ACTION_COMMAND);
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(receiver, filter);
            }
            mCommandReceiver = receiver;
            Logger.i("event=command_receiver_registered");
        } catch (Throwable t) {
            Logger.e("event=command_receiver_failed", t);
        }
    }

    /** 执行一条来自模块 App 的命令。未知命令只留痕，不猜。 */
    private void handleCommand(String command) {
        if (command == null || command.isEmpty()) {
            return;
        }
        Logger.i("event=command_received command=" + command);
        if (ProgressProtocol.CMD_RESET_UPLOADED.equals(command)) {
            resetUploaded();
        } else if (ProgressProtocol.CMD_RESTORE_METADATA_ORIGINALS.equals(command)) {
            Context context = HostContext.get(mClassLoader);
            Config config = mConfig;
            if (context == null || config == null || !config.isReady()) {
                Logger.w("event=metadata_restore_job_skipped status=no_ready_context");
                return;
            }
            mMetadataRestoreJob.start(context, config);
        }
    }

    /**
     * 重置"已上传"标记并清掉模块自己的待传账，让这批照片重新走一遍完整备份。
     *
     * <p>两件事缺一不可：
     * <ol>
     *   <li>把相册的 {@code localFlag} 退回 7 —— 否则相册不会再把它们交给 {@code invokeList}
     *       （它的枚举谓词是 {@code localFlag != 0}），模块根本看不见它们。</li>
     *   <li>清掉模块的 done 记录 —— 否则相册交下来之后，队列按"路径+大小+mtime"命中旧记录，
     *       直接判成"这一份传过了"跳过，一个字节都不发。这条正是 AGENTS.md 里
     *       "换了目录之后备份就不动了"那个坑的同一个原因。</li>
     * </ol>
     */
    private void resetUploaded() {
        Context context = HostContext.get(mClassLoader);
        if (context == null) {
            Logger.w("event=reset_uploaded_failed reason=no_host_context");
            return;
        }
        try {
            int rows = new MarkSynced(context).resetUploaded();
            // 清完模块的账要立刻落盘：进程在下次正常落盘前被杀的话，
            // 旧 done 表会从 state.json 原样读回来，这次重置就白做了
            UploadQueue queue = mQueue;
            if (queue != null) {
                queue.resetLedger();
            }
            Logger.i("event=reset_uploaded_done rows=" + rows);
        } catch (Throwable t) {
            Logger.e("event=reset_uploaded_failed", t);
        }
    }

    /** 热重载交接：停掉上传与下载线程，新 generation 会从 state.json 重建队列 */
    public void shutdown() {
        UploadQueue queue;
        DownloadQueue download;
        TrashQueue trash;
        BroadcastReceiver receiver;
        OriginalRestoreHook restoreHook;
        synchronized (this) {
            queue = mQueue;
            mQueue = null;
            download = mDownload;
            mDownload = null;
            trash = mTrash;
            mTrash = null;
            receiver = mCommandReceiver;
            mCommandReceiver = null;
            restoreHook = mOriginalRestoreHook;
            mOriginalRestoreHook = null;
            mSlimHook = null;
            NativeDownloadHook nativeDownload = mNativeDownloadHook;
            mNativeDownloadHook = null;
            if (nativeDownload != null) nativeDownload.shutdown();
        }
        if (restoreHook != null) restoreHook.shutdown();
        mMetadataRestoreJob.shutdown();
        if (receiver != null) {
            try {
                HostContext.get(mClassLoader).unregisterReceiver(receiver);
            } catch (Throwable t) {
                // 注销失败最多是旧 receiver 还挂着；它只会回一条幂等的重置命令
                Logger.w("event=command_receiver_unregister_failed", t);
            }
        }
        if (queue != null) {
            queue.shutdown();
        }
        if (download != null) {
            download.shutdown();
        }
        if (trash != null) {
            trash.shutdown();
        }
    }
}
