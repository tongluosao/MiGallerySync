package com.tongluosao.gallerysync.core;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 从源文件里取出 ICC 色彩配置，并判断它是否描述 sRGB 色彩空间。
 *
 * <p>JPEG 的 ICC 在 APP2 段里（标识 {@code ICC_PROFILE\0} + 序号 + 段数 + 数据，可能多段），
 * HEIF 的 ICC 在 {@code meta/iprp/ipco/colr} 里（{@code colour_type == 'prof'}，payload 就是 ICC 字节）。
 *
 * <p>判定 sRGB 要读 ICC 头后的 tag 表里的 {@code rXYZ}/{@code gXYZ}/{@code bXYZ}/{@code wtpt}
 * （{@code XYZ } 类型，s15Fixed16），换算成 xy 色度再和 sRGB 原色比较。注意 ICC 的 PCS 是
 * <b>D50 适配</b>的：标准 sRGB profile（例如 sRGB IEC61966-2.1）里的三原色列是 D65 原色经
 * Bradford 适配到 D50 的结果，其 xy 与"原色本身"能差到 0.02 以上（绿原色 x 约 0.321 对 0.300）。
 * 所以这里对"已适配"和"未适配"两种存法都做判断：只要有一种落在容差内就算兼容。这样既
 * 认得出标准 sRGB profile，也不会把 Display-P3 / Adobe-RGB / Rec.2020 误判成 sRGB。
 */
public final class IccProfile {

    private static final byte[] ICC_IDENTIFIER = {
            'I', 'C', 'C', '_', 'P', 'R', 'O', 'F', 'I', 'L', 'E', 0
    };
    private static final int HEADER_SIZE = 128;
    private static final int TAG_TABLE_OFFSET = 128;
    private static final int TAG_ENTRY_SIZE = 12;
    private static final int MAX_TAGS = 4096;
    private static final int MAX_BOX_DEPTH = 12;
    private static final int MIN_ICC_LENGTH = HEADER_SIZE + 4;

    private static final double[] SRGB_RED = {0.640, 0.330};
    private static final double[] SRGB_GREEN = {0.300, 0.600};
    private static final double[] SRGB_BLUE = {0.150, 0.060};
    private static final double[] SRGB_WHITE_D65 = {0.3127, 0.3290};
    /** ICC 的 PCS 白点（D50）；v4 profile 常把媒体白点直接存成它。 */
    private static final double[] PCS_WHITE_D50 = {0.3457, 0.3585};
    private static final double CHROMATICITY_TOLERANCE = 0.02;

    /** Bradford 色适应 D50 -> D65（与 lcms 一致的锥体响应矩阵）。 */
    private static final double[][] D50_TO_D65 = {
            {0.9555526, -0.0230522, 0.0632543},
            {-0.0282989, 1.0099332, 0.0210361},
            {0.0123181, -0.0205178, 1.3304287}
    };

    private IccProfile() {
    }

    /**
     * 取出源文件内嵌的 ICC。JPEG 走 APP2 多段拼接，HEIF 走 {@code colr} box；
     * 其它格式、缺失、或长度/签名不自洽时返回 null。
     */
    public static byte[] extract(File source) {
        if (source == null || !source.isFile() || source.length() < 12L) {
            return null;
        }
        try (RandomAccessFile file = new RandomAccessFile(source, "r")) {
            long length = file.length();
            byte[] head = new byte[12];
            file.seek(0L);
            file.readFully(head);
            if ((head[0] & 0xff) == 0xff && (head[1] & 0xff) == 0xd8) {
                return extractJpeg(file, length);
            }
            if (isFtyp(head)) {
                return extractHeif(file, length);
            }
            return null;
        } catch (Throwable ignored) {
            // 任何解析异常都等于"没有可用的 ICC"，调用方只需跳过注入。
            return null;
        }
    }

