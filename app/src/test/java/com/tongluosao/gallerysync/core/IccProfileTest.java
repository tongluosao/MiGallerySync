package com.tongluosao.gallerysync.core;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * ICC 提取与 sRGB 判定的纯 JVM 测试。所有样本都在这里现造（最小 JPEG / 最小 HEIF / 最小 ICC），
 * 不依赖 Android 运行环境和真实照片。
 */
public class IccProfileTest {

    /** 标准 sRGB v2 profile 的 rXYZ/gXYZ/bXYZ（已按 ICC 规范做 D50 适配）。 */
    static final double[][] SRGB_PRIMARIES = {
            {0.43604, 0.22249, 0.01392},
            {0.38513, 0.71686, 0.09717},
            {0.14305, 0.06061, 0.71393}
    };
    static final double[] SRGB_WHITE_D65 = {0.95045, 1.0, 1.08905};
    /** Adobe-RGB 的绿原色与 sRGB 差得很远（红/蓝几乎一样），用来验证"部分命中不算命中"。 */
    static final double[][] ADOBE_RGB_PRIMARIES = {
            {0.60974, 0.31111, 0.01947},
            {0.20528, 0.62567, 0.06087},
            {0.14919, 0.06322, 0.74457}
    };
    static final double[] PCS_WHITE_D50 = {0.96420, 1.0, 0.82491};

    private File directory;

    @Before
    public void setUp() throws IOException {
        directory = Files.createTempDirectory("icc-profile").toFile();
    }

    @After
    public void tearDown() {
        delete(directory);
    }

    // ---------------------------------------------------------------- JPEG

    @Test
    public void extractsSingleSegmentApp2() throws IOException {
        byte[] icc = srgbProfile(0);
        File file = write("single.jpg", jpegWithIcc(icc, icc.length));

        assertArrayEquals(icc, IccProfile.extract(file));
    }

    @Test
    public void extractsMultiSegmentApp2InSequenceOrder() throws IOException {
        byte[] icc = srgbProfile(0);
        // 顺序故意反过来：必须按段里的序号拼，而不是按文件顺序。
        File file = write("multi.jpg", jpegWithSegments(icc, 64, true));

        assertArrayEquals(icc, IccProfile.extract(file));
    }

    @Test
    public void rejectsApp2WithMissingSegment() throws IOException {
        byte[] icc = srgbProfile(0);
        int chunk = 64;
        byte[][] segments = app2Segments(icc, chunk);
        byte[] file = concat(new byte[]{(byte) 0xff, (byte) 0xd8}, segments[0],
                new byte[]{(byte) 0xff, (byte) 0xd9});

        assertNull(IccProfile.extract(write("missing.jpg", file)));
    }

    @Test
    public void rejectsIccWhoseDeclaredLengthIsTooLarge() throws IOException {
        byte[] icc = srgbProfile(0);
        // 把声明长度改大：提取和判定都应该拒绝。
        byte[] broken = icc.clone();
        putU32(broken, 0, broken.length + 1024L);

        assertFalse(IccProfile.isSrgbCompatible(broken));
        assertNull(IccProfile.extract(write("bad-length.jpg", jpegWithIcc(broken,
                broken.length))));
    }

    @Test
    public void returnsNullForJpegWithoutIcc() throws IOException {
        byte[] file = concat(new byte[]{(byte) 0xff, (byte) 0xd8},
                boxSegment(0xe0, ascii("JFIF")),
                new byte[]{(byte) 0xff, (byte) 0xd9});

        assertNull(IccProfile.extract(write("no-icc.jpg", file)));
    }

