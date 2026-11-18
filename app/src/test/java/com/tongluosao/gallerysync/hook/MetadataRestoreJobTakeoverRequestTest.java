package com.tongluosao.gallerysync.hook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * takeover 请求的形状：根上只有 jobId/target/mode/dryRun/includeVideos，<b>没有 items</b>。
 *
 * <p>三条底线都用生产代码自己的 {@code parseRequest} 钉住：
 * <ul>
 *   <li>合法请求能过，且 mode/includeVideos 真的被读进 {@link MetadataRestoreJob} 的 Request；</li>
 *   <li>带了 items 的接管请求整单拒掉（{@code takeover_items_not_allowed}）—— 否则它会被当成
 *       老逐项请求，按 1..MAX_ITEMS 校验，最后一条都不执行；</li>
 *   <li>认不出的根 mode 整单拒掉（{@code invalid_request_mode}），绝不退化成逐项 restore。</li>
 * </ul>
 *
 * <p>与同目录其它请求测试一样：单测 JVM 里的 org.json 是 android.jar 的打桩实现，
 * 不可用时跳过（{@code MetadataRestoreJob} 的解析在设备上跑的是真实 org.json）。
 */
public class MetadataRestoreJobTakeoverRequestTest {

    private static final String TARGET =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    public void takeoverRequestShapeIsAccepted() throws Exception {
        Assume.assumeTrue("单测 JVM 里的 org.json 不可用（android.jar 打桩）", jsonUsable());
        File request = write("{\"jobId\":\"takeover-1763000000000\",\"target\":\"" + TARGET
                + "\",\"mode\":\"takeover\",\"dryRun\":false,\"includeVideos\":true}");
        Object parsed = invokeParseRequest(request);
        assertEquals(MetadataRestoreJob.TAKEOVER_MODE, read(parsed, "mode"));
        assertEquals(Boolean.TRUE, read(parsed, "includeVideos"));
        assertEquals(Boolean.FALSE, read(parsed, "dryRun"));
        assertEquals("takeover-1763000000000", read(parsed, "jobId"));
        assertEquals(0, ((java.util.List<?>) read(parsed, "items")).size());
    }

    @Test
    public void dryRunAndIncludeVideosDefaultWhenAbsent() throws Exception {
        Assume.assumeTrue("单测 JVM 里的 org.json 不可用（android.jar 打桩）", jsonUsable());
        File request = write("{\"jobId\":\"takeover-2\",\"target\":\"" + TARGET
                + "\",\"mode\":\"takeover\"}");
        Object parsed = invokeParseRequest(request);
        // dryRun 缺省 false（演练必须先显式要求），includeVideos 缺省 true（视频也算已有照片）
        assertEquals(Boolean.FALSE, read(parsed, "dryRun"));
        assertEquals(Boolean.TRUE, read(parsed, "includeVideos"));
    }

    @Test
    public void itemsAreRejectedForTakeover() throws Exception {
        Assume.assumeTrue("单测 JVM 里的 org.json 不可用（android.jar 打桩）", jsonUsable());
        File request = write("{\"jobId\":\"takeover-3\",\"target\":\"" + TARGET
                + "\",\"mode\":\"takeover\",\"items\":[{\"row\":\"1\",\"sha1\":\""
                + "0123456789abcdef0123456789abcdef01234567\",\"sha256\":\""
                + TARGET + "\",\"length\":1}]}");
        assertRejected(request, "takeover_items_not_allowed");
    }

    @Test
    public void unknownRootModeIsRejected() throws Exception {
        Assume.assumeTrue("单测 JVM 里的 org.json 不可用（android.jar 打桩）", jsonUsable());
        File request = write("{\"jobId\":\"takeover-4\",\"target\":\"" + TARGET
                + "\",\"mode\":\"takeovr\"}");
        assertRejected(request, "invalid_request_mode");
    }

    @Test
    public void nonBooleanIncludeVideosIsRejected() throws Exception {
        Assume.assumeTrue("单测 JVM 里的 org.json 不可用（android.jar 打桩）", jsonUsable());
        File request = write("{\"jobId\":\"takeover-5\",\"target\":\"" + TARGET
                + "\",\"mode\":\"takeover\",\"includeVideos\":\"yes\"}");
        assertRejected(request, "invalid_include_videos");
    }

    @Test
    public void parseIncludeVideosIsPure() throws Exception {
        // 纯函数部分不依赖 org.json，android.jar 打桩的环境里也照跑
        assertTrue(MetadataRestoreJob.parseIncludeVideos(null));
        assertTrue(MetadataRestoreJob.parseIncludeVideos(Boolean.TRUE));
        assertFalse(MetadataRestoreJob.parseIncludeVideos(Boolean.FALSE));
        try {
            MetadataRestoreJob.parseIncludeVideos("true");
            fail("字符串不是布尔，必须拒绝");
        } catch (java.io.IOException expected) {
            assertEquals("invalid_include_videos", expected.getMessage());
        }
    }

    private static void assertRejected(File request, String reason) throws Exception {
        try {
            invokeParseRequest(request);
            fail("parseRequest 接受了本该拒绝的接管请求：" + reason);
        } catch (AssertionError expected) {
            assertTrue("拒绝原因不对：" + expected.getMessage(),
                    expected.getMessage() != null && expected.getMessage().contains(reason));
        }
    }

    private static File write(String body) throws Exception {
        File request = File.createTempFile("gallerysync-takeover-request", ".json");
        request.deleteOnExit();
        Files.write(request.toPath(), body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private static Object invokeParseRequest(File request) throws Exception {
        Method parse = MetadataRestoreJob.class.getDeclaredMethod("parseRequest", File.class);
        parse.setAccessible(true);
        try {
            return parse.invoke(null, request);
        } catch (InvocationTargetException failure) {
            throw new AssertionError("parseRequest 拒绝了请求: " + failure.getCause(),
                    failure.getCause());
        }
    }

    private static Object read(Object request, String field) throws Exception {
        Field target = request.getClass().getDeclaredField(field);
        target.setAccessible(true);
        return target.get(request);
    }

    private static boolean jsonUsable() {
        try {
            return new JSONObject("{\"probe\":1}").getInt("probe") == 1;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
