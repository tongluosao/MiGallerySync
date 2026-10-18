package com.tongluosao.gallerysync.core;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;

import androidx.exifinterface.media.ExifInterface;
import androidx.heifwriter.HeifWriter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Creates a normalized, size-limited HEIC copy without changing the source file. */
public final class HeicPreviewEncoder {

    public static final int DEFAULT_MAX_EDGE = 2560;
    public static final int DEFAULT_QUALITY = 84;

    private static final String HEIC_MIME = "image/vnd.android.heic";
    private static final int VERIFY_MAX_EDGE = 512;
    private static final long STOP_TIMEOUT_MS = 30_000L;
    private static final String[] PRESERVED_TAGS = {
            ExifInterface.TAG_GPS_VERSION_ID,
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_TIMESTAMP,
            ExifInterface.TAG_GPS_DATESTAMP,
            ExifInterface.TAG_GPS_PROCESSING_METHOD,
            ExifInterface.TAG_GPS_SPEED,
            ExifInterface.TAG_GPS_SPEED_REF,
            ExifInterface.TAG_GPS_TRACK,
            ExifInterface.TAG_GPS_TRACK_REF,
            ExifInterface.TAG_GPS_IMG_DIRECTION,
            ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
            ExifInterface.TAG_GPS_DEST_LATITUDE,
            ExifInterface.TAG_GPS_DEST_LATITUDE_REF,
            ExifInterface.TAG_GPS_DEST_LONGITUDE,
            ExifInterface.TAG_GPS_DEST_LONGITUDE_REF,
            ExifInterface.TAG_GPS_DEST_BEARING,
            ExifInterface.TAG_GPS_DEST_BEARING_REF,
            ExifInterface.TAG_GPS_DEST_DISTANCE,
            ExifInterface.TAG_GPS_DEST_DISTANCE_REF,
            ExifInterface.TAG_GPS_AREA_INFORMATION,
            ExifInterface.TAG_GPS_DOP,
            ExifInterface.TAG_GPS_MAP_DATUM,
            ExifInterface.TAG_GPS_SATELLITES,
            ExifInterface.TAG_GPS_STATUS,
            ExifInterface.TAG_GPS_MEASURE_MODE,
            ExifInterface.TAG_GPS_H_POSITIONING_ERROR,
            ExifInterface.TAG_GPS_DIFFERENTIAL,
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_ISO_SPEED_RATINGS,
            ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
            ExifInterface.TAG_EXPOSURE_PROGRAM,
            ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
            ExifInterface.TAG_METERING_MODE,
            ExifInterface.TAG_APERTURE_VALUE,
            ExifInterface.TAG_MAX_APERTURE_VALUE,
            ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
            ExifInterface.TAG_LENS_MAKE,
            ExifInterface.TAG_LENS_MODEL,
            ExifInterface.TAG_LENS_SPECIFICATION,
            ExifInterface.TAG_USER_COMMENT,
            ExifInterface.TAG_IMAGE_DESCRIPTION,
            ExifInterface.TAG_SOFTWARE,
            ExifInterface.TAG_FLASH,
            ExifInterface.TAG_WHITE_BALANCE,
            ExifInterface.TAG_LIGHT_SOURCE,
            ExifInterface.TAG_ARTIST,
            ExifInterface.TAG_COPYRIGHT,
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_SUBSEC_TIME,
            ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
            ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
            ExifInterface.TAG_OFFSET_TIME,
            ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
            ExifInterface.TAG_OFFSET_TIME_DIGITIZED
    };

    private static volatile String sLastFailureReason;

    private HeicPreviewEncoder() {
    }

    public static boolean encode(File original, File output) {
        return encode(original, output, DEFAULT_MAX_EDGE, DEFAULT_QUALITY, true);
    }

