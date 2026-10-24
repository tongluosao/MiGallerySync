package com.tongluosao.gallerysync.hook;

import com.tongluosao.gallerysync.core.Logger;
import com.tongluosao.gallerysync.core.UploadQueue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import io.github.libxposed.api.XposedInterface;

/**
 * 让"云同步列表"页只列本模块真正待上传的那些行。
 *
 * <p>那个页面是 {@code com.miui.gallery.activity.BackupDetailActivity} +
 * {@code com.miui.gallery.ui.BackupDetailFragment}（布局 id {@code backup_detail}，标题
 * "云同步列表"）。它的列表不是 cloud 表直查，而是 {@code PhotoListFragmentBase.configLoader}
 * 给 {@code CursorLoader} 配四个值：{@code BackupDetailFragment.getUri()}（MediaStore 的
 * media view）、{@code getProjection()}、{@code getSelection()}、{@code getSelectionArgs()}。
 *
 * <p>{@code getSelection()} 拼出来的是
 * {@code "(alias_sync_state = 3 OR alias_sync_state = 2 OR (alias_sync_state = 0 AND _id IN (...))) AND (localFlag != 19)"}
 * （smali 19a0e4），而 {@code alias_sync_state} 就是
 * {@code CASE WHEN localFlag = 0 THEN 0 WHEN localFlag IN (5,6,9) THEN 1 ELSE 3 END}
 * （{@code MediaCursorHelper.CLOUD_PROJECTION} 第 16 列）。也就是说这一页的口径完全是
 * 小米云的 {@code localFlag}：凡是"相册自己认为还没传上去"的行都进来，跟本模块队列毫无关系。
 * 真机实测：cloud 表 {@code localFlag IN (7,8)} 有 13787 行、{@code localFlag != 0} 有 13809 行，
 * 而同一时刻模块 state.json 的 pending 是空数组 —— 于是页面列出 1.3 万项"待同步"，
 * 模块实际上一个都不欠。
 *
 * <p>改法：只在这一处收窄选择条件，追加 {@code AND _id IN (队列里待上传项的 cloud 行号)}。
 * <ul>
 *   <li>取 id 的理由：media view 与 cloud 表是同一张表（同一个 provider，同一个 _id 空间，
 *       实测 media 路径按 {@code localFlag=7} 也返回同样的 13787 行），而队列里
 *       {@code Task.rowId} 存的就是 cloud 表 {@code _id}（{@code CloudItemReader} 从
 *       {@code DBItem.getId()} 读的）。不猜列名，也不依赖 cursor 的投影顺序。</li>
 *   <li>不动 {@code proceed()} 的返回值时就是原样（配置不是替换模式、队列还没建、读 id 失败），
 *       页面照旧，绝不因为这里出错把相册页面搞崩。</li>
 *   <li>纯 SQL 收窄，不碰网络、不碰小米云账号，也不改库里的数据：页面少列的项
 *       localFlag 还是 7/8，下一次枚举照旧会交回队列。</li>
 * </ul>
 *
 * <p>刷新时机是够的：相册每次收到上传状态变化都会走
 * {@code BackupDetailFragment$4.run → LoaderManager.getInstance(this).getLoader(1) →
 * configLoader(loader) → loader.onContentChanged()}，每次都会重新调 {@code getSelection()}，
 * 所以队列变短之后列表会跟着收窄。
 */
final class BackupListHook {

    private static final String CLS_FRAGMENT = "com.miui.gallery.ui.BackupDetailFragment";
    /** 队列里的行号上限：再多就把整条 selection 收着点，别把 SQLite 的语句塞爆 */
    private static final int MAX_IDS = 50000;

    /** 队列私有字段（UploadQueue 只暴露计数，不暴露待传项的 id；不改 core 就用反射读） */
    private static volatile Field sLockField;
    private static volatile Field sPendingField;
    private static volatile Field sRowIdField;
    private static volatile Class<?> sTaskClass;

    private final XposedInterface mXposed;
    private final ClassLoader mClassLoader;
    private final Supplier<String[]> mPendingIds;

    BackupListHook(XposedInterface xposed, ClassLoader classLoader, Supplier<String[]> pendingIds) {
        mXposed = xposed;
        mClassLoader = classLoader;
        mPendingIds = pendingIds;
    }

