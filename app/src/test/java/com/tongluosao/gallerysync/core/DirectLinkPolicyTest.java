package com.tongluosao.gallerysync.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.net.SocketException;
import java.net.SocketTimeoutException;

/**
 * 302 直链的取舍规则。
 *
 * <p>这组断言钉的是"小对象也要走直链"这条改动的边界：
 * <ul>
 *   <li>大小<b>不再</b>决定"配不配"直链 —— 1KB 与 600MB 只要缓存活着都该走；</li>
 *   <li>大小只决定缓存活多久：越小越短，越大越长；</li>
 *   <li>0 字节对象不试直链：它没有体可省，而"空响应"正是直链失效的判据，
 *       拿空对象去试会把一条好缓存误判成坏缓存；</li>
 *   <li>缓存过期时刻是<b>开区间</b>：{@code now == expiresAt} 已经算过期；</li>
 *   <li>失败原因必须落在稳定标签上（{@code no_302} / {@code empty_response} /
 *       {@code http_403} / {@code timeout} …），日志统计才能按它分类。</li>
 * </ul>
 */
public class DirectLinkPolicyTest {

    private static DirectLinkPolicy.CachedEntry live(long now) {
        return new DirectLinkPolicy.CachedEntry("https://storage.example/a.jpg?sig=1",
                now + 60_000L);
    }

    // ------------------------------------------------------------ shouldTryDirect

    @Test
    public void zeroByteObjectNeverUsesDirectLink() {
        long now = 1_000L;
        assertFalse("空对象没有体可省，探它只会把好缓存误判成空响应",
                DirectLinkPolicy.shouldTryDirect(0L, live(now), now));
        assertEquals("空对象不缓存直链", 0L, DirectLinkPolicy.ttlFor(0L));
        assertFalse("空对象连探都不该探",
                DirectLinkPolicy.shouldProbe(0L, DirectLinkPolicy.DirectoryMode.INLINE));
    }

    @Test
    public void smallObjectUsesDirectLinkJustLikeLargeOne() {
        long now = 1_000L;
        // 1KB 的截图：缓存活着就走，与 600MB 的视频同一条判据
        assertTrue(DirectLinkPolicy.shouldTryDirect(1024L, live(now), now));
        assertTrue(DirectLinkPolicy.shouldTryDirect(10L * 1024L * 1024L, live(now), now));
        assertTrue(DirectLinkPolicy.shouldTryDirect(600L * 1024L * 1024L, live(now), now));
        // 大小未知（-1）也照走：直链本身不依赖我们知不知道长度
        assertTrue(DirectLinkPolicy.shouldTryDirect(-1L, live(now), now));
    }

    @Test
    public void missingOrExpiredCacheMeansStandardPath() {
        long now = 1_000L;
        assertFalse("没有缓存就不能假装有直链",
                DirectLinkPolicy.shouldTryDirect(1024L, null, now));
        assertFalse("过期后必须退回主域",
                DirectLinkPolicy.shouldTryDirect(1024L,
                        new DirectLinkPolicy.CachedEntry("https://storage.example/a", now), now));
        assertFalse("过期一毫秒就过期",
                DirectLinkPolicy.shouldTryDirect(1024L,
                        new DirectLinkPolicy.CachedEntry("https://storage.example/a", now - 1L),
                        now));
    }

    @Test
    public void emptyUrlIsNotACacheHit() {
        long now = 1_000L;
        assertFalse(DirectLinkPolicy.shouldTryDirect(1024L,
                new DirectLinkPolicy.CachedEntry("", now + 60_000L), now));
        assertFalse(DirectLinkPolicy.shouldTryDirect(1024L,
                new DirectLinkPolicy.CachedEntry(null, now + 60_000L), now));
    }

    // ------------------------------------------------------------ ttlFor

    @Test
    public void ttlGrowsWithObjectSize() {
        assertEquals(DirectLinkPolicy.TINY_TTL_MS, DirectLinkPolicy.ttlFor(1L));
        assertEquals(DirectLinkPolicy.TINY_TTL_MS, DirectLinkPolicy.ttlFor(1024L));
        assertEquals("1KB 之后跳到截图档", DirectLinkPolicy.SMALL_TTL_MS,
                DirectLinkPolicy.ttlFor(1025L));
        assertEquals(DirectLinkPolicy.SMALL_TTL_MS, DirectLinkPolicy.ttlFor(1024L * 1024L));
        assertEquals("10MB 仍是默认档", DirectLinkPolicy.DEFAULT_TTL_MS,
                DirectLinkPolicy.ttlFor(10L * 1024L * 1024L));
        assertEquals(DirectLinkPolicy.LARGE_TTL_MS,
                DirectLinkPolicy.ttlFor(10L * 1024L * 1024L + 1L));
        assertEquals(DirectLinkPolicy.LARGE_TTL_MS,
                DirectLinkPolicy.ttlFor(500L * 1024L * 1024L));
        assertEquals("超过 500MB 给最长的一档", DirectLinkPolicy.HUGE_TTL_MS,
                DirectLinkPolicy.ttlFor(500L * 1024L * 1024L + 1L));
    }

