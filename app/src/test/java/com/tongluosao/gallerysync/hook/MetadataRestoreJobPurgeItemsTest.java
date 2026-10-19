package com.tongluosao.gallerysync.hook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * purge_items 的纯逻辑测试：逐条证明"删这一行"之前必经的那道 gate
 * （{@link MetadataRestoreJob.PurgeItems#decision}）以及请求解析的唯一形状。
 *
 * <p>判定用例直接构造 {@link MetadataRestoreJob.PurgeItemFacts}，不启动 Android、不访问磁盘：
 * 返回空串才允许删，返回的字符串就是结果里上报的 reason。通过条件一共六条，用例按同样的顺序
 * 逐条打断一条、其余保持成立，因此每个拒绝理由都只由它自己那一条触发。
 *
 * <p>解析用例走生产代码的 {@code parseRequest(File)}：purge_items 必须接受
 * {@code {"mode","row","sha1","length"}}，出现 baseRow/baseSha1 或缺少 length 时整单拒绝。
 * 单测 JVM 里的 org.json 来自 android.jar 的打桩实现，不可用时这些用例跳过（与既有
 * parseRequest 用例同一约定）。
 */
public class MetadataRestoreJobPurgeItemsTest {

    private static final String SHA1 = "0123456789abcdef0123456789abcdef01234567";
    /** 同长度、不同内容的另一张照片的 sha1。 */
    private static final String OTHER_SHA1 = "fedcba9876543210fedcba9876543210fedcba98";
    private static final String TARGET =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final long LENGTH = 1103701L;

    private static MetadataRestoreJob.PurgeItemFacts validFacts() {
        MetadataRestoreJob.PurgeItemFacts facts = new MetadataRestoreJob.PurgeItemFacts();
        facts.rowId = "15563";
        facts.requestSha1 = SHA1;
        facts.requestedLength = LENGTH;
        facts.rowExists = true;
        facts.sha1Matches = true;
        facts.hasServerId = false;
        facts.localFlag = MetadataRestoreJob.PurgeItems.FLAG_LOCAL_ONLY;
        facts.localFileBlank = false;
        facts.localFileResolved = true;
        facts.filePresent = true;
        facts.lengthMatches = true;
        facts.fileSha1Matches = true;
        facts.referenceQueryFailed = false;
        // 引用行列表里必然包含本行自身，判定必须把它排除。
        facts.references = new ArrayList<>();
        facts.references.add(new MetadataRestoreJob.PurgeReference("15563", SHA1));
        return facts;
    }

    private static String decision(MetadataRestoreJob.PurgeItemFacts facts) {
        return MetadataRestoreJob.PurgeItems.decision(facts);
    }

    // ------------------------------------------------------------ 通过条件

    /** 六条全成立：行与磁盘文件都对得上，且没有别的照片共用这份文件 → 允许删。 */
    @Test
    public void validItemWithLocalFilePasses() {
        assertEquals("", decision(validFacts()));
    }

    /** 规格 7：本地文件不存在时跳过长度与 sha1 两条，只删行。 */
    @Test
    public void existingItemWithoutLocalFilePasses() {
        MetadataRestoreJob.PurgeItemFacts facts = validFacts();
        facts.localFileBlank = true;
        facts.localFileResolved = false;
        facts.filePresent = false;
        facts.lengthMatches = false;
        facts.fileSha1Matches = false;
        assertEquals("", decision(facts));
    }

    /** localFile 有值但文件已经不在盘上：同样跳过长度/sha1，不因"没有文件"拒掉。 */
    @Test
    public void resolvedButAbsentFileSkipsLengthAndSha1() {
        MetadataRestoreJob.PurgeItemFacts facts = validFacts();
        facts.filePresent = false;
        facts.lengthMatches = false;
        facts.fileSha1Matches = false;
        assertEquals("", decision(facts));
    }

    /** 文件不在盘上时连 requestedLength 都不看：没有文件就没有可核对的长度。 */
    @Test
    public void absentFileIgnoresNonPositiveRequestedLength() {
        MetadataRestoreJob.PurgeItemFacts facts = validFacts();
        facts.filePresent = false;
        facts.requestedLength = 0L;
        facts.lengthMatches = false;
        facts.fileSha1Matches = false;
        assertEquals("", decision(facts));
    }

    /** 同路径 + 同 sha1 的重复登记行不算"共享"：那是同一张照片的多次登记，允许删其中一行。 */
    @Test
    public void sameSha1DuplicateRegistrationPasses() {
        MetadataRestoreJob.PurgeItemFacts facts = validFacts();
        facts.references.add(new MetadataRestoreJob.PurgeReference("16001", SHA1));
        assertEquals("", decision(facts));
    }

    /** sha1 比较忽略大小写：库里存的是大写时不能误判成"另一张照片"。 */
    @Test
    public void duplicateSha1ComparisonIgnoresCase() {
        MetadataRestoreJob.PurgeItemFacts facts = validFacts();
        facts.references.add(new MetadataRestoreJob.PurgeReference("16002",
                SHA1.toUpperCase(java.util.Locale.ROOT)));
        assertEquals("", decision(facts));
    }

    // ------------------------------------------------------------ 逐条拒绝

    /** 条件 1：行已经不在了。 */
    @Test
    public void missingRowRejected() {
        MetadataRestoreJob.PurgeItemFacts facts = validFacts();
        facts.rowExists = false;
        assertEquals("purge_row_missing", decision(facts));
    }

    /** 查不到行和"没有事实"是同一种结局：一律 purge_row_missing，绝不能默认放行。 */
    @Test
    public void nullFactsRejected() {
        assertEquals("purge_row_missing", decision(null));
    }

    /** 条件 2：行记录的 sha1 与请求不一致。 */
    @Test
    public void sha1MismatchRejected() {
        MetadataRestoreJob.PurgeItemFacts facts = validFacts();
        facts.sha1Matches = false;
        assertEquals("purge_sha1_mismatch", decision(facts));
    }

    /** 条件 3：行还有 serverId —— 远端有对象，删了本地就会触发补齐重新下载。 */
    @Test
    public void serverIdRejected() {
        MetadataRestoreJob.PurgeItemFacts facts = validFacts();
        facts.hasServerId = true;
        assertEquals("purge_has_server_id", decision(facts));
    }

    /** 条件 4：只在本机的三种 localFlag（0/7/8）都允许删。 */
    @Test
    public void allowedLocalFlagsPass() {
        int[] allowed = new int[]{MetadataRestoreJob.PurgeItems.FLAG_LOCAL_STABLE,
                MetadataRestoreJob.PurgeItems.FLAG_LOCAL_ONLY,
                MetadataRestoreJob.PurgeItems.FLAG_LOCAL_ONLY_SYNCED};
        for (int flag : allowed) {
            MetadataRestoreJob.PurgeItemFacts facts = validFacts();
            facts.localFlag = flag;
            assertEquals("flag=" + flag, "", decision(facts));
            assertTrue("flag=" + flag,
                    MetadataRestoreJob.PurgeItems.isAllowedLocalFlag(flag));
        }
    }

    /** 条件 4：其余状态（含已同步、待上传、未知值）一个都不放过。 */
    @Test
    public void unsafeLocalFlagsRejected() {
        int[] rejected = new int[]{-1, 1, 3, 4, 5, 6, 9, 10, 100};
        for (int flag : rejected) {
            MetadataRestoreJob.PurgeItemFacts facts = validFacts();
            facts.localFlag = flag;
            assertEquals("flag=" + flag, "purge_state_unsafe", decision(facts));
            assertFalse("flag=" + flag,
                    MetadataRestoreJob.PurgeItems.isAllowedLocalFlag(flag));
        }
    }

    /** 条件 4 补充：回收站行只在文件确已不存在时放行，文件还在就仍然拒。 */
    @Test
    public void trashedRowAllowedOnlyWithoutFile() {
        MetadataRestoreJob.PurgeItemFacts withoutFile = validFacts();
        withoutFile.localFlag = MetadataRestoreJob.PurgeItems.FLAG_LOCAL_TRASHED;
        withoutFile.filePresent = false;
        withoutFile.lengthMatches = false;
        withoutFile.fileSha1Matches = false;
        assertEquals("", decision(withoutFile));

        MetadataRestoreJob.PurgeItemFacts withFile = validFacts();
        withFile.localFlag = MetadataRestoreJob.PurgeItems.FLAG_LOCAL_TRASHED;
        assertEquals("purge_state_unsafe", decision(withFile));
    }

    /** 条件 5：同路径的其它行登记着不同的 sha1 —— 这份文件是另一张照片的，拒。 */
    @Test
    public void conflictingReferenceRejected() {
        MetadataRestoreJob.PurgeItemFacts facts = validFacts();
        facts.references.add(new MetadataRestoreJob.PurgeReference("16003", OTHER_SHA1));
        assertEquals("purge_file_shared", decision(facts));
    }

    /** 条件 5：引用行的 sha1 读不到（NULL）时按"可能是别的照片"拒。 */
    @Test
    public void referenceWithNullSha1Rejected() {
        MetadataRestoreJob.PurgeItemFacts facts = validFacts();
        facts.references.add(new MetadataRestoreJob.PurgeReference("16004", null));
        assertEquals("purge_file_shared", decision(facts));
    }

    /** 条件 5：查询异常按"被共享"处理（fail closed）。 */
    @Test
    public void referenceQueryFailureRejected() {
        MetadataRestoreJob.PurgeItemFacts facts = validFacts();
        facts.referenceQueryFailed = true;
        assertEquals("purge_file_shared", decision(facts));
    }

    /** 引用列表为 null（没人采到）时同样按被共享拒。 */
    @Test
    public void nullReferenceListRejected() {
        MetadataRestoreJob.PurgeItemFacts facts = validFacts();
        facts.references = null;
        assertEquals("purge_file_shared", decision(facts));
        assertTrue(MetadataRestoreJob.PurgeItems.hasConflictingReference(null, "15563", SHA1));
    }

    /** 条件 5 先于文件校验：共享的文件哪怕长度也不对，上报的仍是 purge_file_shared。 */
    @Test
    public void sharedCheckRunsBeforeFileChecks() {
        MetadataRestoreJob.PurgeItemFacts facts = validFacts();
        facts.references.add(new MetadataRestoreJob.PurgeReference("16005", OTHER_SHA1));
        facts.lengthMatches = false;
        facts.fileSha1Matches = false;
        assertEquals("purge_file_shared", decision(facts));
    }

    /** 条件 6：本地文件长度与请求不一致。 */
    @Test
    public void lengthMismatchRejected() {
        MetadataRestoreJob.PurgeItemFacts facts = validFacts();
        facts.lengthMatches = false;
        assertEquals("purge_length_mismatch", decision(facts));
    }

    /** 条件 6：长度对得上但磁盘内容的 sha1 不是请求里那一份（不同的照片同名同大小）。 */
    @Test
    public void fileSha1MismatchRejected() {
        MetadataRestoreJob.PurgeItemFacts facts = validFacts();
        facts.fileSha1Matches = false;
        assertEquals("purge_file_sha1_mismatch", decision(facts));
    }

    /** 文件在盘上却没给出正的长度：没有可核对的证据，属于非法条目而不是长度不符。 */
    @Test
    public void nonPositiveRequestedLengthForPresentFileRejected() {
        for (long length : new long[]{0L, -1L, Long.MIN_VALUE}) {
            MetadataRestoreJob.PurgeItemFacts facts = validFacts();
            facts.requestedLength = length;
            assertEquals("length=" + length, "invalid_purge_item", decision(facts));
        }
    }

    /** localFile 有值却规范化不到共享存储根目录内：核不了文件也删不了文件，绝不删行。 */
    @Test
    public void unresolvedLocalPathRejected() {
        MetadataRestoreJob.PurgeItemFacts facts = validFacts();
        facts.localFileResolved = false;
        assertEquals("invalid_purge_item", decision(facts));
    }

    /** 没有任何本地路径的行不受"未解析"影响：空白 localFile 就是没有文件。 */
    @Test
    public void blankLocalPathIsNotInvalid() {
        MetadataRestoreJob.PurgeItemFacts facts = validFacts();
        facts.localFileBlank = true;
        facts.localFileResolved = false;
        facts.filePresent = false;
        assertEquals("", decision(facts));
    }

    /** 判定是幂等的纯函数：同样的输入两次同结果，且不改动事实对象里的引用列表。 */
    @Test
    public void decisionIsSideEffectFree() {
        MetadataRestoreJob.PurgeItemFacts facts = validFacts();
        assertEquals("", decision(facts));
        assertEquals("", decision(facts));
        assertEquals(1, facts.references.size());
        assertEquals("15563", facts.references.get(0).rowId);
        assertEquals(SHA1, facts.references.get(0).sha1);
    }

    // ------------------------------------------------------------ 请求解析

    /** purge_items 的合法形状：只有 mode/row/sha1/length，没有 sha256、没有 base 行。 */
    @Test
    public void purgeItemWithoutBaseRowPassesParse() throws Exception {
        Assume.assumeTrue("单测 JVM 里的 org.json 不可用（android.jar 打桩）", jsonUsable());
        File request = writeRequest("purge-items-valid", purgeItem("15563", LENGTH, ""));
        assertEquals(null, parseFailure(request));
    }

    /** 出现 baseRow 就是按别的模式的形状造的请求：整单 invalid_item_mode。 */
    @Test
    public void purgeItemWithBaseRowRejected() throws Exception {
        Assume.assumeTrue("单测 JVM 里的 org.json 不可用（android.jar 打桩）", jsonUsable());
        File request = writeRequest("purge-items-base-row",
                purgeItem("15563", LENGTH, ",\"baseRow\":\"15000\""));
        assertEquals("invalid_item_mode", parseFailure(request));
    }

    /** baseSha1 同理；大小写混写（"BASESHA1"）也必须被拒，不能靠键名大小写绕过。 */
    @Test
    public void purgeItemWithBaseSha1Rejected() throws Exception {
        Assume.assumeTrue("单测 JVM 里的 org.json 不可用（android.jar 打桩）", jsonUsable());
        File lower = writeRequest("purge-items-base-sha1",
                purgeItem("15563", LENGTH, ",\"baseSha1\":\"" + SHA1 + "\""));
        assertEquals("invalid_item_mode", parseFailure(lower));
        File upper = writeRequest("purge-items-base-sha1-upper",
                purgeItem("15563", LENGTH, ",\"BASESHA1\":\"" + SHA1 + "\""));
        assertEquals("invalid_item_mode", parseFailure(upper));
    }

    /** purge_items 必须给出 length：本地文件存在时要拿它逐字节核对。 */
    @Test
    public void purgeItemWithoutLengthRejected() throws Exception {
        Assume.assumeTrue("单测 JVM 里的 org.json 不可用（android.jar 打桩）", jsonUsable());
        File request = writeRequest("purge-items-no-length",
                "{\"mode\":\"purge_items\",\"row\":\"15563\",\"sha1\":\"" + SHA1 + "\"}");
        assertEquals("invalid_item_length", parseFailure(request));
    }

    /** length 为 0/负数同样非法：purge_items 的长度必须是正的。 */
    @Test
    public void purgeItemWithNonPositiveLengthRejected() throws Exception {
        Assume.assumeTrue("单测 JVM 里的 org.json 不可用（android.jar 打桩）", jsonUsable());
        File zero = writeRequest("purge-items-zero-length", purgeItem("15563", 0L, ""));
        assertEquals("invalid_item_length", parseFailure(zero));
    }

    /** sha1 形状不合法时仍是既有的 invalid_item_metadata，不是新增理由。 */
    @Test
    public void purgeItemWithShortSha1Rejected() throws Exception {
        Assume.assumeTrue("单测 JVM 里的 org.json 不可用（android.jar 打桩）", jsonUsable());
        File request = writeRequest("purge-items-short-sha1",
                "{\"mode\":\"purge_items\",\"row\":\"15563\",\"sha1\":\"abc\","
                        + "\"length\":1103701}");
        assertEquals("invalid_item_metadata", parseFailure(request));
    }

    /** 每条一个 item 的请求体里可以混排两条 purge_items：请求本身是合法的。 */
    @Test
    public void multiplePurgeItemsPassParse() throws Exception {
        Assume.assumeTrue("单测 JVM 里的 org.json 不可用（android.jar 打桩）", jsonUsable());
        String body = "{\"jobId\":\"purge-items-multi\",\"target\":\"" + TARGET + "\",\"items\":["
                + purgeItem("15563", LENGTH, "") + "," + purgeItem("16001", LENGTH, "") + "]}";
        assertEquals(null, parseFailure(writeRequest("purge-items-multi", body)));
    }

    // ------------------------------------------------------------ 工具

    private static String purgeItem(String row, long length, String extra) {
        return "{\"mode\":\"purge_items\",\"row\":\"" + row + "\",\"sha1\":\"" + SHA1
                + "\",\"length\":" + length + extra + "}";
    }

    private static File writeRequest(String name, String item) throws Exception {
        File request = File.createTempFile("gallerysync-" + name, ".json");
        request.deleteOnExit();
        String body = "{\"jobId\":\"" + name + "\",\"target\":\"" + TARGET
                + "\",\"items\":[" + item + "]}";
        Files.write(request.toPath(), body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    /** 调用生产代码的 private static parseRequest(File)：返回拒绝理由，接受时返回 null。 */
    private static String parseFailure(File request) throws Exception {
        Method parse = MetadataRestoreJob.class.getDeclaredMethod("parseRequest", File.class);
        parse.setAccessible(true);
        try {
            parse.invoke(null, request);
            return null;
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            String message = cause == null ? null : cause.getMessage();
            return message == null ? cause.getClass().getSimpleName() : message;
        }
    }

    private static boolean jsonUsable() {
        try {
            return new org.json.JSONObject("{\"probe\":1}").getInt("probe") == 1;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 引用列表的两种形状都必须是"元素可空、可含本行"的朴素列表。 */
    @Test
    public void referenceListToleratesNullEntries() {
        List<MetadataRestoreJob.PurgeReference> references = new ArrayList<>();
        references.add(null);
        references.add(new MetadataRestoreJob.PurgeReference(null, null));
        references.add(new MetadataRestoreJob.PurgeReference("15563", SHA1));
        assertFalse(MetadataRestoreJob.PurgeItems.hasConflictingReference(references, "15563",
                SHA1));
    }
}