    /**
     * Encode an oriented HEIC preview to a sibling temporary file, verify it, then atomically
     * replace {@code output}. A failure leaves any existing output untouched.
     */
    public static boolean encode(File original, File output, int maxEdge, int quality,
            boolean preserveMetadata) {
        sLastFailureReason = null;
        if (original == null || output == null || !original.isFile()) {
            return fail("source or destination is missing");
        }
        if (maxEdge <= 0 || quality < 0 || quality > 100) {
            return fail("invalid max edge or quality");
        }

        File stage = null;
        File exifJpeg = null;
        Bitmap decoded = null;
        Bitmap oriented = null;
        Bitmap rgb = null;
        HeifWriter writer = null;
        try {
            if (original.getCanonicalFile().equals(output.getCanonicalFile())) {
                throw new IOException("source and destination refer to the same file");
            }
            File parent = output.getAbsoluteFile().getParentFile();
            if (parent == null || (!parent.isDirectory() && !parent.mkdirs()
                    && !parent.isDirectory())) {
                throw new IOException("cannot create destination directory");
            }
            String suffix = ".tmp-" + UUID.randomUUID();
            stage = new File(parent, output.getName() + suffix + ".heic");

            ExifInterface sourceExif = null;
            try {
                sourceExif = new ExifInterface(original.getAbsolutePath());
            } catch (IOException ex) {
                if (preserveMetadata) {
                    throw new IOException("cannot read source EXIF", ex);
                }
            }
            int orientation = sourceExif == null
                    ? ExifInterface.ORIENTATION_NORMAL
                    : sourceExif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                            ExifInterface.ORIENTATION_NORMAL);
            Map<String, String> expectedExif = preserveMetadata
                    ? copyableAttributes(sourceExif) : new LinkedHashMap<>();
            byte[] exifBlock = null;
            if (preserveMetadata) {
                exifJpeg = new File(parent, output.getName() + suffix + ".jpg");
                expectedExif.put(ExifInterface.TAG_ORIENTATION,
                        Integer.toString(ExifInterface.ORIENTATION_NORMAL));
                exifBlock = makeExifBlock(exifJpeg, expectedExif);
            }

            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(original.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                throw new IOException("source image dimensions could not be read");
            }
            int sample = 1;
            while (Math.max(bounds.outWidth / sample, bounds.outHeight / sample)
                    > maxEdge * 2L) {
                if (sample > (1 << 29)) {
                    throw new IOException("source dimensions are too large");
                }
                sample *= 2;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            decoded = BitmapFactory.decodeFile(original.getAbsolutePath(), options);
            if (decoded == null) {
                throw new IOException("source image could not be decoded");
            }

            Matrix matrix = orientationMatrix(orientation);
            oriented = matrix == null || matrix.isIdentity() ? decoded
                    : Bitmap.createBitmap(decoded, 0, 0, decoded.getWidth(), decoded.getHeight(),
                            matrix, true);
            if (oriented == null) {
                throw new IOException("source orientation could not be applied");
            }
            int longEdge = Math.max(oriented.getWidth(), oriented.getHeight());
            if (longEdge > maxEdge) {
                float scale = (float) maxEdge / longEdge;
                Bitmap resized = Bitmap.createScaledBitmap(oriented,
                        Math.max(1, Math.round(oriented.getWidth() * scale)),
                        Math.max(1, Math.round(oriented.getHeight() * scale)), true);
                if (resized != oriented) {
                    if (oriented != decoded) {
                        oriented.recycle();
                    }
                    oriented = resized;
                }
            }

            rgb = Bitmap.createBitmap(oriented.getWidth(), oriented.getHeight(),
                    Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(rgb);
            canvas.drawColor(Color.WHITE);
            canvas.drawBitmap(oriented, 0f, 0f, new Paint(Paint.FILTER_BITMAP_FLAG));

            writer = new HeifWriter.Builder(stage.getAbsolutePath(), rgb.getWidth(),
                    rgb.getHeight(), HeifWriter.INPUT_MODE_BITMAP)
                    .setQuality(quality)
                    .setMaxImages(1)
                    .setGridEnabled(true)
                    .build();
            writer.start();
            if (exifBlock != null) {
                writer.addExifData(0, exifBlock, 0, exifBlock.length);
            }
            writer.addBitmap(rgb);
            writer.stop(STOP_TIMEOUT_MS);
            writer.close();
            writer = null;

            verifyOutput(stage, rgb.getWidth(), rgb.getHeight(), expectedExif,
                    preserveMetadata);
            if (preserveMetadata && original.lastModified() > 0) {
                stage.setLastModified(original.lastModified());
            }
            Files.move(stage.toPath(), output.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            stage = null;
            sLastFailureReason = null;
            return true;
        } catch (Throwable failure) {
            String message = failure.getMessage();
            sLastFailureReason = failure.getClass().getSimpleName()
                    + (message == null || message.isEmpty() ? "" : ": " + message);
            Logger.d("event=heic_preview_failed reason=" + sLastFailureReason);
            return false;
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (Throwable ignored) {
                }
            }
            recycle(rgb);
            if (oriented != decoded) {
                recycle(oriented);
            }
            recycle(decoded);
            deleteQuietly(exifJpeg);
            deleteQuietly(stage);
        }
    }

    /** Reports whether the platform advertises the legacy flexible-YUV HEIC encoder path. */
    public static boolean isPlatformEncoderAdvertised() {
        try {
            MediaCodecList codecs = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            for (MediaCodecInfo info : codecs.getCodecInfos()) {
                if (!info.isEncoder()) {
                    continue;
                }
                for (String type : info.getSupportedTypes()) {
                    if (!HEIC_MIME.equalsIgnoreCase(type)) {
                        continue;
                    }
                    MediaCodecInfo.CodecCapabilities capabilities =
                            info.getCapabilitiesForType(type);
                    for (int color : capabilities.colorFormats) {
                        if (color == MediaCodecInfo.CodecCapabilities
                                .COLOR_FormatYUV420Flexible) {
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // Capability discovery is diagnostic; HeifWriter remains the authoritative attempt.
        }
        return false;
    }

    public static String getLastFailureReason() {
        return sLastFailureReason;
    }

    private static Map<String, String> copyableAttributes(ExifInterface source) {
        Map<String, String> attributes = new LinkedHashMap<>();
        if (source == null) {
            return attributes;
        }
        for (String tag : PRESERVED_TAGS) {
            String value = source.getAttribute(tag);
            if (value != null) {
                attributes.put(tag, value);
            }
        }
        return attributes;
    }

    /** Writes allowlisted tags to a temporary JPEG and extracts its complete APP1 EXIF block. */
    private static byte[] makeExifBlock(File jpeg, Map<String, String> attributes)
            throws IOException {
        Bitmap pixel = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        try {
            pixel.eraseColor(Color.WHITE);
            try (FileOutputStream out = new FileOutputStream(jpeg)) {
                if (!pixel.compress(Bitmap.CompressFormat.JPEG, 100, out)) {
                    throw new IOException("could not create temporary EXIF JPEG");
                }
                out.flush();
                out.getFD().sync();
            }
        } finally {
            pixel.recycle();
        }

        ExifInterface exif = new ExifInterface(jpeg.getAbsolutePath());
        for (Map.Entry<String, String> attribute : attributes.entrySet()) {
            exif.setAttribute(attribute.getKey(), attribute.getValue());
        }
        exif.setAttribute(ExifInterface.TAG_ORIENTATION,
                Integer.toString(ExifInterface.ORIENTATION_NORMAL));
        exif.saveAttributes();

        byte[] jpegBytes = Files.readAllBytes(jpeg.toPath());
        if (jpegBytes.length < 4 || (jpegBytes[0] & 0xff) != 0xff
                || (jpegBytes[1] & 0xff) != 0xd8) {
            throw new IOException("temporary EXIF JPEG has an invalid header");
        }
        int position = 2;
        while (position + 1 < jpegBytes.length) {
            while (position < jpegBytes.length && (jpegBytes[position] & 0xff) != 0xff) {
                position++;
            }
            while (position < jpegBytes.length && (jpegBytes[position] & 0xff) == 0xff) {
                position++;
            }
            if (position >= jpegBytes.length) {
                break;
            }
            int marker = jpegBytes[position++] & 0xff;
            if (marker == 0xda || marker == 0xd9) {
                break;
            }
            if (marker == 0x01 || (marker >= 0xd0 && marker <= 0xd7)) {
                continue;
            }
            if (position + 1 >= jpegBytes.length) {
                break;
            }
            int segmentLength = ((jpegBytes[position] & 0xff) << 8)
                    | (jpegBytes[position + 1] & 0xff);
            if (segmentLength < 2 || position + segmentLength > jpegBytes.length) {
                throw new IOException("temporary JPEG has a malformed marker segment");
            }
            int payloadOffset = position + 2;
            int payloadLength = segmentLength - 2;
            if (marker == 0xe1 && startsWithExifIdentifier(jpegBytes, payloadOffset,
                    payloadLength)) {
                return Arrays.copyOfRange(jpegBytes, payloadOffset,
                        payloadOffset + payloadLength);
            }
            position += segmentLength;
        }
        throw new IOException("temporary JPEG has no EXIF APP1 segment");
    }

    private static boolean startsWithExifIdentifier(byte[] bytes, int offset, int length) {
        byte[] identifier = {'E', 'x', 'i', 'f', 0, 0};
        if (length < identifier.length || offset < 0 || offset + length > bytes.length) {
            return false;
        }
        for (int i = 0; i < identifier.length; i++) {
            if (bytes[offset + i] != identifier[i]) {
                return false;
            }
        }
        return true;
    }

    private static void verifyOutput(File file, int expectedWidth, int expectedHeight,
            Map<String, String> expectedExif, boolean preserveMetadata) throws IOException {
        if (!file.isFile() || file.length() <= 16 || !hasHeicBrand(file)) {
            throw new IOException("encoder did not produce a HEIC-compatible file");
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth != expectedWidth || bounds.outHeight != expectedHeight) {
            throw new IOException("HEIC dimensions were not verified: "
                    + bounds.outWidth + "x" + bounds.outHeight);
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        int sample = 1;
        while (Math.max(bounds.outWidth / sample, bounds.outHeight / sample)
                > VERIFY_MAX_EDGE) {
            sample *= 2;
        }
        options.inSampleSize = sample;
        Bitmap decoded = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        if (decoded == null) {
            throw new IOException("device could not decode generated HEIC");
        }
        decoded.recycle();

        if (preserveMetadata) {
            ExifInterface outputExif = new ExifInterface(file.getAbsolutePath());
            for (Map.Entry<String, String> attribute : expectedExif.entrySet()) {
                if (!attribute.getValue().equals(outputExif.getAttribute(attribute.getKey()))) {
                    throw new IOException("HEIC EXIF verification failed for "
                            + attribute.getKey());
                }
            }
            if (outputExif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_UNDEFINED) != ExifInterface.ORIENTATION_NORMAL) {
                throw new IOException("HEIC orientation metadata was not normalized");
            }
        }
    }

    private static boolean hasHeicBrand(File file) throws IOException {
        byte[] bytes = new byte[(int) Math.min(4096L, file.length())];
        try (java.io.FileInputStream input = new java.io.FileInputStream(file)) {
            int total = 0;
            while (total < bytes.length) {
                int count = input.read(bytes, total, bytes.length - total);
                if (count < 0) {
                    break;
                }
                total += count;
            }
            if (total < 16 || bytes[4] != 'f' || bytes[5] != 't'
                    || bytes[6] != 'y' || bytes[7] != 'p') {
                return false;
            }
            long boxSize = ((bytes[0] & 0xffL) << 24) | ((bytes[1] & 0xffL) << 16)
                    | ((bytes[2] & 0xffL) << 8) | (bytes[3] & 0xffL);
            if (boxSize < 16 || boxSize == 1) {
                return false;
            }
            int end = (int) Math.min(total, Math.min(boxSize, bytes.length));
            for (int offset = 8; offset + 4 <= end; offset += 4) {
                if (bytes[offset] == 'h' && bytes[offset + 1] == 'e'
                        && ((bytes[offset + 2] == 'i'
                                && (bytes[offset + 3] == 'c' || bytes[offset + 3] == 'x'))
                        || (bytes[offset + 2] == 'v'
                                && (bytes[offset + 3] == 'c' || bytes[offset + 3] == 'x')))) {
                    return true;
                }
            }
            return false;
        }
    }

    private static Matrix orientationMatrix(int orientation) {
        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                matrix.setScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                matrix.setRotate(180f);
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                matrix.setScale(1f, -1f);
                break;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                matrix.setRotate(90f);
                matrix.postScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_90:
                matrix.setRotate(90f);
                break;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                matrix.setRotate(270f);
                matrix.postScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                matrix.setRotate(270f);
                break;
            default:
                return null;
        }
        return matrix;
    }

    private static void recycle(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) {
            bitmap.recycle();
        }
    }

    private static void deleteQuietly(File file) {
        if (file != null && file.exists() && !file.delete()) {
            Logger.d("event=heic_preview_temp_cleanup_failed path=" + file.getAbsolutePath());
        }
    }

    private static boolean fail(String reason) {
        sLastFailureReason = reason;
        return false;
    }
}
