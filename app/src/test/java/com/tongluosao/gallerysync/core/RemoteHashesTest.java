package com.tongluosao.gallerysync.core;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 远端哈希旁车：路径映射、内容格式、以及读写的失败语义。
 *
 * <p>全部用内存里的 {@link RemoteHashes.Io} 假实现，不碰网络、也不碰 android.util.Log
 * （旁车写入成功时不打日志，所以这些用例在纯 JVM 单测里跑得通）。
 */
public class RemoteHashesTest {

    private static final String ROOT = "/MiGallerySync";
    private static final String SHA =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    /** 内存版远端：key 是远端路径，value 是字节内容。 */
    private static final class FakeIo implements RemoteHashes.Io {
        final Map<String, byte[]> files = new HashMap<>();
        int reads;
        int writes;
        boolean failReads;

        @Override
        public byte[] read(String remotePath) throws java.io.IOException {
            reads++;
            if (failReads) {
                throw new java.io.IOException("simulated read failure");
            }
            byte[] body = files.get(remotePath);
            return body == null ? null : body.clone();
        }

        @Override
        public void write(String remotePath, byte[] body) {
            writes++;
            files.put(remotePath, body.clone());
        }

        @Override
        public boolean delete(String remotePath) {
            return files.remove(remotePath) != null;
        }
    }

    // ------------------------------------------------------------ 路径映射

    /** 子目录原图：旁车保留完整的相对目录结构，文件名后缀 .sha256。 */
    @Test
    public void nestedDirMapsUnderHiddenDir() {
        assertEquals(ROOT + "/.mgs-hashes/2026/10/IMG_0001.jpg.sha256",
                RemoteHashes.hashPathFor(ROOT, "/MiGallerySync/2026/10/IMG_0001.jpg"));
    }

    /** 根目录直挂的原图（只有一层）。 */
    @Test
    public void rootLevelFileMapsUnderHiddenDir() {
        assertEquals(ROOT + "/.mgs-hashes/IMG_0001.jpg.sha256",
                RemoteHashes.hashPathFor(ROOT, "/MiGallerySync/IMG_0001.jpg"));
        assertEquals("/IMG_0001.jpg", RemoteHashes.relativeOf(ROOT,
                "/MiGallerySync/IMG_0001.jpg"));
    }

    /** 大小写：根目录只差大小写时仍能切出相对路径，而文件名的大小写逐字保留。 */
    @Test
    public void caseIsPreservedAndRootComparedIgnoringCase() {
        assertEquals(ROOT + "/.mgs-hashes/2026/IMG_0001.JPG.sha256",
                RemoteHashes.hashPathFor(ROOT, "/migallerysync/2026/IMG_0001.JPG"));
        assertEquals("/2026/IMG_0001.JPG", RemoteHashes.relativeOf(ROOT,
                "/MiGallerySync/2026/IMG_0001.JPG"));
    }

    /** 根目录带尾斜杠与不带尾斜杠必须映射到同一个旁车路径。 */
    @Test
    public void trailingSlashOnRootIsIgnored() {
        String a = RemoteHashes.hashPathFor(ROOT, "/MiGallerySync/a/b.jpg");
        String b = RemoteHashes.hashPathFor(ROOT + "/", "/MiGallerySync/a/b.jpg");
        assertEquals(a, b);
    }

    /** 不在 root 下的路径原样作为相对路径：绝不返回空串（否则所有越界路径共用一个旁车）。 */
    @Test
    public void pathOutsideRootKeepsItsOwnIdentity() {
        assertEquals("/Other/x.jpg", RemoteHashes.relativeOf(ROOT, "/Other/x.jpg"));
        assertEquals(ROOT + "/.mgs-hashes/Other/x.jpg.sha256",
                RemoteHashes.hashPathFor(ROOT, "/Other/x.jpg"));
        assertFalse(RemoteHashes.hashPathFor(ROOT, "/Other/x.jpg")
                .equals(RemoteHashes.hashPathFor(ROOT, "/Other/y.jpg")));
    }

    /** 空根目录退化成“从 WebDAV 根开始”。 */
    @Test
    public void blankRootFallsBackToWebDavRoot() {
        assertEquals("/.mgs-hashes/A.jpg.sha256", RemoteHashes.hashPathFor("", "/A.jpg"));
        assertEquals("/.mgs-hashes/A.jpg.sha256", RemoteHashes.hashPathFor(null, "/A.jpg"));
    }

    // ------------------------------------------------------------ 内容格式

    /** format/parse 必须往返一致。 */
    @Test
    public void formatParseRoundTrip() {
        String text = RemoteHashes.format(SHA, 4_194_304L);
        assertEquals("mgs-hash/1 " + SHA + " 4194304\n", text);
        RemoteHashes.Entry entry = RemoteHashes.parse(text.getBytes(StandardCharsets.US_ASCII));
        assertNotNull(entry);
        assertEquals(SHA, entry.sha256);
        assertEquals(4_194_304L, entry.length);
    }

    /** 写出去的一律是小写摘要；读进来时大写也能接受并归一成小写。 */
    @Test
    public void digestCaseIsNormalized() {
        assertEquals("mgs-hash/1 " + SHA + " 7\n",
                RemoteHashes.format(SHA.toUpperCase(Locale.US), 7L));
        RemoteHashes.Entry entry = RemoteHashes.parse(
                ("mgs-hash/1 " + SHA.toUpperCase(Locale.US) + " 7\n")
                        .getBytes(StandardCharsets.US_ASCII));
        assertNotNull(entry);
        assertEquals(SHA, entry.sha256);
    }

