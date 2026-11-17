package com.tongluosao.gallerysync.hook.gallery;

import com.tongluosao.gallerysync.core.Logger;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 相册版本档案表：一个版本一个 {@link Profile}，列表按"新版本在前"排列。
 *
 * <h3>以后怎么支持一个新相册版本</h3>
 * <ol>
 *   <li>在 {@code hook/gallery/} 下新建（或在这里追加）一个 Profile，名字用 {@code G<版本号>}，
 *       与 {@link #all()} 里的顺序保持一致；</li>
 *   <li>{@code sentinel(...)} 填这个版本一定有、旧版本没有的类名 —— 写
 *       {@code Class#getName()} 的形式，嵌套类用 {@code $}（例如
 *       {@code com.miui.gallery.provider.GalleryContract$Cloud}）。别拿"大概存在"的类当
 *       sentinel，一次误命中会把整个版本认错；</li>
 *   <li>{@code ref(...)} 只声明已经确认存在的类与成员：反编译/运行日志里看到过再写。
 *       必需成员用 {@code method()}/{@code field()}，可能被裁掉的用
 *       {@code optionalMethod()}/{@code optionalField()}；</li>
 *   <li>{@code capability(Capability.X, () -> ...)} 给每个支持的能力挂工厂。工厂里可以
 *       用 {@code Ref} 的 {@link Binding}，但不要在里面写死类名 —— 类名只出现在 Ref 里；</li>
 *   <li>最后把新档案加到 {@link #all()} 的最前面。探测按列表顺序短路，
 *       新版本在前才不会落到旧档案上。</li>
 * </ol>
 *
 * <h3>类名从哪来</h3>
 * <p>相册把同名类在 4.3.x 里从 {@code com.miui.gallery.cloud} 挪到了
 * {@code com.miui.gallery.data.remote}/{@code base.*}，签名逐字不变。调用方
 * （{@code CloudItemReader}/{@code GalleryHooks}/{@code HostSyncState}）因此只写"新版类名"，
 * 实际用哪个类由当前命中的档案决定：{@link #resolveClass} 先按类名末段在当前档案的 Ref 里找，
 * 找不到再用写死的类名 —— 手机上两者是同一个类，行为与改造前逐字一致。
 *
 * <p>两个档案目前都只声明 Ref、不挂能力工厂，所以 {@link ProfileRegistry#report()} 会把
 * 全部能力报成 {@code unavailable(…: no-factory)}。这是有意的 —— 宁可在诊断里显示"没接"，
 * 也不要写一个猜出来的成员让版本识别失真。
 */
public final class Profiles {

    // ------------------------------------------------------------ 新版（4.3.1.6，手机）

    /** 4.3.1.6 实测存在的同步项类（见 {@code CloudItemReader}）。 */
    private static final String CLS_ITEM_2026 = "com.miui.gallery.data.remote.RequestCloudItem";
    private static final String CLS_ITEM_BASE_2026 =
            "com.miui.gallery.data.remote.RequestItemBase";
    private static final String CLS_SYNC_STATE_INFO_2026 =
            "com.miui.gallery.base.syncstate.SyncStateInfo";
    private static final String CLS_SYNC_STATE_MANAGER_2026 =
            "com.miui.gallery.base.syncstate.SyncStateManager";
    private static final String CLS_SYNC_TYPE_2026 = "com.miui.gallery.base.syncresult.SyncType";
    private static final String CLS_SYNC_REQUEST_2026 =
            "com.miui.gallery.base.syncresult.SyncRequest";
    private static final String CLS_SYNC_UTIL_2026 = "com.miui.gallery.util.SyncUtil";
    private static final String CLS_UPLOAD_STATUS_2026 =
            "com.miui.gallery.util.deviceprovider.UploadStatusController";

    // ------------------------------------------------------------ 旧版（3.6.2.10，平板）

    /** 3.6.2.10 实测存在的同步项类：与新版的成员签名逐字相同，只是包名不同。 */
    private static final String CLS_ITEM_2024 = "com.miui.gallery.cloud.RequestCloudItem";
    private static final String CLS_ITEM_BASE_2024 = "com.miui.gallery.cloud.RequestItemBase";
    private static final String CLS_SYNC_STATE_INFO_2024 =
            "com.miui.gallery.cloud.syncstate.SyncStateInfo";
    private static final String CLS_SYNC_STATE_MANAGER_2024 =
            "com.miui.gallery.cloud.syncstate.SyncStateManager";
    private static final String CLS_SYNC_TYPE_2024 = "com.miui.gallery.cloud.base.SyncType";
    private static final String CLS_SYNC_REQUEST_2024 = "com.miui.gallery.cloud.base.SyncRequest";
    private static final String CLS_SYNC_UTIL_2024 = "com.miui.gallery.util.SyncUtil";
    private static final String CLS_UPLOAD_STATUS_2024 =
            "com.miui.gallery.util.deviceprovider.UploadStatusController";

    /** 云表行对象：新旧版本都在这个包下，没有挪过位置。 */
    private static final String CLS_DB_IMAGE = "com.miui.gallery.data.DBImage";

    /**
     * 2026 版档案（4.3.x，手机）。
     *
     * <p>sentinel 保留老代码一直在用的云表契约类，再补一个新版独有的同步项类：
     * 契约类在新旧版本里同名，单独用它判别不了版本，而
     * {@code data.remote.RequestCloudItem} 只在 4.3.x 存在。
     *
     * <p>{@code dbCloud} 是"同步项 → 云表行"的入口字段（{@code CloudItemReader} 也是反射读它），
     * {@code priority} 声明在父类 {@code RequestItemBase} 上、由 {@code getField} 沿父类查到。
     */
    public static final Profile G2026 = Profile.named("G2026")
            .sentinel("com.miui.gallery.provider.GalleryContract$Cloud")
            .sentinel(CLS_ITEM_2026)
            .ref(Ref.cls(CLS_ITEM_2026)
                    .field("dbCloud"))
            .ref(Ref.cls(CLS_ITEM_BASE_2026)
                    .field("priority").type(int.class))
            .ref(Ref.cls(CLS_DB_IMAGE)
                    .method("getId").returns(String.class)
                    .method("getLocalFile").returns(String.class)
                    .method("getSize").returns(long.class)
                    .method("isVideoType").returns(boolean.class)
                    .method("getMixedDateTime").returns(long.class)
                    .method("getSha1").returns(String.class))
            .ref(Ref.cls(CLS_SYNC_STATE_INFO_2026)
                    .method("getDirtyCount").returns(int[].class))
            .ref(Ref.cls(CLS_SYNC_STATE_MANAGER_2026)
                    .method("getInstance")
                    .method("triggerMediaChanged").returns(void.class))
            .ref(Ref.cls(CLS_SYNC_TYPE_2026)
                    .field("POWER_FORCE"))
            .ref(Ref.cls(CLS_SYNC_REQUEST_2026))
            .ref(Ref.cls(CLS_SYNC_UTIL_2026))
            .ref(Ref.cls(CLS_UPLOAD_STATUS_2026)
                    .method("getInstance")
                    .method("startUpload").returns(void.class)
                    .method("endUpload").returns(void.class))
            .build();

    /**
     * 2024 版档案（3.6.2.10，平板）。
     *
     * <p>成员签名与 4.3.x 逐字一致，只有包名不同：同步项在 {@code com.miui.gallery.cloud}，
     * 同步状态在 {@code cloud.syncstate}，同步请求在 {@code cloud.base}。
     *
     * <p>两处签名无法在这里声明：{@code SyncRequest(SyncType, long, boolean, boolean)}
     * 与 {@code SyncUtil.requestSync(Context, SyncRequest)} —— {@code Ref.ctor()/method()} 只收
     * {@code java.lang.Class}，而这两个参数是宿主自己的类，声明期（模块的类加载器看不到相册类）
     * 拿不到它们。所以这两条只校验类存在，成员由 {@code HostSyncState} 在运行期按签名去查；
     * 查不到时的降级与改造前完全一样（同步状态退化成不显示，上传不受影响）。
     */
    public static final Profile G2024 = Profile.named("G2024")
            .sentinel(CLS_ITEM_2024)
            .ref(Ref.cls(CLS_ITEM_2024)
                    .field("dbCloud"))
            .ref(Ref.cls(CLS_ITEM_BASE_2024)
                    .field("priority").type(int.class))
            .ref(Ref.cls(CLS_DB_IMAGE)
                    .method("getId").returns(String.class)
                    .method("getLocalFile").returns(String.class)
                    .method("getSize").returns(long.class)
                    .method("isVideoType").returns(boolean.class)
                    .method("getMixedDateTime").returns(long.class)
                    .method("getSha1").returns(String.class))
            .ref(Ref.cls(CLS_SYNC_STATE_INFO_2024)
                    .method("getDirtyCount").returns(int[].class))
            .ref(Ref.cls(CLS_SYNC_STATE_MANAGER_2024)
                    .method("getInstance")
                    .method("triggerMediaChanged").returns(void.class))
            .ref(Ref.cls(CLS_SYNC_TYPE_2024)
                    .field("POWER_FORCE"))
            .ref(Ref.cls(CLS_SYNC_REQUEST_2024))
            .ref(Ref.cls(CLS_SYNC_UTIL_2024))
            .ref(Ref.cls(CLS_UPLOAD_STATUS_2024)
                    .method("getInstance")
                    .method("startUpload").returns(void.class)
                    .method("endUpload").returns(void.class))
            .build();

    /** 进程级档案表：用宿主 ClassLoader 探测，只建一次（探测结果本来就该在进程内固定） */
    private static volatile ProfileRegistry sRegistry;
    private static volatile ClassLoader sRegistryLoader;

    private Profiles() {
    }

    /** 按"新版本优先"返回全部档案；新增版本记得加到这里。 */
    public static List<Profile> all() {
        return Collections.unmodifiableList(Arrays.asList(G2026, G2024));
    }

    /**
     * 用宿主 ClassLoader 建（或取回）进程级档案表。
     *
     * <p>不能用 {@link ProfileRegistry#defaults()}：那个用的是模块自己的加载器，而相册的类
     * 只有宿主加载器看得见，拿错了会永远 {@code profile=none}。
     */
    public static ProfileRegistry registry(ClassLoader hostLoader) {
        ClassLoader cl = loaderOrSelf(hostLoader);
        ProfileRegistry cached = sRegistry;
        if (cached != null && sRegistryLoader == cl) {
            return cached;
        }
        synchronized (Profiles.class) {
            if (sRegistry == null || sRegistryLoader != cl) {
                sRegistry = new ProfileRegistry(all(), cl);
                sRegistryLoader = cl;
            }
            return sRegistry;
        }
    }

    /** 当前命中的档案；没有命中返回 null。 */
    public static Profile profile(ClassLoader hostLoader) {
        return registry(hostLoader).profile();
    }

    /** 当前档案名，没命中时是 {@code none}；只给日志用。 */
    public static String profileName(ClassLoader hostLoader) {
        Profile profile = profile(hostLoader);
        return profile == null ? "none" : profile.name();
    }

    /**
     * 按类名末段在当前档案的 Ref 里找解析结果（找不到返回 null）。
     *
     * <p>调用方写的是"新版类名"，档案里那一条可能指向旧版的同名类 —— 这就是平板能识别
     * {@code com.miui.gallery.cloud.RequestCloudItem} 的原因。比对只看简单类名：两个版本的
     * 成员签名逐字一致，包名是唯一区别。
     */
    public static Binding resolveBinding(ClassLoader hostLoader, String hardcodedClassName) {
        if (hardcodedClassName == null || hardcodedClassName.trim().isEmpty()) {
            return null;
        }
        ClassLoader cl = loaderOrSelf(hostLoader);
        Profile profile = profile(cl);
        if (profile == null) {
            return null;
        }
        String role = simpleName(hardcodedClassName.trim());
        for (Ref ref : profile.refs()) {
            Binding binding = ref.resolve(cl);
            Class<?> clazz = binding.clazz();
            if (clazz != null && role.equals(clazz.getSimpleName())) {
                return binding;
            }
        }
        return null;
    }

    /**
     * 类查找：档案优先、写死类名回落。
     *
     * <p>回落保证手机上行为不变（档案把类名换成同名类时结果与以前逐字相同）；平板则因为
     * 档案声明了 {@code com.miui.gallery.cloud.*} 而拿到正确的类。都拿不到时返回 null，
     * 由调用点照旧打 {@code class_not_found}。
     */
    public static Class<?> resolveClass(ClassLoader hostLoader, String hardcodedClassName) {
        if (hardcodedClassName == null || hardcodedClassName.trim().isEmpty()) {
            return null;
        }
        String name = hardcodedClassName.trim();
        ClassLoader cl = loaderOrSelf(hostLoader);
        Binding binding = resolveBinding(cl, name);
        if (binding != null && binding.clazz() != null) {
            Class<?> fromProfile = binding.clazz();
            if (!fromProfile.getName().equals(name)) {
                // 只有"档案把类名换掉了"才值得记：手机上两者同名，日志与改造前一致
                Logger.d("event=profile_class profile=" + profileName(cl)
                        + " role=" + simpleName(name)
                        + " from=" + name + " to=" + fromProfile.getName());
            }
            return fromProfile;
        }
        try {
            return Class.forName(name, false, cl);
        } catch (ClassNotFoundException e) {
            return null;
        } catch (LinkageError e) {
            // 类文件在但依赖缺失：同样当作"这个版本没有"
            return null;
        } catch (SecurityException e) {
            return null;
        }
    }

    private static ClassLoader loaderOrSelf(ClassLoader hostLoader) {
        return hostLoader != null ? hostLoader : Profiles.class.getClassLoader();
    }

    /** 取类名末段（嵌套类取 {@code $} 之后那一段），与 {@code Class#getSimpleName()} 对齐。 */
    private static String simpleName(String className) {
        String simple = className.substring(className.lastIndexOf('.') + 1);
        int nested = simple.lastIndexOf('$');
        return nested >= 0 ? simple.substring(nested + 1) : simple;
    }
}
