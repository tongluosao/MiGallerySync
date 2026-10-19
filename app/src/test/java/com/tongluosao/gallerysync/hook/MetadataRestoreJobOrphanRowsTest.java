package com.tongluosao.gallerysync.hook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * orphan_rows（"什么本地文件都没有的云行"）的判定测试。
 *
 * <p>判定的上半部分是纯逻辑：直接构造 {@link MetadataRestoreJob.OrphanRowFacts}，断言运行时
 * 删除任何一行之前必经的 gate（{@code OrphanRows.decision}）返回的字符串——空串才允许删，
 * 否则返回的字符串就是结果里上报的 reason。下半部分反射调用生产代码自己的
 * {@code parseRequest}，证明 {@code {mode:"orphan_rows", row, sha1}} 这种最小条目能通过校验，
 * 而空 sha1 会在进入判定之前就以 {@code invalid_item_metadata} 被拒。
 *
 * <p>真机上这 17 条残骸里有 12 条共用空文件的 sha1
 * {@code c70f1b7c644949a207103d83a801cd93ab607a7e}，所以"共享"必须按"同 sha1 且还能产出内容的
 * 别的行"来算：同为无文件残骸的行不算共享，否则它们会把彼此永久钉住，一条也清不掉。
 */
public class MetadataRestoreJobOrphanRowsTest {

    /** 真机上 12 条残骸共用的空文件 sha1，和它们各自的文件名形状。 */
    private static final String EMPTY_FILE_SHA1 = "c70f1b7c644949a207103d83a801cd93ab607a7e";
    private static final String TARGET =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private static String decision(MetadataRestoreJob.OrphanRowFacts facts) {
        return MetadataRestoreJob.OrphanRows.decision(facts);
    }

    /**
     * 一条真实可删的残骸：{@code MGSYNC_000.jpg} 这种名字、两个路径字段全空、没有 serverId、
     * localFlag = 7，且同 sha1 的其它行全是同样无文件的残骸（可产出内容的其它行数 = 0）。
     */
    private static MetadataRestoreJob.OrphanRowFacts orphanFacts() {
        MetadataRestoreJob.OrphanRowFacts facts = new MetadataRestoreJob.OrphanRowFacts();
        facts.rowId = "17123";
        facts.rowExists = true;
        facts.sha1Matches = true;
        facts.localFileBlank = true;
        facts.thumbnailFileBlank = true;
        facts.hasServerId = false;
        facts.localFlag = MetadataRestoreJob.OrphanRows.FLAG_LOCAL_ONLY;
        facts.fileNameBlank = false;
        facts.materialisableSha1Rows = 0L;
        facts.registryRegistered = false;
        return facts;
    }

    @Test
    public void removableHappyPathIsAllowed() {
        assertEquals("", decision(orphanFacts()));
    }

    /**
     * 12 条共享空文件 sha1 的残骸必须都能删：别的行也是残骸（无 localFile、无 serverId），
     * 它们产出不了任何内容，不该算成"共享"把这个批次锁死。
     */
    @Test
    public void rowsSharingEmptyFileSha1AreAllRemovable() {
        MetadataRestoreJob.OrphanRowFacts first = orphanFacts();
        first.rowId = "17123";
        first.materialisableSha1Rows = 0L;
        assertEquals("", decision(first));

        MetadataRestoreJob.OrphanRowFacts last = orphanFacts();
        last.rowId = "17134";
        last.materialisableSha1Rows = 0L;
        // 同一批 12 条里的每一条都独立判定通过，不会因为同 sha1 的行还在就被拒。
        assertEquals("", decision(last));
        assertEquals("", decision(orphanFacts()));
    }

    @Test
    public void localFilePresentIsRefused() {
        MetadataRestoreJob.OrphanRowFacts facts = orphanFacts();
        facts.localFileBlank = false;
        assertEquals("orphan_row_has_local_file", decision(facts));

        MetadataRestoreJob.OrphanRowFacts thumbnailOnly = orphanFacts();
        thumbnailOnly.thumbnailFileBlank = false;
        assertEquals("orphan_row_has_local_file", decision(thumbnailOnly));
    }

    @Test
    public void serverIdPresentIsRefused() {
        MetadataRestoreJob.OrphanRowFacts facts = orphanFacts();
        facts.hasServerId = true;
        assertEquals("orphan_row_has_server_id", decision(facts));
    }

    @Test
    public void sha1MismatchIsRefused() {
        MetadataRestoreJob.OrphanRowFacts facts = orphanFacts();
        facts.sha1Matches = false;
        assertEquals("orphan_row_sha1_mismatch", decision(facts));
    }

    /**
     * 同 sha1 的另一个行还能产出内容（真机上就是有 localFile 或 serverId 的行）→ 拒删，
     * 否则删掉这一行会让那个仍然可用的对象失去唯一引用。
     */
    @Test
    public void sha1SharedWithMaterialisableRowIsRefused() {
        MetadataRestoreJob.OrphanRowFacts facts = orphanFacts();
        facts.materialisableSha1Rows = 1L;
        assertEquals("orphan_row_shared", decision(facts));
    }

    /** 同 sha1 行数查询失败（-1）必须 fail closed，按"仍被共享"拒删。 */
    @Test
    public void failedSha1QueryIsRefused() {
        MetadataRestoreJob.OrphanRowFacts facts = orphanFacts();
        facts.materialisableSha1Rows = -1L;
        assertEquals("orphan_row_shared", decision(facts));
    }