    /** 尾部少一个换行也接受（手工写的旁车照样能读）。 */
    @Test
    public void missingTrailingNewlineIsAccepted() {
        assertNotNull(RemoteHashes.parse(
                ("mgs-hash/1 " + SHA + " 12").getBytes(StandardCharsets.US_ASCII)));
    }

    /** 非法内容一律 null，绝不抛异常。 */
    @Test
    public void invalidPayloadsReturnNull() {
        assertNull(RemoteHashes.parse(null));
        assertNull(RemoteHashes.parse(new byte[0]));
        // 缺字段
        assertNull(RemoteHashes.parse(("mgs-hash/1 " + SHA).getBytes(StandardCharsets.US_ASCII)));
        assertNull(RemoteHashes.parse("mgs-hash/1\n".getBytes(StandardCharsets.US_ASCII)));
        // 多字段
        assertNull(RemoteHashes.parse(("mgs-hash/1 " + SHA + " 1 x\n")
                .getBytes(StandardCharsets.US_ASCII)));
        // 版本头不对
        assertNull(RemoteHashes.parse(("mgs-hash/2 " + SHA + " 1\n")
                .getBytes(StandardCharsets.US_ASCII)));
        // 非十六进制摘要
        assertNull(RemoteHashes.parse(("mgs-hash/1 " + "z".repeat(64) + " 1\n")
                .getBytes(StandardCharsets.US_ASCII)));
        // 摘要长度不对
        assertNull(RemoteHashes.parse(("mgs-hash/1 " + SHA.substring(0, 63) + " 1\n")
                .getBytes(StandardCharsets.US_ASCII)));
        // 长度不是十进制
        assertNull(RemoteHashes.parse(("mgs-hash/1 " + SHA + " 12a\n")
                .getBytes(StandardCharsets.US_ASCII)));
        // 负长度
        assertNull(RemoteHashes.parse(("mgs-hash/1 " + SHA + " -1\n")
                .getBytes(StandardCharsets.US_ASCII)));
        // 多行
        assertNull(RemoteHashes.parse(("mgs-hash/1 " + SHA + " 1\nsecond\n")
                .getBytes(StandardCharsets.US_ASCII)));
    }

    /** 超过上限的内容直接不采信：旁车读到最后也不许把大文件当旁车。 */
    @Test
    public void oversizedPayloadReturnsNull() {
        byte[] huge = new byte[RemoteHashes.MAX_BYTES + 1];
        java.util.Arrays.fill(huge, (byte) 'a');
        assertNull(RemoteHashes.parse(huge));
    }

    // ------------------------------------------------------------ matches

    @Test
    public void matchesBoundaries() {
        RemoteHashes.Entry entry = new RemoteHashes.Entry(SHA, 16L);
        assertTrue(RemoteHashes.matches(entry, SHA, 16L));
        // 摘要大小写不同也接受：登记表里存在历史大写数据
        assertTrue(RemoteHashes.matches(entry, SHA.toUpperCase(Locale.US), 16L));
        // 长度不一致
        assertFalse(RemoteHashes.matches(entry, SHA, 15L));
        // 期望长度非法
        assertFalse(RemoteHashes.matches(entry, SHA, -1L));
        // 没有旁车
        assertFalse(RemoteHashes.matches(null, SHA, 16L));
        // 没有摘要
        assertFalse(RemoteHashes.matches(entry, null, 16L));
        // 0 字节对象：长度 0 是合法的相等值
        assertTrue(RemoteHashes.matches(new RemoteHashes.Entry(SHA, 0L), SHA, 0L));
        assertFalse(RemoteHashes.matches(new RemoteHashes.Entry(SHA, 0L), SHA, 1L));
    }

    // ------------------------------------------------------------ 读写入口

    /** tryWrite 落在算好的旁车路径上，readOrNull 能原样读回来。 */
    @Test
    public void writeThenReadUsesMappedPath() {
        FakeIo io = new FakeIo();
        RemoteHashes hashes = new RemoteHashes(ROOT, io);
        assertTrue(hashes.tryWrite("/MiGallerySync/2026/IMG_1.jpg", SHA, 4096L));
        String expected = ROOT + "/.mgs-hashes/2026/IMG_1.jpg.sha256";
        assertTrue(io.files.containsKey(expected));
        assertArrayEquals(RemoteHashes.format(SHA, 4096L).getBytes(StandardCharsets.US_ASCII),
                io.files.get(expected));
        RemoteHashes.Entry entry = hashes.readOrNull("/MiGallerySync/2026/IMG_1.jpg");
        assertNotNull(entry);
        assertEquals(SHA, entry.sha256);
        assertEquals(4096L, entry.length);
    }

    /** 没有旁车时 readOrNull 返回 null（调用方据此退回整份回读）。 */
    @Test
    public void readMissingReturnsNull() {
        FakeIo io = new FakeIo();
        assertNull(new RemoteHashes(ROOT, io).readOrNull("/MiGallerySync/none.jpg"));
        assertEquals(1, io.reads);
    }

    /** 旁车内容坏掉时也只返回 null，不把异常抛给校验流程。 */
    @Test
    public void readGarbageReturnsNull() {
        FakeIo io = new FakeIo();
        io.files.put(ROOT + "/.mgs-hashes/a.jpg.sha256", "not a sidecar".getBytes(
                StandardCharsets.US_ASCII));
        assertNull(new RemoteHashes(ROOT, io).readOrNull("/MiGallerySync/a.jpg"));
    }
}