    void install() {
        try {
            Class<?> fragment = Class.forName(CLS_FRAGMENT, false, mClassLoader);
            Method getSelection = fragment.getDeclaredMethod("getSelection");
            getSelection.setAccessible(true);
            mXposed.hook(getSelection)
                    .setId("gallery_backup_list_selection")
                    .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                    .intercept(chain -> {
                        Object original = chain.proceed();
                        try {
                            return narrow(original instanceof String ? (String) original : null, original);
                        } catch (Throwable t) {
                            Logger.w("event=backup_list_narrow_failed detail=" + t, t);
                            return original;
                        }
                    });
            Logger.i("event=hook_registered method=BackupDetailFragment.getSelection");
        } catch (NoSuchMethodException e) {
            Logger.e("event=method_not_found method=BackupDetailFragment.getSelection", e);
        } catch (ClassNotFoundException e) {
            Logger.e("event=class_not_found class=" + CLS_FRAGMENT, e);
        } catch (Throwable t) {
            Logger.e("event=hook_failed method=BackupDetailFragment.getSelection", t);
        }
    }

    private Object narrow(String base, Object fallback) {
        if (base == null || base.isEmpty()) {
            return fallback;
        }
        String[] ids = mPendingIds == null ? null : mPendingIds.get();
        if (ids == null) {
            // 不接管：非替换模式、队列没建起来、或行号读不出来
            return fallback;
        }
        StringBuilder sb = new StringBuilder(base.length() + ids.length * 8 + 32);
        sb.append(base).append(" AND (");
        if (ids.length == 0) {
            // 队列里一张都没剩：说"没有了"就得是空列表，别退回去列小米云的那 1.3 万行
            sb.append("1 = 0");
        } else {
            sb.append("_id IN (");
            for (int i = 0; i < ids.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(ids[i]);
            }
            sb.append(')');
        }
        sb.append(')');
        Logger.d("event=backup_list_narrowed pending=" + ids.length);
        return sb.toString();
    }

    /**
     * 队列里"在传 + 排队"那些项的 cloud 表行号。
     *
     * @return null 表示这次不该由模块接管（读不出来也算）；空数组表示队列是空的
     */
    static String[] pendingRowIds(UploadQueue queue) {
        if (queue == null) {
            return null;
        }
        try {
            Field lockField = sLockField;
            Field pendingField = sPendingField;
            if (lockField == null || pendingField == null) {
                lockField = UploadQueue.class.getDeclaredField("mLock");
                lockField.setAccessible(true);
                pendingField = UploadQueue.class.getDeclaredField("mPending");
                pendingField.setAccessible(true);
                sLockField = lockField;
                sPendingField = pendingField;
            }
            Object monitor = lockField.get(queue);
            if (monitor == null) {
                return null;
            }
            Set<String> ids = new HashSet<>();
            int withoutRowId = 0;
            int badRowId = 0;
            // 与队列自己一样在 mLock 内读，否则边传边改的 LinkedHashMap 会抛 ConcurrentModification
            synchronized (monitor) {
                Object raw = pendingField.get(queue);
                if (!(raw instanceof Map)) {
                    return null;
                }
                for (Object task : ((Map<?, ?>) raw).values()) {
                    if (ids.size() >= MAX_IDS) {
                        break;
                    }
                    String rowId = rowIdOf(task);
                    if (rowId == null) {
                        withoutRowId++;
                        continue;
                    }
                    String trimmed = rowId.trim();
                    if (!isDigits(trimmed)) {
                        badRowId++;
                        continue;
                    }
                    ids.add(trimmed);
                }
            }
            if (withoutRowId > 0 || badRowId > 0) {
                Logger.w("event=backup_list_ids_partial no_rowid=" + withoutRowId
                        + " bad_rowid=" + badRowId);
            }
            return ids.toArray(new String[0]);
        } catch (Throwable t) {
            Logger.w("event=backup_list_ids_failed detail=" + t);
            return null;
        }
    }

    /** 读 {@code UploadQueue.Task.rowId}（私有静态内部类，只能反射） */
    private static String rowIdOf(Object task) throws IllegalAccessException, NoSuchFieldException {
        if (task == null) {
            return null;
        }
        Field field = sRowIdField;
        if (field == null || sTaskClass != task.getClass()) {
            field = task.getClass().getDeclaredField("rowId");
            field.setAccessible(true);
            sRowIdField = field;
            sTaskClass = task.getClass();
        }
        Object value = field.get(task);
        return value == null ? null : String.valueOf(value);
    }

    /** 只接受纯数字：selection 是拼进 SQL 的字符串，别的形状一律丢 */
    private static boolean isDigits(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }
}
