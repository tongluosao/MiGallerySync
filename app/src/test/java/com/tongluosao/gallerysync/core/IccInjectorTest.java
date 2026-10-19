package com.tongluosao.gallerysync.core;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;

import static com.tongluosao.gallerysync.core.IccProfileTest.ascii;
import static com.tongluosao.gallerysync.core.IccProfileTest.box;
import static com.tongluosao.gallerysync.core.IccProfileTest.concat;
import static com.tongluosao.gallerysync.core.IccProfileTest.heif;
import static com.tongluosao.gallerysync.core.IccProfileTest.jpegWithIcc;
import static com.tongluosao.gallerysync.core.IccProfileTest.pixels;
import static com.tongluosao.gallerysync.core.IccProfileTest.readU32;
import static com.tongluosao.gallerysync.core.IccProfileTest.srgbProfile;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** ICC 注入的纯 JVM 测试：JPEG 拆段、HEIF 插 colr + 修 iloc，以及失败时原文件不变。 */
public class IccInjectorTest {

    private File directory;

    @Before
    public void setUp() throws IOException {
        directory = Files.createTempDirectory("icc-injector").toFile();
    }

    @After
    public void tearDown() {
        IccProfileTest.delete(directory);
    }

    // ------------------------------------------------------------------ JPEG

    @Test
    public void jpegInjectionKeepsSoiAndStaysExtractable() throws IOException {
        byte[] icc = srgbProfile(0);
        // 模拟模块产出的无 ICC 预览。
        byte[] bare = concat(new byte[]{(byte) 0xff, (byte) 0xd8},
                IccProfileTest.boxSegment(0xe0, ascii("JFIF")),
                new byte[]{(byte) 0xff, (byte) 0xd9});
        File file = write("bare.jpg", bare);

        assertTrue(IccInjector.injectJpeg(file, icc));

        byte[] injected = Files.readAllBytes(file.toPath());
        assertEquals(0xff, injected[0] & 0xff);
        assertEquals(0xd8, injected[1] & 0xff);
        assertEquals(0xff, injected[2] & 0xff);
        assertEquals(0xe2, injected[3] & 0xff);
        assertArrayEquals(icc, IccProfile.extract(file));
        // 原有内容（APP0 + EOI）必须原样跟在后面。
        assertArrayEquals(Arrays.copyOfRange(bare, 2, bare.length),
                Arrays.copyOfRange(injected, injected.length - bare.length + 2,
                        injected.length));
    }

    @Test
    public void jpegInjectionSplitsLargeProfileIntoSegments() throws IOException {
        byte[] icc = Arrays.copyOf(srgbProfile(0), 70000);
        IccProfileTest.putU32(icc, 0, icc.length);
        File file = write("large.jpg", concat(new byte[]{(byte) 0xff, (byte) 0xd8},
                new byte[]{(byte) 0xff, (byte) 0xd9}));

        assertTrue(IccInjector.injectJpeg(file, icc));

        assertArrayEquals(icc, IccProfile.extract(file));
        // 70000 字节超过单个 APP2 的上限，必须拆成 2 段。
        assertEquals(2, app2Count(Files.readAllBytes(file.toPath())));
    }

    @Test
    public void jpegInjectionIsNoOpWhenProfileAlreadyPresent() throws IOException {
        byte[] icc = srgbProfile(0);
        File file = write("has-icc.jpg", jpegWithIcc(icc, icc.length));
        byte[] before = Files.readAllBytes(file.toPath());

        assertTrue(IccInjector.injectJpeg(file, icc));

        assertArrayEquals(before, Files.readAllBytes(file.toPath()));
    }

    @Test
    public void jpegInjectionFailureLeavesFileUntouched() throws IOException {
        byte[] icc = srgbProfile(0);
        byte[] notJpeg = ascii("this is not a jpeg file at all");
        File file = write("not-a-jpeg.jpg", notJpeg);

        assertFalse(IccInjector.injectJpeg(file, icc));
        assertArrayEquals(notJpeg, Files.readAllBytes(file.toPath()));

        byte[] bare = concat(new byte[]{(byte) 0xff, (byte) 0xd8},
                new byte[]{(byte) 0xff, (byte) 0xd9});
        File valid = write("valid.jpg", bare);
        assertFalse(IccInjector.injectJpeg(valid, new byte[16]));
        assertArrayEquals(bare, Files.readAllBytes(valid.toPath()));

        assertFalse(IccInjector.injectJpeg(null, icc));
        assertFalse(IccInjector.injectJpeg(valid, null));
    }

    // ------------------------------------------------------------------ HEIF