    /**
     * ICC 的 tag 表是否声明 sRGB 原色与白点。缺 tag、长度不符、签名不对一律 false。
     */
    public static boolean isSrgbCompatible(byte[] icc) {
        if (!isValid(icc)) {
            return false;
        }
        long declaredSize = u32(icc, 0);
        if (!"RGB ".equals(fourCc(icc, 16))) {
            return false;
        }
        long tagCount = u32(icc, TAG_TABLE_OFFSET);
        if (tagCount <= 0L || tagCount > MAX_TAGS
                || TAG_TABLE_OFFSET + 4L + tagCount * TAG_ENTRY_SIZE > declaredSize) {
            return false;
        }
        double[][] primaries = new double[3][];
        double[] white = null;
        for (int index = 0; index < (int) tagCount; index++) {
            int entry = TAG_TABLE_OFFSET + 4 + index * TAG_ENTRY_SIZE;
            String signature = fourCc(icc, entry);
            int slot;
            switch (signature) {
                case "rXYZ":
                    slot = 0;
                    break;
                case "gXYZ":
                    slot = 1;
                    break;
                case "bXYZ":
                    slot = 2;
                    break;
                case "wtpt":
                    slot = 3;
                    break;
                default:
                    continue;
            }
            double[] xyz = readXyz(icc, u32(icc, entry + 4), u32(icc, entry + 8), declaredSize);
            if (xyz == null) {
                return false;
            }
            if (slot == 3) {
                white = xyz;
            } else {
                primaries[slot] = xyz;
            }
        }
        if (white == null || primaries[0] == null || primaries[1] == null
                || primaries[2] == null) {
            return false;
        }
        double[] whiteXy = toXy(white);
        if (whiteXy == null
                || (!within(whiteXy, SRGB_WHITE_D65) && !within(whiteXy, PCS_WHITE_D50))) {
            return false;
        }
        double[][] unadapted = new double[3][];
        for (int index = 0; index < 3; index++) {
            unadapted[index] = multiply(D50_TO_D65, primaries[index]);
        }
        // 存的是 D65 原色 -> 直接比；存的是 D50 适配过的列 -> 先反适配再比。
        return primariesMatch(primaries) || primariesMatch(unadapted);
    }

    /** 只用于日志的简短描述：版本、设备色彩空间、声明长度、是否 sRGB。 */
    public static String describe(byte[] icc) {
        if (icc == null || icc.length < MIN_ICC_LENGTH) {
            return "icc(none)";
        }
        StringBuilder out = new StringBuilder(64);
        out.append("icc(len=").append(icc.length)
                .append(",declared=").append(u32(icc, 0))
                .append(",version=").append(icc[8] & 0xff).append('.').append(icc[9] & 0xff)
                .append(",class=").append(fourCc(icc, 12))
                .append(",space=").append(fourCc(icc, 16))
                .append(",srgb=").append(isSrgbCompatible(icc));
        String description = tagText(icc, "desc");
        if (description != null && !description.isEmpty()) {
            out.append(",desc=").append(description);
        }
        return out.append(')').toString();
    }

    /** ICC 头自洽：声明长度不越界、签名是 {@code acsp}。 */
    static boolean isValid(byte[] icc) {
        if (icc == null || icc.length < MIN_ICC_LENGTH) {
            return false;
        }
        long declaredSize = u32(icc, 0);
        if (declaredSize < MIN_ICC_LENGTH || declaredSize > icc.length) {
            return false;
        }
        return icc[36] == 'a' && icc[37] == 'c' && icc[38] == 's' && icc[39] == 'p';
    }

    // ------------------------------------------------------------------ JPEG