    @Test
    public void unknownSizeKeepsTheOldDefault() {
        // 改造前 DIRECT_URL_TTL_MS 就是 3 分钟，未知大小的调用方（老接口、Range 播放）行为不变
        assertEquals(3L * 60_000L, DirectLinkPolicy.ttlFor(-1L));
        assertEquals(DirectLinkPolicy.DEFAULT_TTL_MS, DirectLinkPolicy.ttlFor(-1L));
    }

    // ------------------------------------------------------------ 目录学习 / 探测

    @Test
    public void probeOnlyWhenTheDirectoryIsKnownToBeProxied() {
        assertFalse("还没学过就别乱探",
                DirectLinkPolicy.shouldProbe(1024L, DirectLinkPolicy.DirectoryMode.UNKNOWN));
        assertTrue("整份 GET 被主域代理：值得探",
                DirectLinkPolicy.shouldProbe(1024L, DirectLinkPolicy.DirectoryMode.INLINE));
        assertFalse("整份 GET 已经 302：再探是纯浪费",
                DirectLinkPolicy.shouldProbe(1024L, DirectLinkPolicy.DirectoryMode.REDIRECTS));
        assertFalse("探过了没有直链：别再白探",
                DirectLinkPolicy.shouldProbe(1024L, DirectLinkPolicy.DirectoryMode.NO_DIRECT));
    }

    @Test
    public void directoryModeFollowsWhatTheServerActuallyDid() {
        assertEquals(DirectLinkPolicy.DirectoryMode.REDIRECTS,
                DirectLinkPolicy.modeAfterTransfer(true));
        assertEquals("整份 GET 由主域自己给体", DirectLinkPolicy.DirectoryMode.INLINE,
                DirectLinkPolicy.modeAfterTransfer(false));
        // 探到 302 只证明"带 Range 的请求给直链"，不证明整份 GET 会跳；
        // 而"整份 GET 被代理"才正是需要探测的原因，所以留在 INLINE 继续探。
        assertEquals(DirectLinkPolicy.DirectoryMode.INLINE,
                DirectLinkPolicy.modeAfterProbe(true));
        assertEquals(DirectLinkPolicy.DirectoryMode.NO_DIRECT,
                DirectLinkPolicy.modeAfterProbe(false));
        assertEquals(10L * 60_000L, DirectLinkPolicy.DIRECTORY_HINT_TTL_MS);
    }

    // ------------------------------------------------------------ fallbackReason

    @Test
    public void noRedirectFallsBackWithItsOwnReason() {
        assertEquals("服务器根本没给 302（自己代理整份体）", "no_302",
                DirectLinkPolicy.fallbackReason(false, false, 200, null));
    }

    @Test
    public void emptyResponseFallsBackWithItsOwnReason() {
        assertEquals("直链回了成功码却没有体", "empty_response",
                DirectLinkPolicy.fallbackReason(true, true, 200, null));
        // 旧路径是靠异常文本 "no body" 认出来的，两条路必须给同一个标签
        assertEquals("empty_response", DirectLinkPolicy.fallbackReason(true, false, 0,
                new IOException("direct response had no body")));
    }

    @Test
    public void statusAndFailureWinOverTheGenericLabels() {
        assertEquals("http_403", DirectLinkPolicy.fallbackReason(true, false, 403, null));
        assertEquals("超时优先于 no_302", "timeout",
                DirectLinkPolicy.fallbackReason(false, false, 0, new SocketTimeoutException()));
        assertEquals("看门狗关连接", "socket_closed",
                DirectLinkPolicy.fallbackReason(false, false, 0, new SocketException("Socket closed")));
        assertEquals("socket_error",
                DirectLinkPolicy.fallbackReason(false, false, 0,
                        new SocketException("Connection reset")));
        assertEquals("unknown", DirectLinkPolicy.fallbackReason(true, false, 0, null));
    }

    @Test
    public void httpStatusExceptionKeepsItsCode() {
        // 同包里的真实异常类型：403 必须落成 http_403，而不是类名
        WebDavClient.HttpStatusException failure =
                new WebDavClient.HttpStatusException(403, "GET failed code=403");
        assertEquals("http_403", DirectLinkPolicy.failureReason(failure));
        assertEquals("http_403", DirectLinkPolicy.fallbackReason(true, false, 0, failure));
        assertEquals("http_404", DirectLinkPolicy.fallbackReason(true, false, 0,
                new WebDavClient.HttpStatusException(404, "GET failed code=404")));
    }

    @Test
    public void fallbackReasonNeverReturnsNull() {
        assertTrue(DirectLinkPolicy.fallbackReason(false, false, 0, null) != null);
        assertTrue(DirectLinkPolicy.failureReason(null) != null);
        assertTrue(DirectLinkPolicy.fallbackReason(false, false, 0,
                new IllegalStateException()) != null);
    }

    /** 反射取 code 的那条路要能在"字段不在"时安静退化成类名，而不是抛出来。 */
    @Test
    public void plainExceptionDegradesToItsSimpleName() {
        assertEquals("ioexception", DirectLinkPolicy.failureReason(new IOException("boom")));
        assertEquals("illegalstateexception",
                DirectLinkPolicy.failureReason(new IllegalStateException("boom")));
    }
}
