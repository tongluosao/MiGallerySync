package com.tongluosao.gallerysync.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * 把 ICC 色彩配置写进已经编码好的预览文件。
 *
 * <p>JPEG：ICC 拆成若干 APP2 段直接插在 SOI 之后，不需要改动别的东西。
 * <p>HEIF：{@code colr} 必须追加到 {@code ipco} 末尾，并在 {@code ipma} 里给主 item 加一条关联。
 * 因为模块产出的 HEIC 把 {@code meta} 放在 {@code mdat} 之前，往 {@code ipco} 里插字节会让
 * {@code mdat} 整体后移，所以 {@code iloc} 里记录的绝对文件偏移必须同步平移，否则文件打不开。
 *
 * <p>两个注入方法都先写临时文件再原子替换，任何失败都返回 false 且原文件一个字节都不动；
 * HEIF 还会在替换前把结果整份重解析一遍（box size 链、colr 内容、iloc extent 是否仍落在
 * mdat 内），校验不过就丢弃临时文件。
 */
public final class IccInjector {

    private static final byte[] ICC_IDENTIFIER = {
            'I', 'C', 'C', '_', 'P', 'R', 'O', 'F', 'I', 'L', 'E', 0
    };
    private static final int ICC_IDENTIFIER_LENGTH = 12;
    /** APP2 的 payload 上限：段长字段 16 位，减去自身 2 字节。 */
    private static final int MAX_APP2_PAYLOAD = 65519;
    private static final int MAX_BOX_DEPTH = 12;
    private static final int MAX_ITEM_COUNT = 1_000_000;

    private IccInjector() {
    }

    // ------------------------------------------------------------------ JPEG