    private static byte[] extractJpeg(RandomAccessFile file, long length) throws IOException {
        List<byte[]> chunks = new ArrayList<>();
        int[] sequences = new int[256];
        int[] totals = new int[256];
        int seen = 0;
        long cursor = 2L;
        while (cursor + 1L < length) {
            file.seek(cursor);
            int lead = file.read();
            if (lead != 0xff) {
                // 段与段之间的垃圾字节：按字节重同步，不影响后面的正确段。
                cursor++;
                continue;
            }
            int marker = file.read();
            if (marker < 0) {
                break;
            }
            if (marker == 0xff) {
                // 0xFF 填充字节：前移一格，让后一个 0xFF 成为新的标记起点。
                cursor++;
                continue;
            }
            if (marker == 0xd8 || marker == 0x01 || (marker >= 0xd0 && marker <= 0xd7)) {
                cursor += 2L;
                continue;
            }
            if (marker == 0xda || marker == 0xd9) {
                // 进入熵编码数据后不再有元数据段。
                break;
            }
            if (cursor + 4L > length) {
                break;
            }
            file.seek(cursor + 2L);
            int high = file.read();
            int low = file.read();
            if (high < 0 || low < 0) {
                break;
            }
            int segmentLength = ((high & 0xff) << 8) | (low & 0xff);
            if (segmentLength < 2 || cursor + 2L + segmentLength > length) {
                break;
            }
            int payloadOffset = (int) (cursor + 4L);
            int payloadLength = segmentLength - 2;
            if (marker == 0xe2 && payloadLength > ICC_IDENTIFIER.length + 1) {
                byte[] payload = new byte[payloadLength];
                file.seek(payloadOffset);
                file.readFully(payload);
                if (startsWith(payload, ICC_IDENTIFIER)) {
                    int sequence = payload[ICC_IDENTIFIER.length] & 0xff;
                    int total = payload[ICC_IDENTIFIER.length + 1] & 0xff;
                    if (sequence < 1 || total < 1 || sequence > total || seen >= 255) {
                        return null;
                    }
                    sequences[seen] = sequence;
                    totals[seen] = total;
                    chunks.add(copyOfRange(payload, ICC_IDENTIFIER.length + 2,
                            payloadLength));
                    seen++;
                }
            }
            cursor += 2L + segmentLength;
        }
        if (seen == 0) {
            return null;
        }
        int total = totals[0];
        for (int index = 0; index < seen; index++) {
            if (totals[index] != total) {
                return null;
            }
        }
        if (total != seen) {
            // 段缺失、重复或序号非法：宁可当没有，也不要拼出一段坏 profile。
            return null;
        }
        byte[][] ordered = new byte[total][];
        for (int index = 0; index < seen; index++) {
            int slot = sequences[index] - 1;
            if (ordered[slot] != null) {
                return null;
            }
            ordered[slot] = chunks.get(index);
        }
        int size = 0;
        for (byte[] chunk : ordered) {
            if (chunk == null) {
                return null;
            }
            size += chunk.length;
        }
        byte[] icc = new byte[size];
        int offset = 0;
        for (byte[] chunk : ordered) {
            System.arraycopy(chunk, 0, icc, offset, chunk.length);
            offset += chunk.length;
        }
        return isValid(icc) ? icc : null;
    }

    // ------------------------------------------------------------------ HEIF

    private static byte[] extractHeif(RandomAccessFile file, long length) throws IOException {
        return findColorProfile(file, 0L, length, 0);
    }

    private static byte[] findColorProfile(RandomAccessFile file, long start, long end,
            int depth) throws IOException {
        if (depth > MAX_BOX_DEPTH) {
            return null;
        }
        byte[] header = new byte[16];
        long cursor = start;
        while (cursor + 8L <= end) {
            file.seek(cursor);
            file.readFully(header, 0, 8);
            long size = u32(header, 0);
            String type = fourCc(header, 4);
            int headerSize = 8;
            if (size == 1L) {
                if (cursor + 16L > end) {
                    return null;
                }
                file.readFully(header, 8, 8);
                size = u64(header, 8);
                headerSize = 16;
            } else if (size == 0L) {
                size = end - cursor;
            }
            if (size < headerSize || cursor + size > end) {
                return null;
            }
            long bodyStart = cursor + headerSize;
            long boxEnd = cursor + size;
            if ("colr".equals(type)) {
                if (size >= 12L) {
                    file.seek(bodyStart);
                    byte[] colorType = new byte[4];
                    file.readFully(colorType);
                    if (colorType[0] == 'p' && colorType[1] == 'r'
                            && colorType[2] == 'o' && colorType[3] == 'f') {
                        int payload = (int) (size - 12L);
                        if (payload <= 0) {
                            return null;
                        }
                        byte[] icc = new byte[payload];
                        file.readFully(icc);
                        return isValid(icc) ? icc : null;
                    }
                }
            } else if (isContainer(type)) {
                long childStart = bodyStart;
                if ("meta".equals(type)) {
                    if (size < headerSize + 4L) {
                        return null;
                    }
                    childStart += 4L;
                }
                byte[] found = findColorProfile(file, childStart, boxEnd, depth + 1);
                if (found != null) {
                    return found;
                }
            }
            cursor = boxEnd;
        }
        return null;
    }

    private static boolean isContainer(String type) {
        return "meta".equals(type) || "iprp".equals(type) || "ipco".equals(type);
    }

    private static boolean isFtyp(byte[] head) {
        return head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p';
    }

    // ------------------------------------------------------------------ 工具

