package com.tongluosao.gallerysync.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link WebDavTimeoutPolicy} 的纯逻辑：按方法名选超时、超时后只重试一次。
 *
 * <p>回归点（2026-11 真机）：PROPFIND 以前按"流式"规则算，请求体只有 136 字节，
 * 于是读超时恰好是 {@code READ_TIMEOUT_BASE}（30 秒），对冷启动就要 6.8 秒的
 * OpenList+S3 后端太紧，上传卡在 upload_begin 之后。
 */
public class WebDavTimeoutPolicyTest {

    /** 与 WebDavClient 顶部的流式常量保持一致（只在本测试里当输入用） */
    private static final int STREAM_BASE = 30_000;
    private static final int PER_BYTE = 64;
    private static final int STREAM_MAX = 1_800_000;

    private static int timeout(String method, long bodyLength) {
        return WebDavTimeoutPolicy.readTimeoutMs(method, bodyLength, STREAM_BASE, PER_BYTE,
                STREAM_MAX);
    }

    @Test
    public void metadataRequestsGetTheWideFixedTimeout() {
        for (String method : new String[]{"PROPFIND", "PROPPATCH", "HEAD", "OPTIONS", "MKCOL",
                "DELETE", "MOVE", "COPY", "LOCK", "UNLOCK"}) {
            assertTrue(method, WebDavTimeoutPolicy.isMetadataMethod(method));
            // 元数据请求的请求体只有几百字节，超时必须与体量无关
            assertEquals(method, WebDavTimeoutPolicy.META_READ_TIMEOUT_MS, timeout(method, 0L));
            assertEquals(method, WebDavTimeoutPolicy.META_READ_TIMEOUT_MS, timeout(method, 136L));
            assertEquals(method, WebDavTimeoutPolicy.META_READ_TIMEOUT_MS,
                    timeout(method, 8L * 1024L * 1024L));
        }
    }

    @Test
    public void metadataTimeoutIsAtLeastOneMinute() {
        assertTrue("元数据读超时必须 ≥ 60s",
                WebDavTimeoutPolicy.META_READ_TIMEOUT_MS >= 60_000);
    }

    @Test
    public void propfindWithTinyBodyNoLongerGetsTheThirtySecondBaseTimeout() {
        // 回归：以前 136 字节的 PROPFIND body 会算出 30_002ms，真机上就是它把上传卡死的
        assertEquals(90_000, timeout("PROPFIND", 136L));
        assertTrue(timeout("PROPFIND", 136L) > STREAM_BASE);
    }

    @Test
    public void streamingPutStillScalesWithBodyLength() {
        assertFalse(WebDavTimeoutPolicy.isMetadataMethod("PUT"));
        assertEquals(STREAM_BASE, timeout("PUT", 0L));
        assertEquals(STREAM_BASE + 2, timeout("PUT", 136L));
        assertEquals(STREAM_BASE + 64_000 / PER_BYTE, timeout("PUT", 64_000L));
        // 600MB 这种量级必须顶到上限，不能按字节无限上浮
        assertEquals(STREAM_MAX, timeout("PUT", 600L * 1024L * 1024L));
    }

    @Test
    public void unknownOrNullVerbsFallBackToStreamingRules() {
        assertFalse(WebDavTimeoutPolicy.isMetadataMethod(null));
        assertFalse(WebDavTimeoutPolicy.isMetadataMethod("GET"));
        assertFalse(WebDavTimeoutPolicy.isMetadataMethod("POST"));
        assertEquals(STREAM_BASE, timeout("GET", 0L));
        assertEquals(STREAM_BASE, timeout(null, 0L));
    }

    @Test
    public void methodNamesAreMatchedCaseInsensitively() {
        assertTrue(WebDavTimeoutPolicy.isMetadataMethod("propfind"));
        assertTrue(WebDavTimeoutPolicy.isMetadataMethod("Head"));
        assertEquals(WebDavTimeoutPolicy.META_READ_TIMEOUT_MS, timeout("propfind", 136L));
    }

    @Test
    public void timeoutIsRetriedExactlyOnceForMetadataRequests() {
        // 首次失败（已失败 1 次）→ 再试一次；第二次还超时（已失败 2 次）→ 放弃
        assertTrue(WebDavTimeoutPolicy.shouldRetryAfterTimeout("PROPFIND", 1));
        assertFalse(WebDavTimeoutPolicy.shouldRetryAfterTimeout("PROPFIND", 2));
        assertFalse(WebDavTimeoutPolicy.shouldRetryAfterTimeout("HEAD", 2));
        assertEquals(2, WebDavTimeoutPolicy.META_MAX_ATTEMPTS);
    }

    @Test
    public void streamingRequestsAreNeverRetriedOnTimeout() {
        // PUT 重试等于把整份上行再烧一遍，那笔账交给调用方的退避逻辑
        assertFalse(WebDavTimeoutPolicy.shouldRetryAfterTimeout("PUT", 1));
        assertFalse(WebDavTimeoutPolicy.shouldRetryAfterTimeout("GET", 1));
        assertFalse(WebDavTimeoutPolicy.shouldRetryAfterTimeout(null, 1));
    }
}
