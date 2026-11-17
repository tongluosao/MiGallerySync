package com.tongluosao.gallerysync.hook;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import com.tongluosao.gallerysync.core.Logger;
import com.tongluosao.gallerysync.hook.gallery.Binding;
import com.tongluosao.gallerysync.hook.gallery.Profiles;

/**
 * 通过反射读取相册内部同步项。所有访问都做空值与异常保护：
 * 相册升级后字段或方法改名时只会导致读取失败，不会把异常抛回宿主调用栈。
 *
 * <p>类名与字段名不写死：先看当前版本档案（{@link Profiles#resolveBinding}）里声明了什么，
 * 档案缺失或没声明时才回落到下面这几个 4.3.1.6 的类名。手机上两者解析到同一个类，
 * 行为与改造前逐字一致；平板（旧版 3.6.2.10）走的则是档案里的
 * {@code com.miui.gallery.cloud.RequestCloudItem} 那套。
 *
 * <p>解析只做一次（{@link #create}），失败返回 null 并留下 {@code reader_binding_missing}，
 * 调用点据此把上传拦在安全兜底上 —— 这正是平板以前 {@code reader_init_failed} 的成因。
 */
public final class CloudItemReader {

    /** 新版相册（4.3.x）的同步项类名：档案不可用时的最后回落；旧版类名见 {@link Profiles#G2024} */
    private static final String CLS_REQUEST_CLOUD_ITEM =
            "com.miui.gallery.data.remote.RequestCloudItem";
    private static final String CLS_REQUEST_ITEM_BASE =
            "com.miui.gallery.data.remote.RequestItemBase";

    /** 需要备份到 WebDAV 的优先级：2=自动视频 3=手动视频 4=自动图片 5=手动/共享图片 */
    private static final int PRIORITY_AUTO_VIDEO = 2;
    private static final int PRIORITY_MANUAL_VIDEO = 3;
    private static final int PRIORITY_AUTO_IMAGE = 4;
    private static final int PRIORITY_MANUAL_IMAGE = 5;
    /** 6=缩略图，不备份 */
    private static final int PRIORITY_THUMBNAIL = 6;

    private final Field fDbCloud;
    private final Field fPriority;
    private final Method mGetId;
    private final Method mGetLocalFile;
    private final Method mGetSize;
    private final Method mIsVideoType;
    private final Method mGetMixedDateTime;
    private final Method mGetSha1;

    private CloudItemReader(ClassLoader cl) throws Exception {
        Binding itemBinding = Profiles.resolveBinding(cl, CLS_REQUEST_CLOUD_ITEM);
        Binding baseBinding = Profiles.resolveBinding(cl, CLS_REQUEST_ITEM_BASE);
        Class<?> itemCls = clazz(itemBinding, cl, CLS_REQUEST_CLOUD_ITEM);
        Class<?> baseCls = clazz(baseBinding, cl, CLS_REQUEST_ITEM_BASE);

        fDbCloud = field(itemBinding, itemCls, "dbCloud");
        fPriority = field(baseBinding, baseCls, "priority");
        // 从 dbCloud 字段的声明类型上取方法，不要按名字猜类名：4.3.1.6 里
        // com.miui.gallery.data.local.DBItem 只是一个带 getId() 的接口，
        // getLocalFile/getSize 这些方法全在 DBImage 上，猜错就整条链路放行
        Class<?> dbCls = fDbCloud.getType();
        mGetId = dbCls.getMethod("getId");
        mGetLocalFile = dbCls.getMethod("getLocalFile");
        mGetSize = dbCls.getMethod("getSize");
        mIsVideoType = dbCls.getMethod("isVideoType");
        mGetMixedDateTime = dbCls.getMethod("getMixedDateTime");
        mGetSha1 = dbCls.getMethod("getSha1");
        Logger.i("event=reader_ready dbClass=" + dbCls.getName()
                + " profile=" + Profiles.profileName(cl));
    }

    public static CloudItemReader create(ClassLoader cl) {
        try {
            return new CloudItemReader(cl);
        } catch (Throwable t) {
            // 类名/字段对不上时这是唯一线索：把缺了什么写进 missing=，别只留一行堆栈
            Logger.w("event=reader_binding_missing profile=" + Profiles.profileName(cl)
                    + " missing=" + describe(t));
            Logger.e("event=reader_init_failed", t);
            return null;
        }
    }