    private static double[] readXyz(byte[] icc, long offset, long size, long declaredSize) {
        if (size < 20L || offset < 0L || offset + 20L > declaredSize || offset + 20L > icc.length) {
            return null;
        }
        if (!"XYZ ".equals(fourCc(icc, (int) offset))) {
            return null;
        }
        int base = (int) offset + 8;
        double[] xyz = new double[3];
        for (int index = 0; index < 3; index++) {
            xyz[index] = s15Fixed16(icc, base + index * 4);
        }
        double sum = xyz[0] + xyz[1] + xyz[2];
        if (!(sum > 0.0d) || !isFinite(xyz)) {
            return null;
        }
        return xyz;
    }

    private static boolean primariesMatch(double[][] xyz) {
        double[][] targets = {SRGB_RED, SRGB_GREEN, SRGB_BLUE};
        for (int index = 0; index < 3; index++) {
            double[] xy = toXy(xyz[index]);
            if (xy == null || !within(xy, targets[index])) {
                return false;
            }
        }
        return true;
    }

    private static double[] toXy(double[] xyz) {
        double sum = xyz[0] + xyz[1] + xyz[2];
        if (!(sum > 0.0d) || !isFinite(xyz)) {
            return null;
        }
        return new double[]{xyz[0] / sum, xyz[1] / sum};
    }

    private static double[] multiply(double[][] matrix, double[] vector) {
        double[] result = new double[3];
        for (int row = 0; row < 3; row++) {
            result[row] = matrix[row][0] * vector[0] + matrix[row][1] * vector[1]
                    + matrix[row][2] * vector[2];
        }
        return result;
    }

    private static boolean within(double[] actual, double[] expected) {
        return Math.abs(actual[0] - expected[0]) <= CHROMATICITY_TOLERANCE
                && Math.abs(actual[1] - expected[1]) <= CHROMATICITY_TOLERANCE;
    }

    private static boolean isFinite(double[] values) {
        for (double value : values) {
            if (!Double.isFinite(value)) {
                return false;
            }
        }
        return true;
    }

    private static double s15Fixed16(byte[] data, int offset) {
        return (int) u32(data, offset) / 65536.0d;
    }

    private static String tagText(byte[] icc, String wanted) {
        if (!isValid(icc)) {
            return null;
        }
        long declaredSize = u32(icc, 0);
        long tagCount = u32(icc, TAG_TABLE_OFFSET);
        if (tagCount <= 0L || tagCount > MAX_TAGS) {
            return null;
        }
        for (int index = 0; index < (int) tagCount; index++) {
            int entry = TAG_TABLE_OFFSET + 4 + index * TAG_ENTRY_SIZE;
            if (!wanted.equals(fourCc(icc, entry))) {
                continue;
            }
            long offset = u32(icc, entry + 4);
            long size = u32(icc, entry + 8);
            if (size < 12L || offset < 0L || offset + 12L > declaredSize
                    || offset + 12L > icc.length) {
                return null;
            }
            if (!"desc".equals(fourCc(icc, (int) offset))) {
                return null;
            }
            long textSize = u32(icc, (int) offset + 8);
            int start = (int) offset + 12;
            int available = (int) Math.min(Math.min(textSize, declaredSize - start),
                    icc.length - start);
            if (available <= 0) {
                return null;
            }
            StringBuilder out = new StringBuilder(available);
            for (int position = 0; position < available; position++) {
                byte value = icc[start + position];
                if (value == 0) {
                    break;
                }
                if (value >= 0x20 && value < 0x7f) {
                    out.append((char) value);
                }
            }
            return out.toString().trim();
        }
        return null;
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if (data[index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private static byte[] copyOfRange(byte[] source, int from, int to) {
        int size = to - from;
        if (size < 0) {
            return new byte[0];
        }
        byte[] copy = new byte[size];
        System.arraycopy(source, from, copy, 0, size);
        return copy;
    }

    private static long u32(byte[] data, int offset) {
        return ((data[offset] & 0xffL) << 24) | ((data[offset + 1] & 0xffL) << 16)
                | ((data[offset + 2] & 0xffL) << 8) | (data[offset + 3] & 0xffL);
    }

    private static long u64(byte[] data, int offset) {
        long high = u32(data, offset);
        long low = u32(data, offset + 4);
        return (high << 32) | low;
    }

    private static String fourCc(byte[] data, int offset) {
        if (data == null || offset < 0 || offset + 4 > data.length) {
            return "";
        }
        char[] chars = new char[4];
        for (int index = 0; index < 4; index++) {
            chars[index] = (char) (data[offset + index] & 0xff);
        }
        return new String(chars);
    }

}