    @Test
    public void registeredRowIsRefused() {
        MetadataRestoreJob.OrphanRowFacts facts = orphanFacts();
        facts.registryRegistered = true;
        assertEquals("orphan_row_registered", decision(facts));
    }

    @Test
    public void unsafeLocalFlagIsRefused() {
        MetadataRestoreJob.OrphanRowFacts facts = orphanFacts();
        facts.localFlag = 6;
        assertEquals("orphan_row_state_unsafe", decision(facts));

        MetadataRestoreJob.OrphanRowFacts synced = orphanFacts();
        synced.localFlag = 1;
        assertEquals("orphan_row_state_unsafe", decision(synced));
    }

    @Test
    public void blankFileNameIsRefused() {
        MetadataRestoreJob.OrphanRowFacts facts = orphanFacts();
        facts.fileNameBlank = true;
        assertEquals("orphan_row_no_filename", decision(facts));
    }

    @Test
    public void missingRowIsRefused() {
        MetadataRestoreJob.OrphanRowFacts facts = orphanFacts();
        facts.rowExists = false;
        assertEquals("orphan_row_missing", decision(facts));
        assertEquals("orphan_row_missing", decision(null));
    }

    /** 允许删除的 localFlag 恰好是 0 / 7 / 8，其它值一律不安全。 */
    @Test
    public void allowedLocalFlagsAreExactlyZeroSevenEight() {
        assertTrue(MetadataRestoreJob.OrphanRows.isAllowedLocalFlag(0));
        assertTrue(MetadataRestoreJob.OrphanRows.isAllowedLocalFlag(7));
        assertTrue(MetadataRestoreJob.OrphanRows.isAllowedLocalFlag(8));
        for (int flag : new int[]{1, 2, 3, 4, 5, 6, 9, 11, -1}) {
            assertFalse("localFlag=" + flag, MetadataRestoreJob.OrphanRows.isAllowedLocalFlag(flag));
        }
    }

    /** 最小条目（只有 mode/row/sha1，没有 sha256/length）必须通过生产代码的解析校验。 */
    @Test
    public void orphanRowsItemPassesParseValidation() throws Exception {
        Assume.assumeTrue("单测 JVM 里的 org.json 不可用（android.jar 打桩）", jsonUsable());
        File request = File.createTempFile("gallerysync-orphan-rows", ".json");
        request.deleteOnExit();
        String item = "{\"mode\":\"orphan_rows\",\"row\":\"17123\",\"sha1\":\""
                + EMPTY_FILE_SHA1 + "\"}";
        String body = "{\"jobId\":\"orphan-rows-test\",\"target\":\"" + TARGET
                + "\",\"items\":[" + item + "]}";
        Files.write(request.toPath(), body.getBytes(StandardCharsets.UTF_8));
        invokeParseRequest(request);
    }

    /**
     * 空 sha1 在解析阶段就被拒：没有指纹就无法证明"这一行就是请求说的那一行"，
     * 也走不到 {@code OrphanRows.decision}。
     */
    @Test
    public void blankSha1IsRejectedWithInvalidItemMetadata() throws Exception {
        Assume.assumeTrue("单测 JVM 里的 org.json 不可用（android.jar 打桩）", jsonUsable());
        File request = File.createTempFile("gallerysync-orphan-rows-blank", ".json");
        request.deleteOnExit();
        String item = "{\"mode\":\"orphan_rows\",\"row\":\"17123\",\"sha1\":\"\"}";
        String body = "{\"jobId\":\"orphan-rows-blank-test\",\"target\":\"" + TARGET
                + "\",\"items\":[" + item + "]}";
        Files.write(request.toPath(), body.getBytes(StandardCharsets.UTF_8));
        assertEquals("invalid_item_metadata", expectedParseReason(request));
    }

    /** 请求里任何键含 path/destination 都必须被拒，orphan_rows 不能成为绕道。 */
    @Test
    public void pathKeyIsRejected() throws Exception {
        Assume.assumeTrue("单测 JVM 里的 org.json 不可用（android.jar 打桩）", jsonUsable());
        File request = File.createTempFile("gallerysync-orphan-rows-path", ".json");
        request.deleteOnExit();
        String item = "{\"mode\":\"orphan_rows\",\"row\":\"17123\",\"sha1\":\""
                + EMPTY_FILE_SHA1 + "\",\"localPath\":\"/sdcard/DCIM/x.jpg\"}";
        String body = "{\"jobId\":\"orphan-rows-path-test\",\"target\":\"" + TARGET
                + "\",\"items\":[" + item + "]}";
        Files.write(request.toPath(), body.getBytes(StandardCharsets.UTF_8));
        assertEquals("request_path_not_allowed", expectedParseReason(request));
    }

    /** 调用生产代码的 private static parseRequest(File)，要求它接受这份请求。 */
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

    /** 调用 parseRequest 并要求它抛出 IOException，返回它的 reason 文本。 */
    private static String expectedParseReason(File request) throws Exception {
        Method parse = MetadataRestoreJob.class.getDeclaredMethod("parseRequest", File.class);
        parse.setAccessible(true);
        try {
            parse.invoke(null, request);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            assertTrue("期望 IOException，实际是 " + cause,
                    cause instanceof IOException);
            return cause.getMessage();
        }
        throw new AssertionError("parseRequest 接受了本应被拒的请求");
    }

    private static boolean jsonUsable() {
        try {
            return new JSONObject("{\"probe\":1}").getInt("probe") == 1;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
