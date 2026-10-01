package com.tongluosao.gallerysync.hook;

import android.content.Context;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import com.tongluosao.gallerysync.core.Config;
import com.tongluosao.gallerysync.core.Logger;
import com.tongluosao.gallerysync.core.SyncMarker;
import com.tongluosao.gallerysync.core.UploadQueue;

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
    private volatile Config mConfig;
    /** 宿主 Application 是否已经 onCreate：配置与它谁先到都有可能，两边都要能补建队列 */
    private volatile boolean mAppStarted;

    private GalleryHooks(XposedInterface xposed, ClassLoader classLoader) {
        this.mXposed = xposed;
        this.mClassLoader = classLoader;
        this.mReader = CloudItemReader.create(classLoader);
    }

    public static GalleryHooks install(XposedInterface xposed, ClassLoader classLoader) {
        GalleryHooks hooks = new GalleryHooks(xposed, classLoader);
        hooks.installInvokeListHook();
        hooks.installUploadOutletHook();
        hooks.installAppStartHook();
        hooks.installDirtyCountHook();
        return hooks;
    }

    public void setConfig(Config config) {
        mConfig = config;
        UploadQueue queue = mQueue;
        if (queue != null) {
            queue.setConfig(config);
        } else if (mAppStarted) {
            // 进程已经起来了，队列却因为"配置还没下发"没建成：配置一到就补建，别再等一轮同步
            ensureQueue();
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
                        try {
                            Object desc = chain.getArg(1);
                            String path = (String) filePath.get(desc);
                            long size = fileSize.getLong(desc);
                            Config config = mConfig;
                            boolean shouldBeBlocked = config != null && config.isReplace();
                            if (shouldBeBlocked) {
                                Logger.w("event=upload_outlet_unexpected file=" + path
                                        + " size=" + size
                                        + " reason=replace_mode_must_not_reach_xiaomi_cloud");
                            } else {
                                Logger.d("event=upload_outlet file=" + path + " size=" + size);
                            }
                        } catch (Throwable t) {
                            Logger.e("event=upload_outlet_hook_failed", t);
                        }
                        return chain.proceed();
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
        int limit = config.getMaxItemsPerSync();
        if (limit <= 0) {
            if (replaceMode) {
                Logger.w("event=invoke_list blocked_no_upload reason=limit_invalid limit=" + limit);
                return true;
            }
            // 上限为 0 时一个都入不了队，此时若还拦截就是"相册不传、模块也没传"
            Logger.w("event=invoke_list not_blocked reason=limit_invalid limit=" + limit);
            return false;
        }
        for (Object rawItem : raw) {
            CloudItemReader.Item item = mReader.read(rawItem);
            if (item == null) {
                unreadable++;
                continue;
            }
            if (CloudItemReader.isThumbnailPriority(item.priority)) {
                thumbnails++;
            } else if (CloudItemReader.isBackupPriority(item.priority)) {
                if (backup.size() < limit) {
                    backup.add(item);
                }
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
                // 而它们没被记账（localFlag 没被改成 0），下一轮枚举还会再交回来
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
        } catch (Throwable t) {
            Logger.e("event=enqueue_failed", t);
            // 入队炸了同样别改道：这批留在相册侧，下轮再来
            return replace;
        }
        return replace;
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
                    // config 必须随构造传入：worker 在构造函数里就启动，晚一步 setConfig
                    // 它第一圈就会读到 null 而白睡 120 秒
                    mQueue = UploadQueue.get(context, mConfig);
                    mQueue.setMarker(markSynced(context));
                    mQueue.setProgress(HostSyncState.create(mClassLoader));
                    Logger.i("event=queue_ready");
                } else {
                    Logger.w("event=queue_unavailable reason=no_host_context");
                }
            }
            return mQueue;
        }
    }

    /**
     * 只有替换模式才需要替相册记账：镜像模式下原链路照常跑，宿主上传成功自己会写 localFlag=0。
     * 模式可能被用户随时改，所以判据放在调用时而不是装配时。
     */
    private SyncMarker markSynced(Context context) {
        MarkSynced target = new MarkSynced(context);
        return rowId -> {
            Config config = mConfig;
            if (config != null && config.isReplace()) {
                target.markSynced(rowId);
            }
        };
    }

    /** 热重载交接：停掉上传线程，新 generation 会从 state.json 重建队列 */
    public void shutdown() {
        UploadQueue queue;
        synchronized (this) {
            queue = mQueue;
            mQueue = null;
        }
        if (queue != null) {
            queue.shutdown();
        }
    }
}
