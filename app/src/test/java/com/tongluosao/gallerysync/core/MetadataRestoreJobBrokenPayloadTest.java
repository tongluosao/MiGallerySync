package com.tongluosao.gallerysync.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.tongluosao.gallerysync.hook.MetadataRestoreJob;

import org.junit.Test;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * broken_payload 的纯逻辑测试：零字节 sha1 与删除判定。
 *
 * <p>这些用例直接构造 {@link MetadataRestoreJob.BrokenPayloadFacts}，不启动 Android、不访问磁盘，
 * 断言的是运行时删除任何一行之前必经的那道 gate（{@code BrokenPayload.decision}）：返回空串才
 * 允许删，返回的字符串就是结果里上报的 reason。
 *
 * <p>重点是最后一道保护：sha1 全对但内容不是全零（也就是一张真照片）时必须
 * {@code payload_not_broken} —— 这个模式存在的理由只允许"内容可证明为零"。
 */
public class MetadataRestoreJobBrokenPayloadTest {

    /** 样本长度：覆盖 64KiB 分块边界两侧与多块。 */
    private static final long[] SAMPLE_LENGTHS =
            new long[]{1L, 2L, 8L, 1024L, 65536L, 65537L, 70000L, 3L * 65536L + 7L};

    private static MetadataRestoreJob.BrokenPayloadFacts validFacts(long length) {
        MetadataRestoreJob.BrokenPayloadFacts facts =
                new MetadataRestoreJob.BrokenPayloadFacts();
        facts.rowId = "15989";
        facts.rowExists = true;
        facts.sha1Matches = true;
        facts.hasServerId = false;
        facts.localFlag = MetadataRestoreJob.BrokenPayload.FLAG_LOCAL_ONLY;
        facts.localFileResolved = true;
        facts.filePresent = true;
        facts.fileLengthMatches = true;
        facts.fileSha1Matches = true;
        facts.payloadAllZero = true;
        facts.referencingRowIds = new ArrayList<>(Arrays.asList("15989"));
        assertTrue(length > 0L);
        return facts;
    }

    // ---------------------------------------------------------------- 零字节 sha1

    /** 每个长度都必须等于"独立构造的一段 0 字节缓冲区"的 sha1（不查表、不写常量）。 */
    @Test
    public void zeroSha1MatchesAnIndependentZeroBuffer() throws Exception {
        for (long length : SAMPLE_LENGTHS) {
            String expected = referenceZeroSha1((int) length);
            String actual = MetadataRestoreJob.BrokenPayload.zeroSha1(length);
            assertEquals("length=" + length, expected, actual);
            assertTrue("length=" + length, actual.matches("[0-9a-f]{40}"));
        }
    }

    /** 不同长度必须是不同摘要：写死成常量就会被这条抓住。 */
    @Test
    public void zeroSha1DiffersPerLength() {
        assertEquals(40, MetadataRestoreJob.BrokenPayload.zeroSha1(1L).length());
        assertFalse(MetadataRestoreJob.BrokenPayload.zeroSha1(1L)
                .equals(MetadataRestoreJob.BrokenPayload.zeroSha1(2L)));
        assertFalse(MetadataRestoreJob.BrokenPayload.zeroSha1(1024L)
                .equals(MetadataRestoreJob.BrokenPayload.zeroSha1(1025L)));
    }

    /** 没有长度就没有"全零内容"这件事，必须返回 null 而不是空串或空 sha1。 */
    @Test
    public void zeroSha1RejectsNonPositiveLengths() {
        assertNull(MetadataRestoreJob.BrokenPayload.zeroSha1(0L));
        assertNull(MetadataRestoreJob.BrokenPayload.zeroSha1(-1L));
        assertNull(MetadataRestoreJob.BrokenPayload.zeroSha1(Long.MIN_VALUE));
    }

