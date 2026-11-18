package com.tongluosao.gallerysync.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 自动下载"远端在不在"的缓存：目录名单优先、逐条结论按 (路径, 长度) 记 5 分钟。
 *
 * <p>这里的每条判据都直接决定会不会少握一次手 / 会不会误判远端没有：
 * <ul>
 *   <li>名单里没有名字 = 确定不在（省掉一次 PROPFIND，也省掉一次注定 404 的 GET）；</li>
 *   <li>同名不同长度<b>不能</b>互相命中 —— 命中就会拿旧对象的结论套新对象；</li>
 *   <li>长度未知（{@code <= 0}）的结论一律不记，因为对不上任何一个具体对象；</li>
 *   <li>过期之后必须回到 UNKNOWN，绝不能继续用旧结论跳过下载。</li>
 * </ul>
 */
public class DownloadProbeCacheTest {

    private static final long TTL = 5L * 60L * 1000L;

    private static Map<String, Long> files(Object... pairs) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((String) pairs[i], ((Number) pairs[i + 1]).longValue());
        }
        return out;
    }

    @Test
    public void listingMarksPresentWithItsSize() {
        DownloadProbeCache cache = new DownloadProbeCache();
        cache.putListing("MiGallerySync/DCIM/Camera", files("IMG_1.jpg", 1024L));

        assertEquals(DownloadProbeCache.Presence.PRESENT,
                cache.lookup("MiGallerySync/DCIM/Camera/IMG_1.jpg", 1024L));
        assertEquals(1024L, cache.listedSize("MiGallerySync/DCIM/Camera/IMG_1.jpg"));
        assertEquals(1, cache.listingCount());
    }

    @Test
    public void completeListingSaysMissingWithoutAnyProbe() {
        DownloadProbeCache cache = new DownloadProbeCache();
        // list() 没被截断，所以"名字不在这份名单里"就是"远端确实没有"
        cache.putListing("MiGallerySync/DCIM/Camera", files("IMG_1.jpg", 1024L));

        assertEquals(DownloadProbeCache.Presence.MISSING,
                cache.lookup("MiGallerySync/DCIM/Camera/IMG_2.jpg", 2048L));
        // 没进过名单的目录不能给结论：那是"不知道"，不是"没有"
        assertEquals(DownloadProbeCache.Presence.UNKNOWN,
                cache.lookup("MiGallerySync/DCIM/Other/IMG_2.jpg", 2048L));
    }

    @Test
    public void listingBeatsStaleProbeForTheSamePath() {
        DownloadProbeCache cache = new DownloadProbeCache();
        cache.recordProbe("MiGallerySync/DCIM/Camera/IMG_1.jpg", 1024L, false, 0L);
        cache.putListing("MiGallerySync/DCIM/Camera", files("IMG_1.jpg", 1024L));

        // 名单是本轮的、探测结论可能来自几分钟前：同一轮里以名单为准
        assertEquals(DownloadProbeCache.Presence.PRESENT,
                cache.lookup("MiGallerySync/DCIM/Camera/IMG_1.jpg", 1024L, 1000L));
    }

    @Test
    public void clearingListingsDropsThatKnowledgeButKeepsProbeTtl() {
        DownloadProbeCache cache = new DownloadProbeCache();
        cache.putListing("MiGallerySync/DCIM/Camera", files("IMG_1.jpg", 1024L));
        cache.recordProbe("MiGallerySync/DCIM/Camera/IMG_9.jpg", 9L, false, 0L);

        cache.clearListings();

        assertEquals(0, cache.listingCount());
        assertEquals(DownloadProbeCache.Presence.UNKNOWN,
                cache.lookup("MiGallerySync/DCIM/Camera/IMG_1.jpg", 1024L, 1L));
        assertEquals(DownloadProbeCache.Presence.MISSING,
                cache.lookup("MiGallerySync/DCIM/Camera/IMG_9.jpg", 9L, 1L));
    }

    @Test
    public void probeResultExpiresAfterTtl() {
        DownloadProbeCache cache = new DownloadProbeCache(TTL);
        cache.recordProbe("MiGallerySync/DCIM/Camera/IMG_1.jpg", 1024L, false, 0L);

        assertEquals(DownloadProbeCache.Presence.MISSING,
                cache.lookup("MiGallerySync/DCIM/Camera/IMG_1.jpg", 1024L, TTL - 1L));
        // 到期那一刻就不能再用：远端可能已经被重新上传
        assertEquals(DownloadProbeCache.Presence.UNKNOWN,
                cache.lookup("MiGallerySync/DCIM/Camera/IMG_1.jpg", 1024L, TTL));
        assertEquals(0, cache.size());
    }

    @Test
    public void samePathDifferentSizeNeverSharesConclusion() {
        DownloadProbeCache cache = new DownloadProbeCache();
        cache.recordProbe("MiGallerySync/DCIM/Camera/IMG_1.jpg", 1024L, false, 0L);

        // 远端换成了同名的另一个版本：旧结论不能压住新对象
        assertEquals(DownloadProbeCache.Presence.UNKNOWN,
                cache.lookup("MiGallerySync/DCIM/Camera/IMG_1.jpg", 999L, 1L));
        assertEquals(1, cache.size());
        // 换成新长度之后就能命中了：两条结论各自独立
        cache.recordProbe("MiGallerySync/DCIM/Camera/IMG_1.jpg", 999L, true, 1L);
        assertEquals(2, cache.size());
        assertEquals(DownloadProbeCache.Presence.PRESENT,
                cache.lookup("MiGallerySync/DCIM/Camera/IMG_1.jpg", 999L, 2L));
    }

    @Test
    public void unknownLengthIsNeverRecordedAndNeverMatches() {
        DownloadProbeCache cache = new DownloadProbeCache();
        cache.recordProbe("MiGallerySync/DCIM/Camera/IMG_1.jpg", 0L, false, 0L);
        assertEquals(0, cache.size());

        cache.recordProbe("MiGallerySync/DCIM/Camera/IMG_1.jpg", 1024L, false, 0L);
        // 长度未知的调用方只能靠目录名单，不能命中带长度的结论
        assertEquals(DownloadProbeCache.Presence.UNKNOWN,
                cache.lookup("MiGallerySync/DCIM/Camera/IMG_1.jpg", 0L, 1L));
        assertEquals(DownloadProbeCache.Presence.MISSING,
                cache.lookup("MiGallerySync/DCIM/Camera/IMG_1.jpg", 1024L, 1L));
    }

    @Test
    public void forgetRemovesOnlyTheMatchingSize() {
        DownloadProbeCache cache = new DownloadProbeCache();
        cache.recordProbe("MiGallerySync/DCIM/Camera/IMG_1.jpg", 1024L, true, 0L);
        cache.recordProbe("MiGallerySync/DCIM/Camera/IMG_1.jpg", 2048L, true, 0L);

        cache.forget("MiGallerySync/DCIM/Camera/IMG_1.jpg", 1024L);

        assertEquals(DownloadProbeCache.Presence.UNKNOWN,
                cache.lookup("MiGallerySync/DCIM/Camera/IMG_1.jpg", 1024L, 1L));
        assertEquals(DownloadProbeCache.Presence.PRESENT,
                cache.lookup("MiGallerySync/DCIM/Camera/IMG_1.jpg", 2048L, 1L));
    }

    @Test
    public void statsLineCountsHitsAndResetsEachRound() {
        DownloadProbeCache cache = new DownloadProbeCache();
        cache.putListing("MiGallerySync/DCIM/Camera", files("IMG_1.jpg", 1024L));

        cache.probe("MiGallerySync/DCIM/Camera/IMG_1.jpg", 1024L);
        cache.probe("MiGallerySync/DCIM/Camera/IMG_2.jpg", 2048L);
        cache.probe("MiGallerySync/DCIM/Unknown/IMG_3.jpg", 4096L);

        assertEquals(2, cache.hitCount());
        assertEquals(1, cache.missCount());
        assertEquals("hit=2 miss=1", cache.takeStatsLine());
        // 取走即清零：日志每轮一行，不能累加成一整天的数字
        assertEquals("hit=0 miss=0", cache.takeStatsLine());
    }

    @Test
    public void pathNormalizationAcceptsLeadingAndWindowsSeparators() {
        DownloadProbeCache cache = new DownloadProbeCache();
        cache.putListing("/MiGallerySync/DCIM/Camera/", files("IMG_1.jpg", 1024L));

        assertEquals(DownloadProbeCache.Presence.PRESENT,
                cache.lookup("MiGallerySync\\DCIM\\Camera\\IMG_1.jpg", 1024L));
        assertEquals(DownloadProbeCache.Presence.PRESENT,
                cache.lookup("/MiGallerySync/DCIM/Camera/IMG_1.jpg", 1024L));
    }

    @Test
    public void listingWithoutServerSizeStillProvesPresence() {
        DownloadProbeCache cache = new DownloadProbeCache();
        Map<String, Long> listed = new HashMap<>();
        listed.put("IMG_1.jpg", -1L);
        cache.putListing("MiGallerySync/DCIM/Camera", listed);

        assertEquals(DownloadProbeCache.Presence.PRESENT,
                cache.lookup("MiGallerySync/DCIM/Camera/IMG_1.jpg", 4096L));
        // 长度没给就没法核对，调用方必须自己跳过"长度一致"这道判断
        assertEquals(-1L, cache.listedSize("MiGallerySync/DCIM/Camera/IMG_1.jpg"));
    }

    @Test
    public void clearDropsEverythingIncludingCounters() {
        DownloadProbeCache cache = new DownloadProbeCache();
        cache.putListing("MiGallerySync/DCIM/Camera", files("IMG_1.jpg", 1024L));
        cache.recordProbe("MiGallerySync/DCIM/Other/IMG_2.jpg", 2048L, true, 0L);
        cache.probe("MiGallerySync/DCIM/Camera/IMG_1.jpg", 1024L);

        cache.clear();

        assertEquals(0, cache.size());
        assertEquals(0, cache.listingCount());
        assertEquals(0, cache.hitCount());
        assertEquals(DownloadProbeCache.Presence.UNKNOWN,
                cache.lookup("MiGallerySync/DCIM/Camera/IMG_1.jpg", 1024L));
    }

    @Test
    public void entriesAreKeyedByPathAndSizeTogether() {
        assertFalse(DownloadProbeCache.key("a/b.jpg", 1L)
                .equals(DownloadProbeCache.key("a/b.jpg", 2L)));
        assertEquals(DownloadProbeCache.key("/a/b.jpg", 1L),
                DownloadProbeCache.key("a\\b.jpg", 1L));
        assertTrue(DownloadProbeCache.key("a/b.jpg", 1L).endsWith("|1"));
    }

    @Test
    public void splitRejectsPathsWithoutADirectory() {
        assertNull(DownloadProbeCache.split("IMG_1.jpg"));
        assertNull(DownloadProbeCache.split(""));
        assertNull(DownloadProbeCache.split(null));
        DownloadProbeCache.PathSplit split = DownloadProbeCache.split("MiGallerySync/DCIM/IMG_1.jpg");
        assertEquals("MiGallerySync/DCIM", split.dir);
        assertEquals("IMG_1.jpg", split.name);
        // 末尾斜杠会被规整掉：目录路径本身也走同一个归一化，免得"同名目录两个键"
        assertEquals("MiGallerySync/DCIM/Camera",
                DownloadProbeCache.normalizePath("/MiGallerySync/DCIM/Camera/"));
    }
}