    @Test
    public void heifInjectionAddsColorProfileAndShiftsIloc() throws IOException {
        byte[] icc = srgbProfile(0);
        byte[] payload = pixels(48);
        IccProfileTest.Heif sample = heif(null, payload);
        File file = write("slim.heic", sample.bytes);
        int before = ilocExtentOffset(sample.bytes);
        assertEquals(sample.extentOffset, before);
        assertArrayEquals(payload, Arrays.copyOfRange(sample.bytes, before,
                before + payload.length));

        assertTrue(IccInjector.injectHeif(file, icc));
        assertTrue(IccInjector.verify(file));

        byte[] injected = Files.readAllBytes(file.toPath());
        // 插了 colr(12 + icc) 和 ipma 里多出的 1 个关联字节，mdat 整体后移这么多。
        int inserted = 12 + icc.length + 1;
        int after = ilocExtentOffset(injected);
        assertEquals(before + inserted, after);
        // 同一个 extent 的内容必须没变。
        assertArrayEquals(payload, Arrays.copyOfRange(injected, after, after + payload.length));
        // colr 里的 profile 能被重新提取出来。
        assertArrayEquals(icc, IccProfile.extract(file));
        assertTrue(IccInjector.verify(file));
    }

    @Test
    public void heifInjectionKeepsBoxSizeChainConsistent() throws IOException {
        byte[] icc = Arrays.copyOf(srgbProfile(0), 3144);
        IccProfileTest.putU32(icc, 0, icc.length);
        IccProfileTest.Heif sample = heif(null, pixels(64));
        File file = write("chain.heic", sample.bytes);

        assertTrue(IccInjector.injectHeif(file, icc));

        byte[] injected = Files.readAllBytes(file.toPath());
        int metaOffset = boxSize(injected, 0);
        assertEquals("meta", IccProfileTest.fourCc(injected, metaOffset + 4));
        int metaSize = boxSize(injected, metaOffset);
        int mdatOffset = metaOffset + metaSize;
        assertEquals("mdat", IccProfileTest.fourCc(injected, mdatOffset + 4));
        assertEquals(injected.length, mdatOffset + boxSize(injected, mdatOffset));
        assertTrue(IccInjector.verify(file));
    }

    @Test
    public void heifInjectionIsNoOpWhenProfileAlreadyPresent() throws IOException {
        byte[] icc = srgbProfile(0);
        IccProfileTest.Heif sample = heif(icc, pixels(16));
        File file = write("already.heic", sample.bytes);
        byte[] before = Files.readAllBytes(file.toPath());

        assertTrue(IccInjector.injectHeif(file, icc));

        assertArrayEquals(before, Files.readAllBytes(file.toPath()));
    }

    @Test
    public void heifInjectionFailureLeavesFileUntouched() throws IOException {
        byte[] icc = srgbProfile(0);
        // 没有 meta：无处插 colr。
        byte[] noMeta = concat(box("ftyp", null, concat(ascii("heic"), new byte[4])),
                box("mdat", null, pixels(16)));
        File file = write("no-meta.heic", noMeta);

        assertFalse(IccInjector.injectHeif(file, icc));
        assertArrayEquals(noMeta, Files.readAllBytes(file.toPath()));

        IccProfileTest.Heif sample = heif(null, pixels(16));
        File valid = write("valid.heic", sample.bytes);
        assertFalse(IccInjector.injectHeif(valid, new byte[16]));
        assertArrayEquals(sample.bytes, Files.readAllBytes(valid.toPath()));
        assertFalse(IccInjector.injectHeif(valid, null));
        assertFalse(IccInjector.injectHeif(null, icc));
    }

    @Test
    public void verifyRejectsTruncatedAndForeignFiles() throws IOException {
        byte[] icc = srgbProfile(0);
        IccProfileTest.Heif sample = heif(null, pixels(32));
        File file = write("truncated.heic", sample.bytes);
        assertTrue(IccInjector.injectHeif(file, icc));

        byte[] injected = Files.readAllBytes(file.toPath());
        File truncated = write("truncated2.heic",
                Arrays.copyOf(injected, injected.length - 4));
        assertFalse(IccInjector.verify(truncated));

        File foreign = write("foreign.bin", ascii("definitely not a heif file"));
        assertFalse(IccInjector.verify(foreign));
        assertFalse(IccInjector.verify(null));
    }

    // ------------------------------------------------------------------ 工具

    private File write(String name, byte[] content) throws IOException {
        File file = new File(directory, name);
        Files.write(file.toPath(), content);
        return file;
    }

    private static int app2Count(byte[] file) {
        int count = 0;
        int cursor = 2;
        while (cursor + 4 <= file.length && (file[cursor] & 0xff) == 0xff
                && (file[cursor + 1] & 0xff) == 0xe2) {
            int length = ((file[cursor + 2] & 0xff) << 8) | (file[cursor + 3] & 0xff);
            count++;
            cursor += 2 + length;
        }
        return count;
    }

    private static int ilocExtentOffset(byte[] file) {
        byte[] needle = ascii("iloc");
        for (int start = 4; start + 4 <= file.length; start++) {
            boolean match = true;
            for (int index = 0; index < 4; index++) {
                if (file[start + index] != needle[index]) {
                    match = false;
                    break;
                }
            }
            if (match && readU32(file, start - 4) == 32L) {
                // item_ID(2) + method(2) + data_ref(2) + extent_count(2) 之后是 extent_offset。
                return (int) readU32(file, start + 20);
            }
        }
        throw new AssertionError("iloc not found");
    }

    private static int boxSize(byte[] file, int offset) {
        return (int) readU32(file, offset);
    }
}