    /**
     * 把 ICC 拆段插到 JPEG 的 SOI 之后。已存在 ICC_PROFILE 的文件直接返回 true（不重复写）。
     */
    public static boolean injectJpeg(File jpeg, byte[] icc) {
        if (jpeg == null || icc == null || !IccProfile.isValid(icc) || !jpeg.isFile()) {
            return false;
        }
        long modified = jpeg.lastModified();
        File temporary = null;
        try {
            if (jpeg.length() > Integer.MAX_VALUE) {
                return false;
            }
            if (IccProfile.extract(jpeg) != null) {
                return true;
            }
            byte[] source = Files.readAllBytes(jpeg.toPath());
            if (source.length < 4 || (source[0] & 0xff) != 0xff || (source[1] & 0xff) != 0xd8) {
                return false;
            }
            int segmentCount = (icc.length + MAX_APP2_PAYLOAD - 1) / MAX_APP2_PAYLOAD;
            if (segmentCount < 1 || segmentCount > 255) {
                return false;
            }
            int overhead = segmentCount * (4 + ICC_IDENTIFIER_LENGTH + 2);
            byte[] result = new byte[source.length + overhead + icc.length];
            result[0] = (byte) 0xff;
            result[1] = (byte) 0xd8;
            int cursor = 2;
            int offset = 0;
            for (int sequence = 1; sequence <= segmentCount; sequence++) {
                int chunk = Math.min(MAX_APP2_PAYLOAD, icc.length - offset);
                int payload = ICC_IDENTIFIER_LENGTH + 2 + chunk;
                result[cursor++] = (byte) 0xff;
                result[cursor++] = (byte) 0xe2;
                result[cursor++] = (byte) ((payload + 2) >> 8);
                result[cursor++] = (byte) (payload + 2);
                System.arraycopy(ICC_IDENTIFIER, 0, result, cursor, ICC_IDENTIFIER_LENGTH);
                cursor += ICC_IDENTIFIER_LENGTH;
                result[cursor++] = (byte) sequence;
                result[cursor++] = (byte) segmentCount;
                System.arraycopy(icc, offset, result, cursor, chunk);
                cursor += chunk;
                offset += chunk;
            }
            System.arraycopy(source, 2, result, cursor, source.length - 2);

            temporary = createTemporary(jpeg);
            writeFile(temporary, result);
            if (modified > 0L) {
                // 预览的 mtime 被上层用来判断新鲜度，注入不该改动它。
                temporary.setLastModified(modified);
            }
            Files.move(temporary.toPath(), jpeg.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            temporary = null;
            if (modified > 0L) {
                jpeg.setLastModified(modified);
            }
            return true;
        } catch (Throwable throwable) {
            Logger.w("event=preview_icc_inject_failed format=jpeg reason=" + throwable);
            return false;
        } finally {
            if (temporary != null) {
                temporary.delete();
            }
        }
    }

    // ------------------------------------------------------------------ HEIF

    /**
     * 往 HEIF 的 {@code ipco} 追加 {@code colr(prof)}，在 {@code ipma} 里给 {@code pitm} 的主 item
     * 加一条 essential 关联，并平移 {@code iloc} 里被推迟的 extent 偏移。
     */
    public static boolean injectHeif(File heif, byte[] icc) {
        if (heif == null || icc == null || !IccProfile.isValid(icc) || !heif.isFile()) {
            return false;
        }
        long modified = heif.lastModified();
        File temporary = null;
        try {
            if (heif.length() > Integer.MAX_VALUE) {
                return false;
            }
            if (IccProfile.extract(heif) != null) {
                return true;
            }
            byte[] source = Files.readAllBytes(heif.toPath());
            byte[] result = buildInjected(source, icc);
            if (result == null || !verifyStructure(result, icc)) {
                Logger.w("event=preview_icc_inject_failed format=heif reason=structure");
                return false;
            }
            temporary = createTemporary(heif);
            writeFile(temporary, result);
            if (!verify(temporary)) {
                Logger.w("event=preview_icc_inject_failed format=heif reason=verify");
                return false;
            }
            if (modified > 0L) {
                temporary.setLastModified(modified);
            }
            Files.move(temporary.toPath(), heif.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            temporary = null;
            if (modified > 0L) {
                heif.setLastModified(modified);
            }
            return true;
        } catch (Throwable throwable) {
            Logger.w("event=preview_icc_inject_failed format=heif reason=" + throwable);
            return false;
        } finally {
            if (temporary != null) {
                temporary.delete();
            }
        }
    }

    /** 重解析 HEIF：box size 链自洽、存在 {@code colr(prof)}、{@code iloc} extent 落在 mdat 内。 */
    public static boolean verify(File heif) {
        if (heif == null || !heif.isFile() || heif.length() > Integer.MAX_VALUE) {
            return false;
        }
        try {
            return verifyStructure(Files.readAllBytes(heif.toPath()), null);
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ------------------------------------------------------------------ 构造

    private static byte[] buildInjected(byte[] source, byte[] icc) {
        List<Box> top = parseBoxes(source, 0, source.length, 0);
        if (top == null) {
            return null;
        }
        Box meta = find(top, "meta");
        if (meta == null || meta.headerSize != 8) {
            return null;
        }
        List<Box> metaChildren = parseBoxes(source, meta.bodyOffset() + 4, meta.end(), 1);
        if (metaChildren == null) {
            return null;
        }
        Box iloc = find(metaChildren, "iloc");
        Box pitm = find(metaChildren, "pitm");
        Box iprp = find(metaChildren, "iprp");
        if (iloc == null || pitm == null || iprp == null || iprp.headerSize != 8) {
            return null;
        }
        List<Box> iprpChildren = parseBoxes(source, iprp.bodyOffset(), iprp.end(), 2);
        if (iprpChildren == null) {
            return null;
        }
        Box ipco = find(iprpChildren, "ipco");
        Box ipma = find(iprpChildren, "ipma");
        // 只支持普通 32 位 size 的容器；largesize 的布局在预览里不会出现，直接放弃注入。
        if (ipco == null || ipma == null || ipco.headerSize != 8 || ipma.headerSize != 8) {
            return null;
        }
        List<Box> properties = parseBoxes(source, ipco.bodyOffset(), ipco.end(), 3);
        if (properties == null || properties.isEmpty()) {
            return null;
        }
        int propertyIndex = properties.size() + 1;
        if (propertyIndex > 0x7fff) {
            return null;
        }
        int primaryItem = readPitm(source, pitm);
        if (primaryItem < 0) {
            return null;
        }
        byte[] colr = buildColorProfile(icc);
        byte[] ipmaBytes = appendAssociation(source, ipma, primaryItem, propertyIndex);
        if (ipmaBytes == null) {
            return null;
        }

        int colrInsert = ipco.end();
        int ipmaStart = ipma.offset;
        int deltaColr = colr.length;
        int deltaIpma = ipmaBytes.length - ipma.size;
        long newLength = (long) source.length + deltaColr + deltaIpma;
        if (newLength > Integer.MAX_VALUE) {
            return null;
        }

        byte[] result = new byte[(int) newLength];
        int before = colrInsert;
        System.arraycopy(source, 0, result, 0, before);
        int cursor = before;
        System.arraycopy(colr, 0, result, cursor, colr.length);
        cursor += colr.length;
        int middleEnd = ipmaStart;
        System.arraycopy(source, before, result, cursor, middleEnd - before);
        cursor += middleEnd - before;
        System.arraycopy(ipmaBytes, 0, result, cursor, ipmaBytes.length);
        cursor += ipmaBytes.length;
        System.arraycopy(source, ipma.end(), result, cursor, source.length - ipma.end());

        int shiftedMeta = shiftPosition(meta.offset, colrInsert, deltaColr, ipmaStart, deltaIpma);
        int shiftedIprp = shiftPosition(iprp.offset, colrInsert, deltaColr, ipmaStart, deltaIpma);
        int shiftedIpco = shiftPosition(ipco.offset, colrInsert, deltaColr, ipmaStart, deltaIpma);
        writeU32(result, shiftedMeta, meta.size + deltaColr + deltaIpma);
        writeU32(result, shiftedIprp, iprp.size + deltaColr + deltaIpma);
        writeU32(result, shiftedIpco, ipco.size + deltaColr);

        int shiftedIloc = shiftPosition(iloc.offset, colrInsert, deltaColr, ipmaStart, deltaIpma);
        if (!shiftIloc(source, result, iloc, shiftedIloc, colrInsert, deltaColr, ipmaStart,
                deltaIpma)) {
            return null;
        }
        return result;
    }

    private static int shiftPosition(int position, int colrInsert, int deltaColr, int ipmaStart,
            int deltaIpma) {
        if (position >= ipmaStart) {
            return position + deltaColr + deltaIpma;
        }
        if (position >= colrInsert) {
            return position + deltaColr;
        }
        return position;
    }

    private static byte[] buildColorProfile(byte[] icc) {
        byte[] colr = new byte[12 + icc.length];
        writeU32(colr, 0, colr.length);
        colr[4] = 'c';
        colr[5] = 'o';
        colr[6] = 'l';
        colr[7] = 'r';
        colr[8] = 'p';
        colr[9] = 'r';
        colr[10] = 'o';
        colr[11] = 'f';
        System.arraycopy(icc, 0, colr, 12, icc.length);
        return colr;
    }

    private static int readPitm(byte[] data, Box pitm) {
        if (pitm.size < 12) {
            return -1;
        }
        int version = data[pitm.offset + 8] & 0xff;
        if (version == 0) {
            return pitm.size < 14 ? -1 : (int) u16(data, pitm.offset + 12);
        }
        if (pitm.size < 16) {
            return -1;
        }
        long itemId = u32(data, pitm.offset + 12);
        return itemId > Integer.MAX_VALUE ? -1 : (int) itemId;
    }

    /**
     * 复制 {@code ipma} 并给 {@code primaryItem} 增加一条 essential 关联。属性索引放不进 7 位时
     * 把整个文件升级成 2 字节形式（置 flags bit0），否则沿用文件已有的形式。
     */
    private static byte[] appendAssociation(byte[] data, Box ipma, int primaryItem,
            int propertyIndex) {
        if (ipma.size < 16) {
            return null;
        }
        int version = data[ipma.offset + 8] & 0xff;
        int flags = ((data[ipma.offset + 9] & 0xff) << 16)
                | ((data[ipma.offset + 10] & 0xff) << 8)
                | (data[ipma.offset + 11] & 0xff);
        long entryCount = u32(data, ipma.offset + 12);
        if (entryCount <= 0L || entryCount > MAX_ITEM_COUNT) {
            return null;
        }
        int itemIdSize = version < 1 ? 2 : 4;
        int writtenAssociationSize = (flags & 1) != 0 || propertyIndex > 0x7f ? 2 : 1;
        int readAssociationSize = (flags & 1) != 0 ? 2 : 1;
        int writtenFlags = writtenAssociationSize == 2 ? (flags | 1) : flags;

        List<IpmaEntry> entries = new ArrayList<>((int) entryCount);
        int cursor = ipma.offset + 16;
        for (int index = 0; index < (int) entryCount; index++) {
            if (cursor + itemIdSize + 1 > ipma.end()) {
                return null;
            }
            long itemId = itemIdSize == 4 ? u32(data, cursor) : u16(data, cursor);
            cursor += itemIdSize;
            int associationCount = data[cursor++] & 0xff;
            IpmaEntry entry = new IpmaEntry(itemId);
            for (int position = 0; position < associationCount; position++) {
                if (cursor + readAssociationSize > ipma.end()) {
                    return null;
                }
                int value;
                if (readAssociationSize == 1) {
                    value = data[cursor++] & 0xff;
                    entry.associations.add(new int[]{(value & 0x80) != 0 ? 1 : 0, value & 0x7f});
                } else {
                    value = (int) u16(data, cursor);
                    cursor += 2;
                    entry.associations.add(
                            new int[]{(value & 0x8000) != 0 ? 1 : 0, value & 0x7fff});
                }
            }
            entries.add(entry);
        }

        IpmaEntry primary = null;
        for (IpmaEntry entry : entries) {
            if (entry.itemId == primaryItem) {
                primary = entry;
                break;
            }
        }
        if (primary == null) {
            primary = new IpmaEntry(primaryItem);
            entries.add(primary);
        }
        for (int[] association : primary.associations) {
            if (association[1] == propertyIndex) {
                return null;
            }
        }
        primary.associations.add(new int[]{1, propertyIndex});

        int size = 16;
        for (IpmaEntry entry : entries) {
            size += itemIdSize + 1 + entry.associations.size() * writtenAssociationSize;
        }
        if (size < 16 || entries.size() > MAX_ITEM_COUNT) {
            return null;
        }
        byte[] result = new byte[size];
        writeU32(result, 0, size);
        result[4] = 'i';
        result[5] = 'p';
        result[6] = 'm';
        result[7] = 'a';
        result[8] = (byte) version;
        result[9] = (byte) ((writtenFlags >> 16) & 0xff);
        result[10] = (byte) ((writtenFlags >> 8) & 0xff);
        result[11] = (byte) (writtenFlags & 0xff);
        writeU32(result, 12, entries.size());
        int write = 16;
        for (IpmaEntry entry : entries) {
            if (itemIdSize == 4) {
                writeU32(result, write, entry.itemId);
            } else {
                writeU16(result, write, (int) entry.itemId);
            }
            write += itemIdSize;
            result[write++] = (byte) entry.associations.size();
            for (int[] association : entry.associations) {
                int value = (association[0] != 0 ? 0x8000 : 0) | association[1];
                if (writtenAssociationSize == 1) {
                    result[write++] = (byte) ((association[0] != 0 ? 0x80 : 0) | association[1]);
                } else {
                    writeU16(result, write, value);
                    write += 2;
                }
            }
        }
        return result;
    }

    /**
     * 把 {@code iloc} 里落在插入点之后的 extent 绝对偏移平移。{@code construction_method != 0}
     * 的条目相对 {@code idat}/别的 item，字节位置没变，保持原样。
     */
    private static boolean shiftIloc(byte[] source, byte[] result, Box iloc, int shiftedIloc,
            int colrInsert, int deltaColr, int ipmaStart, int deltaIpma) {
        if (iloc.size < 16) {
            return false;
        }
        int version = source[iloc.offset + 8] & 0xff;
        int offsetSize = (source[iloc.offset + 12] & 0xff) >> 4;
        int lengthSize = source[iloc.offset + 12] & 0x0f;
        int baseOffsetSize = (source[iloc.offset + 13] & 0xff) >> 4;
        int indexSize = version >= 1 ? (source[iloc.offset + 13] & 0x0f) : 0;
        if (offsetSize < 1 || offsetSize > 8 || lengthSize > 8 || baseOffsetSize > 8
                || indexSize > 8) {
            return false;
        }
        long itemCount = u16(source, iloc.offset + 14);
        int cursor = iloc.offset + 16;
        for (int item = 0; item < (int) itemCount; item++) {
            int itemIdSize = version < 2 ? 2 : 4;
            if (cursor + itemIdSize > iloc.end()) {
                return false;
            }
            cursor += itemIdSize;
            int constructionMethod = 0;
            if (version == 1 || version == 2) {
                if (cursor + 2 > iloc.end()) {
                    return false;
                }
                constructionMethod = source[cursor + 1] & 0x0f;
                cursor += 2;
            }
            if (cursor + 2 > iloc.end()) {
                return false;
            }
            cursor += 2;
            if (cursor + baseOffsetSize > iloc.end()) {
                return false;
            }
            long baseOffset = readUint(source, cursor, baseOffsetSize);
            cursor += baseOffsetSize;
            if (cursor + 2 > iloc.end()) {
                return false;
            }
            int extentCount = (int) u16(source, cursor);
            cursor += 2;
            for (int extent = 0; extent < extentCount; extent++) {
                if (version >= 1 && indexSize > 0) {
                    cursor += indexSize;
                }
                if (cursor + offsetSize + lengthSize > iloc.end()) {
                    return false;
                }
                int offsetField = cursor;
                long extentOffset = readUint(source, cursor, offsetSize);
                cursor += offsetSize;
                cursor += lengthSize;
                if (constructionMethod != 0) {
                    continue;
                }
                long absolute = baseOffset + extentOffset;
                long shiftedExtent;
                if (absolute >= ipmaStart) {
                    shiftedExtent = absolute + deltaColr + deltaIpma;
                } else if (absolute >= colrInsert) {
                    shiftedExtent = absolute + deltaColr;
                } else {
                    continue;
                }
                long shiftedField = shiftedExtent - baseOffset;
                if (!fits(shiftedField, offsetSize)) {
                    // 偏移字段装不下新值：放弃注入，宁可没有 ICC 也不要写坏文件。
                    return false;
                }
                writeUint(result, shiftedIloc + (offsetField - iloc.offset), shiftedField,
                        offsetSize);
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ 校验

    private static boolean verifyStructure(byte[] data, byte[] expectedIcc) {
        List<Box> top = parseBoxes(data, 0, data.length, 0);
        if (top == null) {
            return false;
        }
        Box meta = find(top, "meta");
        if (meta == null) {
            return false;
        }
        List<Box> metaChildren = parseBoxes(data, meta.bodyOffset() + 4, meta.end(), 1);
        if (metaChildren == null) {
            return false;
        }
        Box iprp = find(metaChildren, "iprp");
        Box iloc = find(metaChildren, "iloc");
        if (iprp == null || iloc == null) {
            return false;
        }
        List<Box> iprpChildren = parseBoxes(data, iprp.bodyOffset(), iprp.end(), 2);
        if (iprpChildren == null) {
            return false;
        }
        Box ipco = find(iprpChildren, "ipco");
        Box ipma = find(iprpChildren, "ipma");
        if (ipco == null || ipma == null) {
            return false;
        }
        List<Box> properties = parseBoxes(data, ipco.bodyOffset(), ipco.end(), 3);
        if (properties == null) {
            return false;
        }
        Box colr = null;
        for (Box property : properties) {
            if ("colr".equals(property.type)) {
                colr = property;
                break;
            }
        }
        if (colr == null || colr.size < 12
                || data[colr.bodyOffset()] != 'p' || data[colr.bodyOffset() + 1] != 'r'
                || data[colr.bodyOffset() + 2] != 'o' || data[colr.bodyOffset() + 3] != 'f') {
            return false;
        }
        if (expectedIcc != null) {
            byte[] actual = Arrays.copyOfRange(data, colr.bodyOffset() + 4, colr.end());
            if (!Arrays.equals(actual, expectedIcc)) {
                return false;
            }
        }
        return extentsInsideMdat(data, iloc, top) && hasPrimaryColorProfile(data, ipma,
                properties.size());
    }

    private static boolean extentsInsideMdat(byte[] data, Box iloc, List<Box> top) {
        List<long[]> mdats = new ArrayList<>();
        for (Box box : top) {
            if ("mdat".equals(box.type)) {
                mdats.add(new long[]{box.bodyOffset(), box.end()});
            }
        }
        if (mdats.isEmpty() || iloc.size < 16) {
            return false;
        }
        int version = data[iloc.offset + 8] & 0xff;
        int offsetSize = (data[iloc.offset + 12] & 0xff) >> 4;
        int lengthSize = data[iloc.offset + 12] & 0x0f;
        int baseOffsetSize = (data[iloc.offset + 13] & 0xff) >> 4;
        int indexSize = version >= 1 ? (data[iloc.offset + 13] & 0x0f) : 0;
        int itemIdSize = version < 2 ? 2 : 4;
        int cursor = iloc.offset + 16;
        int itemCount = (int) u16(data, iloc.offset + 14);
        for (int item = 0; item < itemCount; item++) {
            if (cursor + itemIdSize > iloc.end()) {
                return false;
            }
            cursor += itemIdSize;
            int constructionMethod = 0;
            if (version == 1 || version == 2) {
                if (cursor + 2 > iloc.end()) {
                    return false;
                }
                constructionMethod = data[cursor + 1] & 0x0f;
                cursor += 2;
            }
            if (cursor + 2 + baseOffsetSize > iloc.end()) {
                return false;
            }
            cursor += 2;
            long baseOffset = readUint(data, cursor, baseOffsetSize);
            cursor += baseOffsetSize;
            if (cursor + 2 > iloc.end()) {
                return false;
            }
            int extentCount = (int) u16(data, cursor);
            cursor += 2;
            for (int extent = 0; extent < extentCount; extent++) {
                if (version >= 1 && indexSize > 0) {
                    cursor += indexSize;
                }
                if (cursor + offsetSize + lengthSize > iloc.end()) {
                    return false;
                }
                long offset = baseOffset + readUint(data, cursor, offsetSize);
                cursor += offsetSize;
                long length = readUint(data, cursor, lengthSize);
                cursor += lengthSize;
                if (constructionMethod != 0 || length == 0L) {
                    continue;
                }
                boolean inside = false;
                for (long[] mdat : mdats) {
                    if (offset >= mdat[0] && offset + length <= mdat[1]) {
                        inside = true;
                        break;
                    }
                }
                if (!inside) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean hasPrimaryColorProfile(byte[] data, Box ipma, int propertyCount) {
        if (ipma.size < 16) {
            return false;
        }
        int version = data[ipma.offset + 8] & 0xff;
        int flags = ((data[ipma.offset + 9] & 0xff) << 16)
                | ((data[ipma.offset + 10] & 0xff) << 8)
                | (data[ipma.offset + 11] & 0xff);
        int itemIdSize = version < 1 ? 2 : 4;
        int associationSize = (flags & 1) != 0 ? 2 : 1;
        int entryCount = (int) u32(data, ipma.offset + 12);
        int cursor = ipma.offset + 16;
        boolean found = false;
        for (int index = 0; index < entryCount; index++) {
            if (cursor + itemIdSize + 1 > ipma.end()) {
                return false;
            }
            cursor += itemIdSize;
            int associationCount = data[cursor++] & 0xff;
            for (int position = 0; position < associationCount; position++) {
                if (cursor + associationSize > ipma.end()) {
                    return false;
                }
                int value;
                if (associationSize == 1) {
                    value = data[cursor++] & 0xff;
                } else {
                    value = (int) u16(data, cursor);
                    cursor += 2;
                }
                int propertyIndex = associationSize == 1 ? value & 0x7f : value & 0x7fff;
                if (propertyIndex == propertyCount) {
                    found = true;
                }
            }
        }
        return found;
    }

    // ------------------------------------------------------------------ box 解析

    private static final class Box {
        final String type;
        final int offset;
        final int size;
        final int headerSize;

        Box(String type, int offset, int size, int headerSize) {
            this.type = type;
            this.offset = offset;
            this.size = size;
            this.headerSize = headerSize;
        }

        int bodyOffset() {
            return offset + headerSize;
        }

        int end() {
            return offset + size;
        }
    }

    private static final class IpmaEntry {
        final long itemId;
        final List<int[]> associations = new ArrayList<>();

        IpmaEntry(long itemId) {
            this.itemId = itemId;
        }
    }

    /** 解析 {@code [start, end)} 里的 box 列表；size 链不闭合、越界或不是 8 字节头返回 null。 */
    private static List<Box> parseBoxes(byte[] data, int start, int end, int depth) {
        if (depth > MAX_BOX_DEPTH || start < 0 || start > end || end > data.length) {
            return null;
        }
        List<Box> boxes = new ArrayList<>();
        int cursor = start;
        while (cursor + 8 <= end) {
            long size = u32(data, cursor);
            String type = fourCc(data, cursor + 4);
            int headerSize = 8;
            if (size == 1L) {
                if (cursor + 16 > end) {
                    return null;
                }
                size = u64(data, cursor + 8);
                headerSize = 16;
            } else if (size == 0L) {
                size = end - cursor;
            }
            if (size < headerSize || size > end - cursor) {
                return null;
            }
            boxes.add(new Box(type, cursor, (int) size, headerSize));
            cursor += (int) size;
        }
        return cursor == end ? boxes : null;
    }

    private static Box find(List<Box> boxes, String type) {
        for (Box box : boxes) {
            if (type.equals(box.type)) {
                return box;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 工具

    private static File createTemporary(File target) throws IOException {
        File parent = target.getAbsoluteFile().getParentFile();
        if (parent == null || !parent.isDirectory()) {
            throw new IOException("no parent directory for " + target.getName());
        }
        return new File(parent, target.getName() + ".icc-" + UUID.randomUUID() + ".tmp");
    }

    private static void writeFile(File file, byte[] content) throws IOException {
        try (FileOutputStream stream = new FileOutputStream(file)) {
            stream.write(content);
            stream.flush();
            stream.getFD().sync();
        }
    }

    private static boolean fits(long value, int size) {
        if (value < 0L || size < 1) {
            return false;
        }
        return size >= 8 || value < (1L << (size * 8));
    }

    private static long readUint(byte[] data, int offset, int size) {
        long value = 0L;
        for (int index = 0; index < size; index++) {
            value = (value << 8) | (data[offset + index] & 0xffL);
        }
        return value;
    }

    private static void writeUint(byte[] data, int offset, long value, int size) {
        for (int index = size - 1; index >= 0; index--) {
            data[offset + index] = (byte) (value & 0xffL);
            value >>>= 8;
        }
    }

    private static void writeU16(byte[] data, int offset, int value) {
        data[offset] = (byte) ((value >> 8) & 0xff);
        data[offset + 1] = (byte) (value & 0xff);
    }

    private static void writeU32(byte[] data, int offset, long value) {
        data[offset] = (byte) ((value >> 24) & 0xff);
        data[offset + 1] = (byte) ((value >> 16) & 0xff);
        data[offset + 2] = (byte) ((value >> 8) & 0xff);
        data[offset + 3] = (byte) (value & 0xff);
    }

    private static long u16(byte[] data, int offset) {
        return ((data[offset] & 0xffL) << 8) | (data[offset + 1] & 0xffL);
    }

    private static long u32(byte[] data, int offset) {
        return ((data[offset] & 0xffL) << 24) | ((data[offset + 1] & 0xffL) << 16)
                | ((data[offset + 2] & 0xffL) << 8) | (data[offset + 3] & 0xffL);
    }

    private static long u64(byte[] data, int offset) {
        return (u32(data, offset) << 32) | u32(data, offset + 4);
    }

    private static String fourCc(byte[] data, int offset) {
        if (offset < 0 || offset + 4 > data.length) {
            return "";
        }
        char[] chars = new char[4];
        for (int index = 0; index < 4; index++) {
            chars[index] = (char) (data[offset + index] & 0xff);
        }
        return new String(chars);
    }
}