    @Test
    public void returnsNullForFilesThatAreNeitherJpegNorHeif() throws IOException {
        assertNull(IccProfile.extract(write("garbage.bin", new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9,
                10, 11, 12, 13, 14, 15, 16})));
        assertNull(IccProfile.extract(null));
        assertNull(IccProfile.extract(new File(directory, "absent.jpg")));
    }

    // ---------------------------------------------------------------- 判定

    @Test
    public void acceptsStandardSrgbProfile() {
        byte[] icc = srgbProfile(0);

        assertTrue(IccProfile.describe(icc), IccProfile.isSrgbCompatible(icc));
    }

    @Test
    public void acceptsSrgbProfileWithLargeDeclaredSize() {
        // 真实 profile 常在 tag 后面留填充，声明长度可能大于 tag 区本身。
        assertTrue(IccProfile.isSrgbCompatible(srgbProfile(65_536)));
    }

    @Test
    public void rejectsAdobeRgbProfile() {
        byte[] icc = profile(ADOBE_RGB_PRIMARIES, PCS_WHITE_D50, 0);

        assertFalse(IccProfile.isSrgbCompatible(icc));
    }

    @Test
    public void rejectsProfileWithoutColorPrimaries() {
        assertFalse(IccProfile.isSrgbCompatible(null));
        assertFalse(IccProfile.isSrgbCompatible(new byte[0]));
        assertFalse(IccProfile.isSrgbCompatible(new byte[196]));
        assertFalse(IccProfile.isSrgbCompatible(profileWithoutTag("rXYZ")));
        assertFalse(IccProfile.isSrgbCompatible(profileWithoutTag("wtpt")));
    }

    @Test
    public void rejectsIccWithBrokenSignatureOrLength() {
        byte[] noSignature = srgbProfile(0);
        noSignature[36] = 'x';
        assertFalse(IccProfile.isSrgbCompatible(noSignature));

        byte[] tooShort = Arrays.copyOf(srgbProfile(0), 100);
        assertFalse(IccProfile.isSrgbCompatible(tooShort));

        byte[] notRgb = srgbProfile(0);
        setAscii(notRgb, 16, "CMYK");
        assertFalse(IccProfile.isSrgbCompatible(notRgb));
    }

    @Test
    public void describeNeverThrows() {
        assertNotNull(IccProfile.describe(null));
        assertNotNull(IccProfile.describe(srgbProfile(0)));
        assertEquals("icc(none)", IccProfile.describe(null));
    }

    // ---------------------------------------------------------------- HEIF

    @Test
    public void extractsColorProfileFromIpco() throws IOException {
        byte[] icc = srgbProfile(0);
        Heif heif = heif(icc, pixels(32));
        File file = write("with-colr.heic", heif.bytes);

        assertArrayEquals(icc, IccProfile.extract(file));
    }

    @Test
    public void ignoresNonProfColorType() throws IOException {
        byte[] nclx = box("colr", null, concat(ascii("nclx"),
                new byte[]{0, 1, 0, 1, 0, 1, (byte) 0x80}));
        Heif heif = heifWithProperties(nclx, null);
        assertNull(IccProfile.extract(write("nclx.heic", heif.bytes)));
    }

    @Test
    public void returnsNullForHeifWithoutColorProfile() throws IOException {
        assertNull(IccProfile.extract(write("plain.heic", heif(null, pixels(16)).bytes)));
    }

    // ---------------------------------------------------------------- 样本构造

    /** sRGB profile；{@code declaredSize} 大于 0 时声明成那个长度（用于模拟尾部填充）。 */
    static byte[] srgbProfile(int declaredSize) {
        return profile(SRGB_PRIMARIES, SRGB_WHITE_D65, declaredSize);
    }

    /** 造一个 tag 表结构完整、但少了某个 tag 的 profile。 */
    static byte[] profileWithoutTag(String missing) {
        byte[] full = srgbProfile(0);
        int count = (int) readU32(full, 128);
        byte[] reduced = new byte[full.length];
        System.arraycopy(full, 0, reduced, 0, 132);
        int kept = 0;
        for (int index = 0; index < count; index++) {
            int entry = 132 + index * 12;
            if (missing.equals(fourCc(full, entry))) {
                continue;
            }
            System.arraycopy(full, entry, reduced, 132 + kept * 12, 12);
            kept++;
        }
        putU32(reduced, 128, kept);
        return reduced;
    }

    /**
     * 造一个最小可用 ICC：128 字节头 + tag 表（wtpt/rXYZ/gXYZ/bXYZ）+ 4 个 {@code XYZ } 数据块。
     */
    static byte[] profile(double[][] primaries, double[] white, int declaredSize) {
        String[] signatures = {"wtpt", "rXYZ", "gXYZ", "bXYZ"};
        double[][] values = {white, primaries[0], primaries[1], primaries[2]};
        int tagTableEnd = 128 + 4 + signatures.length * 12;
        int core = tagTableEnd + signatures.length * 20;
        int size = declaredSize > core ? declaredSize : core;
        byte[] icc = new byte[size];
        putU32(icc, 0, size);
        setAscii(icc, 4, "Lino");
        icc[8] = 0x02;
        icc[9] = 0x10;
        setAscii(icc, 12, "mntr");
        setAscii(icc, 16, "RGB ");
        setAscii(icc, 20, "XYZ ");
        setAscii(icc, 36, "acsp");
        putU32(icc, 128, signatures.length);
        for (int index = 0; index < signatures.length; index++) {
            int entry = 132 + index * 12;
            int data = tagTableEnd + index * 20;
            setAscii(icc, entry, signatures[index]);
            putU32(icc, entry + 4, data);
            putU32(icc, entry + 8, 20);
            setAscii(icc, data, "XYZ ");
            for (int component = 0; component < 3; component++) {
                putU32(icc, data + 8 + component * 4,
                        Math.round(values[index][component] * 65536.0d));
            }
        }
        return icc;
    }

    static byte[] jpegWithIcc(byte[] icc, int chunk) {
        return jpegWithSegments(icc, chunk, false);
    }

    static byte[] jpegWithSegments(byte[] icc, int chunk, boolean reverse) {
        byte[][] segments = app2Segments(icc, chunk);
        if (reverse) {
            for (int left = 0, right = segments.length - 1; left < right; left++, right--) {
                byte[] swap = segments[left];
                segments[left] = segments[right];
                segments[right] = swap;
            }
        }
        byte[] body = new byte[0];
        for (byte[] segment : segments) {
            body = concat(body, segment);
        }
        return concat(new byte[]{(byte) 0xff, (byte) 0xd8}, body,
                boxSegment(0xe0, ascii("JFIF")),
                new byte[]{(byte) 0xff, (byte) 0xd9});
    }

    static byte[][] app2Segments(byte[] icc, int chunk) {
        int count = (icc.length + chunk - 1) / chunk;
        byte[][] segments = new byte[count][];
        int offset = 0;
        for (int index = 0; index < count; index++) {
            int length = Math.min(chunk, icc.length - offset);
            byte[] payload = concat(ascii("ICC_PROFILE"), new byte[]{0},
                    new byte[]{(byte) (index + 1), (byte) count},
                    Arrays.copyOfRange(icc, offset, offset + length));
            segments[index] = boxSegment(0xe2, payload);
            offset += length;
        }
        return segments;
    }

    static byte[] boxSegment(int marker, byte[] payload) {
        return concat(new byte[]{(byte) 0xff, (byte) marker,
                (byte) ((payload.length + 2) >> 8), (byte) (payload.length + 2)}, payload);
    }

    static final class Heif {
        final byte[] bytes;
        final int extentOffset;
        final int extentLength;

        Heif(byte[] bytes, int extentOffset, int extentLength) {
            this.bytes = bytes;
            this.extentOffset = extentOffset;
            this.extentLength = extentLength;
        }
    }

    /** 最小 HEIF：ftyp + meta{hdlr,pitm,iloc,iinf,iprp{ipco{hvcC,ispe[,colr]},ipma}} + mdat。 */
    static Heif heif(byte[] icc, byte[] pixelData) {
        byte[] colr = icc == null ? null
                : box("colr", null, concat(ascii("prof"), icc));
        return heifWithProperties(colr, pixelData);
    }

    static Heif heifWithProperties(byte[] extraProperty, byte[] pixelData) {
        byte[] hvcC = box("hvcC", null, new byte[]{1, 2, 3, 4});
        byte[] ispe = box("ispe", new byte[4], concat(u32(64), u32(48)));
        byte[] ipcoPayload = extraProperty == null
                ? concat(hvcC, ispe)
                : concat(hvcC, ispe, extraProperty);
        byte[] ipco = box("ipco", null, ipcoPayload);
        int propertyCount = extraProperty == null ? 2 : 3;
        byte[] associations = new byte[propertyCount];
        for (int index = 0; index < propertyCount; index++) {
            // 与真实文件一致：essential 位 + 1 字节属性索引。
            associations[index] = (byte) (0x80 | (index + 1));
        }
        // version 0 的 ipma：entry_count(u32) + 每个条目 item_ID(u16) + association_count(u8) + 关联。
        byte[] ipma = box("ipma", new byte[4],
                concat(u32(1), u16(1), new byte[]{(byte) propertyCount}, associations));
        byte[] iprp = box("iprp", null, concat(ipco, ipma));
        byte[] hdlr = box("hdlr", new byte[4], concat(ascii("pict"), new byte[16]));
        byte[] pitm = box("pitm", new byte[4], u16(1));
        byte[] iinf = box("iinf", new byte[4], u16(0));
        byte[] media = pixelData == null ? new byte[0] : pixelData;

        byte[] placeholder = iloc(0, media.length);
        int metaSize = 12 + hdlr.length + pitm.length + placeholder.length + iinf.length
                + iprp.length;
        int extentOffset = 16 + metaSize + 8;
        byte[] iloc = iloc(extentOffset, media.length);
        byte[] meta = box("meta", new byte[4],
                concat(hdlr, pitm, iloc, iinf, iprp));
        byte[] ftyp = box("ftyp", null, concat(ascii("heic"), new byte[4]));
        byte[] mdat = box("mdat", null, media);
        return new Heif(concat(ftyp, meta, mdat), extentOffset, media.length);
    }

    static byte[] iloc(int extentOffset, int extentLength) {
        return box("iloc", new byte[]{1, 0, 0, 0},
                concat(new byte[]{0x44, 0x00}, u16(1),
                        u16(1),                 // item_ID
                        u16(0),                 // reserved + construction_method
                        u16(0),                 // data_reference_index
                        u16(1),                 // extent_count
                        u32(extentOffset),
                        u32(extentLength)));
    }

    static byte[] pixels(int length) {
        byte[] data = new byte[length];
        for (int index = 0; index < length; index++) {
            data[index] = (byte) ((index * 31 + 7) & 0xff);
        }
        return data;
    }

    // ---------------------------------------------------------------- 工具

    File write(String name, byte[] content) throws IOException {
        File file = new File(directory, name);
        Files.write(file.toPath(), content);
        return file;
    }

    static byte[] box(String type, byte[] fullBox, byte[] payload) {
        int prefix = fullBox == null ? 0 : 4;
        byte[] out = new byte[8 + prefix + payload.length];
        putU32(out, 0, out.length);
        setAscii(out, 4, type);
        if (fullBox != null) {
            System.arraycopy(fullBox, 0, out, 8, 4);
        }
        System.arraycopy(payload, 0, out, 8 + prefix, payload.length);
        return out;
    }

    static byte[] ascii(String value) {
        byte[] out = new byte[value.length()];
        for (int index = 0; index < value.length(); index++) {
            out[index] = (byte) value.charAt(index);
        }
        return out;
    }

    static void setAscii(byte[] target, int offset, String value) {
        for (int index = 0; index < value.length(); index++) {
            target[offset + index] = (byte) value.charAt(index);
        }
    }

    static String fourCc(byte[] data, int offset) {
        StringBuilder out = new StringBuilder(4);
        for (int index = 0; index < 4; index++) {
            out.append((char) (data[offset + index] & 0xff));
        }
        return out.toString();
    }

    static byte[] concat(byte[]... parts) {
        int size = 0;
        for (byte[] part : parts) {
            size += part.length;
        }
        byte[] out = new byte[size];
        int cursor = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, cursor, part.length);
            cursor += part.length;
        }
        return out;
    }

    static byte[] u16(int value) {
        return new byte[]{(byte) ((value >> 8) & 0xff), (byte) (value & 0xff)};
    }

    static byte[] u32(long value) {
        return new byte[]{(byte) ((value >> 24) & 0xff), (byte) ((value >> 16) & 0xff),
                (byte) ((value >> 8) & 0xff), (byte) (value & 0xff)};
    }

    static void putU32(byte[] target, int offset, long value) {
        target[offset] = (byte) ((value >> 24) & 0xff);
        target[offset + 1] = (byte) ((value >> 16) & 0xff);
        target[offset + 2] = (byte) ((value >> 8) & 0xff);
        target[offset + 3] = (byte) (value & 0xff);
    }

    static long readU32(byte[] data, int offset) {
        return ((data[offset] & 0xffL) << 24) | ((data[offset + 1] & 0xffL) << 16)
                | ((data[offset + 2] & 0xffL) << 8) | (data[offset + 3] & 0xffL);
    }

    static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int index = 0; index + needle.length <= haystack.length; index++) {
            for (int position = 0; position < needle.length; position++) {
                if (haystack[index + position] != needle[position]) {
                    continue outer;
                }
            }
            return index;
        }
        return -1;
    }

    static void delete(File file) {
        if (file == null) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                delete(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
