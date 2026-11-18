package com.tongluosao.gallerysync.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 同路径在飞登记表的边界：重复领取、双重释放、释放后再领取、空路径。
 *
 * <p>这些边界对应真实的两种事故：两个同 index 的 worker 共用一条连接（重复领取），
 * 以及"路径被谁提前清掉、任务永远卡在已占用上"（漏放/双重释放）。原实现是裸的
 * {@code HashSet.add/remove}，两种都看不出来；这里让它们变成可断言的返回值。
 */
public class InflightRegistryTest {

    @Test
    public void firstClaimSucceedsSecondIsRejected() {
        InflightRegistry registry = new InflightRegistry();
        assertTrue(registry.claim("/MiGallerySync/a.jpg"));
        assertFalse("同路径第二次登记必须被挡住", registry.claim("/MiGallerySync/a.jpg"));
        assertEquals(1, registry.size());
        assertTrue(registry.contains("/MiGallerySync/a.jpg"));
    }

    @Test
    public void doubleReleaseIsDetectable() {
        InflightRegistry registry = new InflightRegistry();
        assertTrue(registry.claim("/MiGallerySync/a.jpg"));
        assertTrue(registry.release("/MiGallerySync/a.jpg"));
        assertFalse("第二次释放说明有 bug（或停机清空）", registry.release("/MiGallerySync/a.jpg"));
        assertFalse(registry.contains("/MiGallerySync/a.jpg"));
        assertTrue(registry.isEmpty());
    }

    @Test
    public void releaseUnknownPathIsFalseAndKeepsOtherEntries() {
        InflightRegistry registry = new InflightRegistry();
        registry.claim("/a.jpg");
        assertFalse(registry.release("/b.jpg"));
        assertEquals(1, registry.size());
        assertTrue(registry.contains("/a.jpg"));
    }

    @Test
    public void pathCanBeClaimedAgainAfterRelease() {
        InflightRegistry registry = new InflightRegistry();
        registry.claim("/a.jpg");
        registry.release("/a.jpg");
        assertTrue(registry.claim("/a.jpg"));
        assertTrue(registry.contains("/a.jpg"));
    }

    @Test
    public void nullAndEmptyPathsAreNeverRegistered() {
        InflightRegistry registry = new InflightRegistry();
        assertFalse(registry.claim(null));
        assertFalse(registry.claim(""));
        assertFalse(registry.contains(null));
        assertFalse(registry.contains(""));
        assertFalse(registry.release(null));
        assertFalse(registry.release(""));
        assertTrue(registry.isEmpty());
    }

    @Test
    public void clearReportsCountAndEmpties() {
        InflightRegistry registry = new InflightRegistry();
        registry.claim("/a.jpg");
        registry.claim("/b.jpg");
        assertEquals(2, registry.clear());
        assertTrue(registry.isEmpty());
        assertEquals(0, registry.clear());
    }

    @Test
    public void snapshotIsIndependentAndPathsOrEmptyIsSafe() {
        InflightRegistry registry = new InflightRegistry();
        assertTrue(registry.pathsOrEmpty().isEmpty());
        registry.claim("/a.jpg");
        java.util.Set<String> snapshot = registry.snapshot();
        snapshot.clear();
        assertTrue("快照必须是副本", registry.contains("/a.jpg"));
        assertEquals(1, registry.pathsOrEmpty().size());
    }
}