    @Test
    public void zeroPayloadSha1AcceptsBothHexCasesAndRejectsEverythingElse() {
        String zero = MetadataRestoreJob.BrokenPayload.zeroSha1(1024L);
        assertTrue(MetadataRestoreJob.BrokenPayload.isZeroPayloadSha1(zero, 1024L));
        assertTrue(MetadataRestoreJob.BrokenPayload.isZeroPayloadSha1(
                zero.toUpperCase(java.util.Locale.ROOT), 1024L));
        // 同一个摘要换个长度就不再是"这个长度的全零内容"。
        assertFalse(MetadataRestoreJob.BrokenPayload.isZeroPayloadSha1(zero, 1025L));
        assertFalse(MetadataRestoreJob.BrokenPayload.isZeroPayloadSha1(null, 1024L));
        assertFalse(MetadataRestoreJob.BrokenPayload.isZeroPayloadSha1(zero, 0L));
        assertFalse(MetadataRestoreJob.BrokenPayload.isZeroPayloadSha1(
                referenceNonZeroSha1(), 1024L));
    }

    // ---------------------------------------------------------------- 允许删除

    @Test
    public void allZeroCopyIsDeletable() {
        assertEquals("", decision(validFacts(4096L)));
    }

    @Test
    public void flagSevenAndEightAndTwoAreAllDeletable() {
        MetadataRestoreJob.BrokenPayloadFacts facts = validFacts(4096L);
        facts.localFlag = MetadataRestoreJob.BrokenPayload.FLAG_LOCAL_ONLY;
        assertEquals("", decision(facts));
        facts.localFlag = MetadataRestoreJob.BrokenPayload.FLAG_LOCAL_ONLY_SYNCED;
        assertEquals("", decision(facts));
        facts.localFlag = MetadataRestoreJob.BrokenPayload.FLAG_LOCAL_ONLY_PENDING;
        assertEquals("", decision(facts));
    }

    /** 引用列表里必然包含本行；只有自己引用时不算共享。 */
    @Test
    public void selfReferenceIsNotSharing() {
        MetadataRestoreJob.BrokenPayloadFacts facts = validFacts(4096L);
        facts.referencingRowIds = new ArrayList<>(Arrays.asList("15989"));
        assertEquals("", decision(facts));
        assertEquals("", decision(validFacts(4096L)));
    }

    // ---------------------------------------------------------------- 拒绝

    @Test
    public void missingRowIsRejectedFirst() {
        MetadataRestoreJob.BrokenPayloadFacts facts = validFacts(4096L);
        facts.rowExists = false;
        facts.sha1Matches = false;
        facts.hasServerId = true;
        facts.localFlag = 0;
        assertEquals("broken_payload_missing", decision(facts));
        assertEquals("broken_payload_missing", decision(null));
    }

    @Test
    public void rowWithAnotherSha1IsRejected() {
        MetadataRestoreJob.BrokenPayloadFacts facts = validFacts(4096L);
        facts.sha1Matches = false;
        assertEquals("broken_payload_sha1_mismatch", decision(facts));
    }

    @Test
    public void uploadedRowIsRejected() {
        MetadataRestoreJob.BrokenPayloadFacts facts = validFacts(4096L);
        facts.hasServerId = true;
        assertEquals("broken_payload_has_server_id", decision(facts));
    }

    @Test
    public void rowWithFlagOutsideTheAllowedSetIsRejected() {
        for (int flag : new int[]{0, 1, 3, 5, 6, 9, 16, -1}) {
            MetadataRestoreJob.BrokenPayloadFacts facts = validFacts(4096L);
            facts.localFlag = flag;
            assertEquals("flag=" + flag, "broken_payload_state_unsafe", decision(facts));
        }
    }

    @Test
    public void rowWithoutAResolvableLocalFileIsRejected() {
        MetadataRestoreJob.BrokenPayloadFacts unresolved = validFacts(4096L);
        unresolved.localFileResolved = false;
        assertEquals("broken_payload_file_missing", decision(unresolved));
        MetadataRestoreJob.BrokenPayloadFacts absent = validFacts(4096L);
        absent.filePresent = false;
        assertEquals("broken_payload_file_missing", decision(absent));
    }

