package com.tongluosao.gallerysync.hook.gallery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * {@link Profile}/{@link ProfileRegistry} 的行为：sentinel 探测、首个命中与缓存、能力取用、
 * 报告文本（可用/不可用两种形态）。
 *
 * <p>同样只用测试内部假类：单测 JVM 里没有相册的类，所以 {@link ProfileRegistry#defaults()}
 * 必须一个档案都选不中 —— 这条也顺带验证了"探测失败不会抛异常"。
 */
public final class ProfileRegistryTest {

    /** 假 sentinel：能加载到就代表"这个相册版本装上了"。 */
    static class FakeCloud {
    }

    static class FakeCloudItem {
        public String getLocalFile() {
            return "/storage/emulated/0/DCIM/c.jpg";
        }

        public long getSize() {
            return 4096L;
        }
    }

    /** 一定不存在：用它验证 sentinel 缺一个就不命中。 */
    private static final String MISSING_SENTINEL = "com.example.gallery.NotInstalled";

    private static Ref itemRef() {
        return Ref.cls(FakeCloudItem.class.getName())
                .method("getLocalFile").returns(String.class)
                .method("getSize").returns(long.class);
    }

    private static Profile profile(String name, String sentinel) {
        return Profile.named(name).sentinel(sentinel).ref(itemRef()).build();
    }

    // ------------------------------------------------------------ 探测

    @Test
    public void probeRequiresEverySentinel() {
        assertTrue(profile("hit", FakeCloud.class.getName()).probe());
        assertTrue(profile("hit", FakeCloud.class.getName())
                .probe(ProfileRegistryTest.class.getClassLoader()));

        Profile partial = Profile.named("partial")
                .sentinel(FakeCloud.class.getName())
                .sentinel(MISSING_SENTINEL)
                .build();
        assertFalse(partial.probe());
    }

    @Test
    public void selectReturnsNullWhenNoSentinelMatches() {
        ProfileRegistry registry = new ProfileRegistry(Collections.singletonList(
                profile("ghost", MISSING_SENTINEL)));

        assertNull(registry.select());
        assertNull(registry.profile());
        // 没有版本就没有能力可给
        assertNull(registry.get(Capability.SYNC_ITEMS));
    }

    @Test
    public void selectPicksFirstMatchingProfileAndCachesIt() {
        Profile first = Profile.named("first")
                .sentinel(FakeCloud.class.getName())
                .capability(Capability.SYNC_ITEMS, () -> "first-value")
                .build();
        Profile second = Profile.named("second")
                .sentinel(FakeCloud.class.getName())
                .capability(Capability.SYNC_ITEMS, () -> "second-value")
                .build();
        ProfileRegistry registry = new ProfileRegistry(Arrays.asList(first, second));

        assertSame(first, registry.select());
        // 第二次调用走缓存：不能再探测出第二个档案
        assertSame(first, registry.select());
        assertEquals("first-value", registry.get(Capability.SYNC_ITEMS));
    }

    // ------------------------------------------------------------ 能力取用

    @Test
    public void getReturnsNullForCapabilitiesTheProfileDoesNotDeclare() {
        ProfileRegistry registry = new ProfileRegistry(Collections.singletonList(
                profile("basic", FakeCloud.class.getName())));

        assertNull(registry.get(Capability.SLIM_ITEM));
        assertNull(registry.get(Capability.TRASH_QUEUE));
        // 没声明 ≠ 声明了但工厂失败：报告里要能区分
        assertEquals("no-factory", registry.select().capabilityFailure(Capability.SLIM_ITEM));
    }

    @Test
    public void getReturnsNullWhenFactoryYieldsNull() {
        ProfileRegistry registry = new ProfileRegistry(Collections.singletonList(
                Profile.named("nullish")
                        .sentinel(FakeCloud.class.getName())
                        .capability(Capability.SLIM_ITEM, () -> null)
                        .build()));

        assertNull(registry.get(Capability.SLIM_ITEM));
        assertEquals("factory-null", registry.select().capabilityFailure(Capability.SLIM_ITEM));
    }

    @Test
    public void getReturnsNullWhenFactoryThrows() {
        ProfileRegistry registry = new ProfileRegistry(Collections.singletonList(
                Profile.named("broken")
                        .sentinel(FakeCloud.class.getName())
                        .capability(Capability.VIDEO_ONLINE, () -> {
                            throw new IllegalStateException("host class gone");
                        })
                        .build()));

        assertNull(registry.get(Capability.VIDEO_ONLINE));
        String failure = registry.select().capabilityFailure(Capability.VIDEO_ONLINE);
        assertTrue(failure, failure.startsWith("factory-failed:"));
        assertTrue(failure, failure.contains("IllegalStateException"));
    }

    // ------------------------------------------------------------ 报告文本

    @Test
    public void reportMarksDeclaredCapabilitiesOkAndMissingOnesUnavailable() {
        ProfileRegistry registry = new ProfileRegistry(Collections.singletonList(
                Profile.named("G9999")
                        .sentinel(FakeCloud.class.getName())
                        .ref(itemRef())
                        .capability(Capability.SYNC_ITEMS, () -> "ok")
                        .build()));

        String report = registry.report();
        assertTrue(report, report.startsWith("profile=G9999 "));
        assertTrue(report, report.contains("sync_items=ok"));
        assertTrue(report, report.contains("slim_item=unavailable(SLIM_ITEM: no-factory)"));
        assertTrue(report, report.contains("trash_queue=unavailable(TRASH_QUEUE: no-factory)"));
        // 报告必须是单行：换行会破坏日志解析
        assertFalse(report, report.contains("\n"));
        assertFalse(report, report.contains("SlimController"));
    }

    @Test
    public void reportShowsRecordedFactoryFailure() {
        ProfileRegistry registry = new ProfileRegistry(Collections.singletonList(
                Profile.named("G9999")
                        .sentinel(FakeCloud.class.getName())
                        .capability(Capability.SLIM_ITEM, () -> null)
                        .build()));

        registry.get(Capability.SLIM_ITEM);
        assertTrue(registry.report(),
                registry.report().contains("slim_item=unavailable(SLIM_ITEM: factory-null)"));
    }

    @Test
    public void reportSaysNoneWhenNothingMatches() {
        ProfileRegistry registry = new ProfileRegistry(Collections.singletonList(
                profile("ghost", MISSING_SENTINEL)));

        String report = registry.report();
        assertTrue(report, report.startsWith("profile=none "));
        assertTrue(report, report.contains("sync_items=unavailable(SYNC_ITEMS: no-profile)"));
    }

    // ------------------------------------------------------------ 档案校验

    @Test
    public void validateAggregatesMissingMembersIntoProfile() {
        Profile incomplete = Profile.named("broken")
                .sentinel(FakeCloud.class.getName())
                .ref(Ref.cls(FakeCloudItem.class.getName())
                        .method("getLocalFile").returns(String.class)
                        .method("getSha1").returns(String.class))
                .build();

        List<Binding> bindings = incomplete.validate();
        assertEquals(1, bindings.size());
        assertFalse(incomplete.isComplete());
        assertEquals(1, incomplete.missing().size());
        assertTrue(incomplete.missing().get(0), incomplete.missing().get(0).contains("getSha1"));

        // 解析结果要缓存：再调一次不能多出一条
        assertSame(bindings, incomplete.validate());
    }

    @Test
    public void completeProfileHasNoMissingMembers() {
        Profile complete = Profile.named("good")
                .sentinel(FakeCloud.class.getName())
                .ref(itemRef())
                .build();

        assertTrue(complete.isComplete());
        assertTrue(complete.missing().isEmpty());
        assertEquals(1, complete.refs().size());
        assertEquals(1, complete.sentinels().size());
    }

    // ------------------------------------------------------------ 出厂档案

    @Test
    public void shippedProfilesAreRegisteredButDoNotMatchOffDevice() {
        // 单测 JVM 里没有相册的类：出厂档案必须一个都选不中，而不是抛异常
        assertNull(ProfileRegistry.defaults().select());
        assertTrue(ProfileRegistry.defaults().report().startsWith("profile=none "));

        // 档案表不能是空的：新增版本时忘了登记会在这里暴露
        assertFalse(Profiles.all().isEmpty());
        assertEquals("G2026", Profiles.G2026.name());
        assertTrue(Profiles.G2026.sentinels().get(0).contains("GalleryContract$Cloud"));
    }
}