    /** 档案里声明了这个类就用它解析到的 Class，否则回落写死的类名（拿不到就抛给调用点） */
    private static Class<?> clazz(Binding binding, ClassLoader cl, String hardcodedName)
            throws Exception {
        if (binding != null && binding.clazz() != null) {
            return binding.clazz();
        }
        return cl.loadClass(hardcodedName);
    }

    /** 档案里的字段优先，取不到再按写死的字段名查一次 */
    private static Field field(Binding binding, Class<?> cls, String name) throws Exception {
        if (binding != null) {
            Field declared = binding.field(name);
            if (declared != null) {
                return declared;
            }
        }
        return cls.getField(name);
    }

    /** 失败原因：异常自带的信息（类名/字段名）比类名本身有用，没有就退回异常类型 */
    private static String describe(Throwable t) {
        String message = t.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return t.getClass().getSimpleName();
        }
        return message;
    }

    public static boolean isBackupPriority(int priority) {
        return priority == PRIORITY_AUTO_VIDEO
                || priority == PRIORITY_MANUAL_VIDEO
                || priority == PRIORITY_AUTO_IMAGE
                || priority == PRIORITY_MANUAL_IMAGE;
    }

    public static boolean isThumbnailPriority(int priority) {
        return priority == PRIORITY_THUMBNAIL;
    }

    private static long toLong(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    /**
     * 本地路径兜底。相册的 localFile 列存的是绝对路径，但存在两种异常值：
     * 空串（该项还没落到本地，不该上传）与相对路径（拼上主存储前缀才能读到文件）。
     * H1 阶段拿不到别的路径来源：宿主上传用的就是 dbCloud.getLocalFile()，
     * doUpload 的 filePath 只在 H2 观测点可见，而替换模式下 H2 根本不会被执行到。
     */
    private static String normalizePath(String raw) {
        if (raw == null) {
            return null;
        }
        String path = raw.trim();
        if (path.isEmpty()) {
            return null;
        }
        if (path.startsWith("/")) {
            return path;
        }
        String absolute = "/storage/emulated/0/" + path;
        Logger.d("event=item_path_relative raw=" + path + " fixed=" + absolute);
        return absolute;
    }

    /** 读取一个同步项；返回 null 表示该项不可用（读取失败或缺少本地文件路径） */
    public Item read(Object requestCloudItem) {
        if (requestCloudItem == null) {
            return null;
        }
        try {
            Object dbItem = fDbCloud.get(requestCloudItem);
            if (dbItem == null) {
                return null;
            }
            int priority = fPriority.getInt(requestCloudItem);
            String path = normalizePath((String) mGetLocalFile.invoke(dbItem));
            if (path == null) {
                return null;
            }
            // 不强转：返回类型一变就是 ClassCastException，整批项会被当成"读不出来"而放行
            long size = toLong(mGetSize.invoke(dbItem));
            boolean video = Boolean.TRUE.equals(mIsVideoType.invoke(dbItem));
            long date = toLong(mGetMixedDateTime.invoke(dbItem));
            Object sha1Obj = mGetSha1.invoke(dbItem);
            String sha1 = sha1Obj == null ? "" : String.valueOf(sha1Obj);
            // cloud 表的 _id：相册自己也是拿它当条件写 localFlag=0 的
            Object idObj = mGetId.invoke(dbItem);
            String rowId = idObj == null ? null : String.valueOf(idObj);
            return new Item(path, size, priority, video, date, sha1, rowId);
        } catch (Throwable t) {
            Logger.d("event=item_read_failed item=" + requestCloudItem.getClass().getName());
            return null;
        }
    }

    public static final class Item {
        public final String path;
        public final long size;
        public final int priority;
        public final boolean video;
        public final long date;
        public final String sha1;
        /** cloud 表行号，上传成功后按它写回 localFlag；读不到时为 null，只影响标记不影响上传 */
        public final String rowId;

        Item(String path, long size, int priority, boolean video, long date, String sha1,
                String rowId) {
            this.path = path;
            this.size = size;
            this.priority = priority;
            this.video = video;
            this.date = date;
            this.sha1 = sha1;
            this.rowId = rowId;
        }

        @Override
        public String toString() {
            return "Item{path=" + path + ", size=" + size + ", priority=" + priority
                    + ", video=" + video + "}";
        }
    }
}