    @Test
    public void sizeMismatchIsRejected() {
        MetadataRestoreJob.BrokenPayloadFacts facts = validFacts(4096L);
        facts.fileLengthMatches = false;
        assertEquals("broken_payload_length_mismatch", decision(facts));
    }

    @Test
    public void fileWhoseBytesDifferFromTheRequestedSha1IsRejected() {
        MetadataRestoreJob.BrokenPayloadFacts facts = validFacts(4096L);
        facts.fileSha1Matches = false;
        assertEquals("broken_payload_file_sha1_mismatch", decision(facts));
    }

    /**
     * 最关键的一条：sha1 三方都对，但内容不是全零 —— 这就是一张真照片。
     * 此时必须 payload_not_broken，绝不能落到可删除。
     */
    @Test
    public void sha1MatchesButContentIsNotZeroIsRefused() {
        MetadataRestoreJob.BrokenPayloadFacts facts = validFacts(4096L);
        facts.payloadAllZero = false;
        assertEquals("payload_not_broken", decision(facts));
        // payloadAllZero 为 false 时其它字段再完美也不允许删。
        facts.referencingRowIds = new ArrayList<>(Arrays.asList("15989"));
        assertEquals("payload_not_broken", decision(facts));
    }

    @Test
    public void fileSharedWithAnotherRowIsRejected() {
        MetadataRestoreJob.BrokenPayloadFacts facts = validFacts(4096L);
        facts.referencingRowIds = new ArrayList<>(Arrays.asList("15989", "16001"));
        assertEquals("broken_payload_file_shared", decision(facts));
    }

    @Test
    public void referenceQueryFailureIsTreatedAsShared() {
        MetadataRestoreJob.BrokenPayloadFacts facts = validFacts(4096L);
        facts.referenceQueryFailed = true;
        facts.referencingRowIds = new ArrayList<>();
        assertEquals("broken_payload_file_shared", decision(facts));
    }

    @Test
    public void nullReferenceListIsTreatedAsShared() {
        MetadataRestoreJob.BrokenPayloadFacts facts = validFacts(4096L);
        facts.referencingRowIds = null;
        assertEquals("broken_payload_file_shared", decision(facts));
    }

    @Test
    public void hasOtherReferencesIgnoresSelfAndNull() {
        assertFalse(MetadataRestoreJob.BrokenPayload.hasOtherReferences(
                new ArrayList<>(Arrays.asList("15989")), "15989"));
        assertFalse(MetadataRestoreJob.BrokenPayload.hasOtherReferences(
                new ArrayList<>(Arrays.asList((String) null, "15989")), "15989"));
        assertFalse(MetadataRestoreJob.BrokenPayload.hasOtherReferences(
                new ArrayList<>(), "15989"));
        assertTrue(MetadataRestoreJob.BrokenPayload.hasOtherReferences(
                new ArrayList<>(Arrays.asList("15989", "16001")), "15989"));
        assertTrue(MetadataRestoreJob.BrokenPayload.hasOtherReferences(null, "15989"));
    }

    // ---------------------------------------------------------------- 工具

    private static String decision(MetadataRestoreJob.BrokenPayloadFacts facts) {
        return MetadataRestoreJob.BrokenPayload.decision(facts);
    }

    /** 独立参照实现：一次 digest 一段 new byte[n]，与被测的分块实现没有共用代码。 */
    private static String referenceZeroSha1(int length) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        return hex(digest.digest(new byte[length]));
    }

    /** 一段非零字节的 sha1（形状与 40 位十六进制一致，但内容不是全零）。 */
    private static String referenceNonZeroSha1() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = new byte[1024];
            for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (i % 251 + 1);
            return hex(digest.digest(bytes));
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            hex.append(Integer.toHexString((value & 0xff) | 0x100).substring(1));
        }
        return hex.toString();
    }
}
