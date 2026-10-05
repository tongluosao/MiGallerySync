package com.tongluosao.gallerysync.hook;

import android.content.Context;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import com.tongluosao.gallerysync.core.Config;
import com.tongluosao.gallerysync.core.Logger;
import com.tongluosao.gallerysync.core.TrashQueue;

import io.github.libxposed.api.XposedInterface;

/**
 * 删除方向的 Hook：用户在相册里删掉一张照片时，把远端那一份排进回收站队列。
 *
 * <p><b>为什么挂这个点</b>：上传方向的 {@code UpDownloadManager.invokeList} 只管上传，
 * 删除走的是另一条完全平行的链路（{@code SyncOwnerDataFromLocal} 里
 * {@code localFlag == 2} 的分支 → {@code RetryOperation} → {@code DeleteCloudItem}），
 * 一个都碰不到。而 {@code DeleteMethod.delete} 是那两条路共同的入口：
 * 相册的 {@code CloudManager} 处理删除请求时调它，参数里带着 cloud 表的行号。
 *
 * <p><b>为什么用行号而不是路径</b>：这个方法的入参就是 {@code long[]} 行号，
 * 路径要回 cloud 表里查 `localFile` 才有。这不是绕远路 ——
 * 删除动作本身只知道"哪些行"，而回表反推能拿到与上传侧**同一套规则**算出来的远端路径。
 *
 * <p><b>只观测、不拦截</b>：返回原样放行，删除该怎么走还怎么走。
 * 模块在这里做的全部事情就是"记下来这一份的远端副本该进回收站了"。
 *
 * <p><b>要区分"仅删本地"</b>：{@code DeleteMethod.delete} 有一个 {@code i == 1} 的分支
 * 表示"只删手机上的、不同步给云端"。那种情况下用户的意思恰恰是"别动远端"，
 * 所以那个分支一律不排 —— 这也正是回收站语义与用户意图对齐的地方。
 */
final class DeleteHook {

    private static final String CLS_DELETE_METHOD =
            "com.miui.gallery.provider.cloudmanager.method.cloud.delete.DeleteMethod";

    private final XposedInterface mXposed;
    private final ClassLoader mClassLoader;
    private final Context mContext;
    private volatile Config mConfig;
    private volatile TrashQueue mTrash;

    DeleteHook(XposedInterface xposed, ClassLoader classLoader, Context context) {
        mXposed = xposed;
        mClassLoader = classLoader;
        mContext = context;
    }

    void setConfig(Config config) {
        mConfig = config;
    }

    void setTrash(TrashQueue trash) {
        mTrash = trash;
    }

    void install() {
        try {
            Class<?> cls = mClassLoader.loadClass(CLS_DELETE_METHOD);
            // 8 参那个重载：只有它带 arrayList2（"真的删掉了哪些"）与 deleteReason，
            // 6 参那个只是转调它。挂外层才能同时看到两者
            Method delete = null;
            for (Method candidate : cls.getDeclaredMethods()) {
                if (!"delete".equals(candidate.getName())) {
                    continue;
                }
                Class<?>[] params = candidate.getParameterTypes();
                if (params.length == 8 && params[4] == long[].class && params[5] == int.class) {
                    delete = candidate;
                    break;
                }
            }
            if (delete == null) {
                Logger.w("event=method_not_found method=DeleteMethod.delete/8");
                return;
            }
            delete.setAccessible(true);
            mXposed.hook(delete)
                    .setId("gallery_delete")
                    .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                    .intercept(chain -> {
                        try {
                            // args: (context, db, manager, arrayList, long[] ids, int reason,
                            //        arrayList2, int deleteOptions)
                            Object idsArg = chain.getArg(4);
                            Object optionsArg = chain.getArg(7);
                            long[] ids = idsArg instanceof long[] ? (long[]) idsArg : null;
                            int deleteOptions = optionsArg instanceof Integer ? (Integer) optionsArg : 0;
                            handleDelete(ids, deleteOptions);
                        } catch (Throwable t) {
                            // 观测失败绝不能影响相册自己的删除流程
                            Logger.w("event=delete_hook_failed detail=" + t);
                        }
                        return chain.proceed();
                    });
            Logger.i("event=hook_registered method=DeleteMethod.delete");
        } catch (ClassNotFoundException e) {
            Logger.w("event=class_not_found class=" + CLS_DELETE_METHOD);
        } catch (Throwable t) {
            Logger.e("event=hook_failed method=DeleteMethod.delete", t);
        }
    }

    /**
     * @param deleteOptions 1 表示"仅删本地"。那种情况用户明确不要动云端，一律跳过
     */
    private void handleDelete(long[] ids, int deleteOptions) {
        if (ids == null || ids.length == 0) {
            return;
        }
        Config config = mConfig;
        TrashQueue trash = mTrash;
        if (config == null || trash == null || !config.isReplace()) {
            // 非替换模式下相册自己的删除同步还在跟小米云打交道，远端 WebDAV 上没有对应的
            // 东西可搬，硬搬只会把用户已经备份的内容移走
            return;
        }
        if (deleteOptions == 1) {
            Logger.d("event=delete_local_only count=" + ids.length);
            return;
        }
        List<String> remotePaths = TrashQueue.remotePathsOf(mContext, config, ids);
        if (remotePaths.isEmpty()) {
            Logger.d("event=delete_no_remote count=" + ids.length);
            return;
        }
        Logger.i("event=delete_detected ids=" + ids.length + " remote=" + remotePaths.size());
        trash.schedule(new ArrayList<>(remotePaths));
    }
}
