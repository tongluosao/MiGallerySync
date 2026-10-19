package com.tongluosao.gallerysync.hook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 证明下发给设备的 stale_rows 请求能通过生产代码自己的解析校验（逐条 bad=0）。
 *
 * <p>请求文件与候选清单是本机离线分析的产物，只存在于
 * {@code D:\COD\migallerysync\.tmp-analysis\stale-rows\}；文件不存在时这两个用例会跳过，
 * 因此在其它机器上跑单测不受影响。单测 JVM 里的 org.json 来自 android.jar 的打桩实现，
 * 若它不可用，用例同样跳过（解析校验由同目录下的 JVM 探针改用真实 org.json 完成）。
 */
public class MetadataRestoreJobStaleRowsRequestTest {

    private static final File REQUEST = new File(
            "D:\\COD\\migallerysync\\.tmp-analysis\\stale-rows\\req-stale.json");
    private static final File CANDIDATES = new File(
            "D:\\COD\\migallerysync\\.tmp-analysis\\stale-rows\\candidates.json");
    private static final String SHA1 = "0123456789abcdef0123456789abcdef01234567";
    private static final String TARGET =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    public void generatedRequestPassesParseValidation() throws Exception {
        Assume.assumeTrue("req-stale.json 只在本机存在", REQUEST.isFile());
        Assume.assumeTrue("单测 JVM 里的 org.json 不可用（android.jar 打桩）", jsonUsable());
        String text = new String(Files.readAllBytes(REQUEST.toPath()), StandardCharsets.UTF_8);
        JSONObject root = new JSONObject(text);
        assertEquals("stale-rows-20261019", root.getString("jobId"));
        assertEquals(64, root.getString("target").length());
        assertTrue(root.getJSONArray("items").length() > 0);
        invokeParseRequest(REQUEST);
    }

    /**
     * 第 3 类重复登记行的请求形状：没有 baseRow，{@code length == diskLength}（两行登记的是同一份
     * 文件）。这一类没有新增字段，解析校验必须照旧通过；长度对不上时会退回第 2 类的语义。
     */
    @Test
    public void classThreeDuplicateItemPassesParseValidation() throws Exception {
        Assume.assumeTrue("单测 JVM 里的 org.json 不可用（android.jar 打桩）", jsonUsable());
        File request = File.createTempFile("gallerysync-stale-rows-duplicate", ".json");
        request.deleteOnExit();
        String item = "{\"mode\":\"stale_rows\",\"row\":\"15563\",\"sha1\":\"" + SHA1
                + "\",\"length\":1103701,\"diskLength\":1103701}";
        String body = "{\"jobId\":\"stale-rows-duplicate-test\",\"target\":\"" + TARGET
                + "\",\"items\":[" + item + "]}";
        Files.write(request.toPath(), body.getBytes(StandardCharsets.UTF_8));
        invokeParseRequest(request);
    }

    @Test
    public void candidateListPassesParseValidation() throws Exception {
        Assume.assumeTrue("candidates.json 只在本机存在", CANDIDATES.isFile());
        Assume.assumeTrue("单测 JVM 里的 org.json 不可用（android.jar 打桩）", jsonUsable());
        assertTrue(new JSONObject(new String(Files.readAllBytes(CANDIDATES.toPath()),
                StandardCharsets.UTF_8)).has("class1"));
    }

    /** 调用生产代码的 private static parseRequest(File)，任何异常都算校验失败。 */
    private static void invokeParseRequest(File request) throws Exception {
        Method parse = MetadataRestoreJob.class.getDeclaredMethod("parseRequest", File.class);
        parse.setAccessible(true);
        try {
            parse.invoke(null, request);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            throw new AssertionError("parseRequest 拒绝了请求: " + cause, cause);
        }
    }

    private static boolean jsonUsable() {
        try {
            return new JSONObject("{\"probe\":1}").getInt("probe") == 1;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
