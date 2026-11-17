package com.tongluosao.gallerysync.hook.gallery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * {@link Profiles} 出厂档案的契约：顺序（新→旧）、两个版本各自的 sentinel 与 Ref 类名、
 * 以及"单测 JVM 里一个都不命中、也不抛异常"。
 *
 * <p>为什么要在没有相册的 JVM 里测：这两个档案是模块识别相册版本的唯一依据，
 * 平板（旧版 3.6.2.10）之所以能跑起来，全靠 {@link Profiles#G2024} 里那些
 * {@code com.miui.gallery.cloud.*} 的类名；写错一个字母，平板就退回
 * {@code reader_binding_missing}。
 */
public final class ProfilesTest {

    /** G2024（旧版 3.6.2.10）必须覆盖的类；顺序与档案声明一致。 */
    private static final List<String> G2024_CLASSES = Arrays.asList(
            "com.miui.gallery.cloud.RequestCloudItem",
            "com.miui.gallery.cloud.RequestItemBase",
            "com.miui.gallery.data.DBImage",
            "com.miui.gallery.cloud.syncstate.SyncStateInfo",
            "com.miui.gallery.cloud.syncstate.SyncStateManager",
            "com.miui.gallery.cloud.base.SyncType",
            "com.miui.gallery.cloud.base.SyncRequest",
            "com.miui.gallery.util.SyncUtil",
            "com.miui.gallery.util.deviceprovider.UploadStatusController");

    /** G2026（新版 4.3.x）必须覆盖的类；顺序与档案声明一致。 */
    private static final List<String> G2026_CLASSES = Arrays.asList(
            "com.miui.gallery.data.remote.RequestCloudItem",
            "com.miui.gallery.data.remote.RequestItemBase",
            "com.miui.gallery.data.DBImage",
            "com.miui.gallery.base.syncstate.SyncStateInfo",
            "com.miui.gallery.base.syncstate.SyncStateManager",
            "com.miui.gallery.base.syncresult.SyncType",
            "com.miui.gallery.base.syncresult.SyncRequest",
            "com.miui.gallery.util.SyncUtil",
            "com.miui.gallery.util.deviceprovider.UploadStatusController");

    private static ClassLoader loader() {
        return ProfilesTest.class.getClassLoader();
    }

    /** 每个 Ref 声明的主要类名（不含 alt）；一个 Ref 只用主名，方便断言"没有靠猜"。 */
    private static List<String> refClassNames(Profile profile) {
        List<String> names = new ArrayList<>(profile.refs().size());
        for (Ref ref : profile.refs()) {
            names.add(ref.classNames().get(0));
        }
        return names;
    }

    private static Set<String> asSet(List<String> names) {
        return new LinkedHashSet<>(names);
    }

    // ------------------------------------------------------------ 顺序与登记

    @Test
    public void allListsNewVersionFirst() {
        List<Profile> profiles = Profiles.all();
        assertEquals(2, profiles.size());
        // 探测按列表顺序短路：G2026 必须在前面，否则新版手机会落到旧档案上
        assertSame(Profiles.G2026, profiles.get(0));
        assertSame(Profiles.G2024, profiles.get(1));
        assertEquals("G2026", Profiles.G2026.name());
        assertEquals("G2024", Profiles.G2024.name());
    }

    // ------------------------------------------------------------ sentinel

    @Test
    public void eachProfileUsesItsOwnRequestCloudItemAsSentinel() {
        // 新版独有：data.remote 下的同步项类
        assertTrue(Profiles.G2026.sentinels().contains("com.miui.gallery.data.remote.RequestCloudItem"));
        // 老代码一直在用的云表契约类：新旧版本同名，留着只是为了不改变手机上的识别路径
        assertTrue(Profiles.G2026.sentinels().contains("com.miui.gallery.provider.GalleryContract$Cloud"));
        // 旧版独有：cloud 下的同步项类
        assertEquals(Arrays.asList("com.miui.gallery.cloud.RequestCloudItem"),
                Profiles.G2024.sentinels());
        // 两个版本的 sentinel 不能互相包含，否则一次误命中就把版本认错
        assertFalse(Profiles.G2024.sentinels().contains("com.miui.gallery.data.remote.RequestCloudItem"));
        assertFalse(Profiles.G2026.sentinels().contains("com.miui.gallery.cloud.RequestCloudItem"));
    }

    // ------------------------------------------------------------ Ref

    @Test
    public void oldProfileCoversEveryClassTheSyncChainNeeds() {
        List<String> declared = refClassNames(Profiles.G2024);
        assertTrue("G2024 至少要声明一个 Ref", Profiles.G2024.refs().size() > 0);
        assertEquals(G2024_CLASSES.size(), Profiles.G2024.refs().size());
        assertEquals(asSet(G2024_CLASSES), asSet(declared));
        // 顺序也要对：先同步项，再云表行，最后同步状态/请求
        assertEquals(G2024_CLASSES, declared);
    }

    @Test
    public void newProfileCoversEveryClassTheSyncChainNeeds() {
        List<String> declared = refClassNames(Profiles.G2026);
        assertEquals(G2026_CLASSES.size(), Profiles.G2026.refs().size());
        assertEquals(G2026_CLASSES, declared);
        // 旧版的类不能出现在新档案里：那会让新版手机也去加载 cloud 包
        for (String name : declared) {
            assertFalse(name, name.startsWith("com.miui.gallery.cloud."));
        }
    }

    // ------------------------------------------------------------ 设备外行为

    @Test
    public void neitherProfileMatchesWithoutTheGalleryInstalled() {
        // 单测 JVM 里没有相册的类：两个档案都必须不命中，且不能抛异常
        assertFalse(Profiles.G2026.probe());
        assertFalse(Profiles.G2024.probe());
        assertFalse(Profiles.G2026.probe(loader()));
        assertFalse(Profiles.G2024.probe(loader()));

        assertNull(Profiles.profile(loader()));
        assertEquals("none", Profiles.profileName(loader()));
        assertNull(Profiles.resolveBinding(loader(), "com.miui.gallery.data.remote.RequestCloudItem"));
        assertNull(Profiles.resolveClass(loader(), "com.miui.gallery.data.remote.RequestCloudItem"));
    }

    @Test
    public void validateReportsMissingClassesInsteadOfThrowing() {
        List<Binding> bindings = Profiles.G2024.validate(loader());
        assertEquals(Profiles.G2024.refs().size(), bindings.size());
        for (Binding binding : bindings) {
            // 设备外每个 Ref 都只该有"缺少类 …"一条，不该冒出成员层面的噪音
            assertNull(binding.clazz());
            assertFalse(binding.isComplete());
            assertEquals(1, binding.missing().size());
            assertTrue(binding.missing().get(0), binding.missing().get(0).startsWith("缺少类 "));
        }
        assertEquals(bindings, Profiles.G2024.validate(loader()));
    }

    @Test
    public void registryReportSaysNoneOffDevice() {
        // 启动自检那一行在单测环境里必须是 profile=none，而不是抛异常或错认版本
        String report = Profiles.registry(loader()).report();
        assertTrue(report, report.startsWith("profile=none "));
        assertFalse(report, report.contains("\n"));
        // 档案表本身不能是空的：新增版本时忘了登记会在这里暴露
        assertFalse(Profiles.all().isEmpty());
    }

    // ------------------------------------------------------------ 兜底入参

    @Test
    public void resolveClassToleratesEmptyNames() {
        assertNull(Profiles.resolveClass(loader(), null));
        assertNull(Profiles.resolveClass(loader(), "  "));
        assertNull(Profiles.resolveBinding(loader(), null));
    }
}
