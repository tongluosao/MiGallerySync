package com.tongluosao.gallerysync.core;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.util.Log;

import androidx.exifinterface.media.ExifInterface;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * Runs one real HEIC through the production preview encoder using a cache-only input copy.
 * The input must be copied to cache/photo-quality-probe/incoming by the operator beforehand.
 */
public final class PhotoQualityProbeInstrumentation extends Instrumentation {
    private static final String TAG = "PhotoQualityProbe";
    private static final String PROBE_DIRECTORY = "photo-quality-probe";
    private static final String INPUT_DIRECTORY = "incoming";
    private static final String OUTPUT_DIRECTORY = "output";
    private static final String ARGUMENT_INPUT_NAME = "photo_quality_probe_input";
    private static final String[] CHECKED_TAGS = {
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_ISO_SPEED_RATINGS,
            ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF
    };
    private Bundle mArguments = Bundle.EMPTY;

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        mArguments = arguments == null ? Bundle.EMPTY : new Bundle(arguments);
        start();
    }

    @Override
    public void onStart() {
        Bundle result = new Bundle();
        try {
            String inputName = mArguments.getString(ARGUMENT_INPUT_NAME);
            Bundle probe = runProbe(getTargetContext(), inputName);
            result.putAll(probe);
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            String detail = failure.getClass().getSimpleName() + ": " + failure.getMessage();
            Log.e(TAG, detail, failure);
            result.putString("status", "FAILED");
            result.putString("failure", detail);
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    /** Can also be called from the existing synthetic runner when it dispatches this argument. */
    public static Bundle runProbe(Context context, String inputName) throws Exception {
        if (context == null) {
            throw new IllegalArgumentException("target context is missing");
        }
        if (inputName == null || !inputName.matches("[A-Za-z0-9_.-]+\\.(?i:heic|heif)")) {
            throw new IllegalArgumentException("input argument must be one HEIC/HEIF file name");
        }

        File cache = context.getCacheDir().getCanonicalFile();
        File probeRoot = new File(cache, PROBE_DIRECTORY).getCanonicalFile();
        File inputDirectory = new File(probeRoot, INPUT_DIRECTORY).getCanonicalFile();
        File input = new File(inputDirectory, inputName).getCanonicalFile();
        if (!input.getPath().startsWith(inputDirectory.getPath() + File.separator)) {
            throw new IOException("input escaped the probe cache directory");
        }
        if (!input.isFile() || input.length() == 0L) {
            throw new IOException("cache-only HEIC input is missing or empty: " + input);
        }

        File outputDirectory = new File(probeRoot, OUTPUT_DIRECTORY);
        if (!outputDirectory.isDirectory() && !outputDirectory.mkdirs()
                && !outputDirectory.isDirectory()) {
            throw new IOException("cannot create private output directory");
        }
        File output = new File(outputDirectory,
                input.getName() + "-" + UUID.randomUUID() + ".heic").getCanonicalFile();
        if (input.equals(output)) {
            throw new IOException("probe input and output must be different files");
        }

        long originalLength = input.length();
        long originalMtime = input.lastModified();
        String originalSha256 = sha256(input);
        ExifInterface sourceExif = new ExifInterface(input.getAbsolutePath());
        requireHeicBrand(input);

        // Null Config applies the production defaults: keep HEIC and preserve metadata.
        boolean encoded = DownloadQueue.createCompressedPreview(input, output,
                input.getName(), null);
        if (!encoded) {
            String reason = HeicPreviewEncoder.getLastFailureReason();
            throw new IOException("production HEIC preview encoder returned false"
                    + (reason == null ? "" : ": " + reason));
        }
        if (!output.isFile() || output.length() <= 16L) {
            throw new IOException("encoder returned success without a valid output file");
        }
        requireHeicBrand(output);

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(output.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException("output dimensions could not be decoded");
        }

        ExifInterface outputExif = new ExifInterface(output.getAbsolutePath());
        int metadataChecks = 0;
        for (String tag : CHECKED_TAGS) {
            String expected = sourceExif.getAttribute(tag);
            String actual = outputExif.getAttribute(tag);
            if (!metadataMatches(tag, expected, actual)) {
                throw new IOException("preserved metadata check failed: " + tag);
            }
            if (expected != null) {
                metadataChecks++;
            }
        }
        if (outputExif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_UNDEFINED) != ExifInterface.ORIENTATION_NORMAL) {
            throw new IOException("output orientation metadata was not normalized");
        }

        if (input.length() != originalLength || input.lastModified() != originalMtime
                || !originalSha256.equals(sha256(input))) {
            throw new IOException("cache input changed during the read-only probe");
        }

        Bundle result = new Bundle();
        result.putString("status", "ENCODED");
        result.putString("input_name", input.getName());
        result.putLong("input_bytes", originalLength);
        result.putLong("output_bytes", output.length());
        result.putBoolean("size_reduced", output.length() < originalLength);
        result.putString("input_sha256", originalSha256);
        result.putString("output_sha256", sha256(output));
        result.putInt("output_width", bounds.outWidth);
        result.putInt("output_height", bounds.outHeight);
        result.putInt("metadata_checks", metadataChecks);
        result.putString("output_private_path", output.getAbsolutePath());
        Log.i(TAG, "status=ENCODED input=" + input.getName() + " input_bytes=" + originalLength
                + " output_bytes=" + output.length() + " size_reduced="
                + (output.length() < originalLength) + " metadata_checks=" + metadataChecks);
        return result;
    }

    private static void requireHeicBrand(File file) throws IOException {
        byte[] bytes = new byte[(int) Math.min(4096L, file.length())];
        try (FileInputStream input = new FileInputStream(file)) {
            int total = 0;
            while (total < bytes.length) {
                int count = input.read(bytes, total, bytes.length - total);
                if (count < 0) break;
                total += count;
            }
            if (total < 16 || bytes[4] != 'f' || bytes[5] != 't'
                    || bytes[6] != 'y' || bytes[7] != 'p') {
                throw new IOException("file does not contain a HEIF ftyp box: " + file.getName());
            }
            boolean compatible = false;
            for (int offset = 8; offset + 4 <= total; offset += 4) {
                if ((bytes[offset] == 'h' && bytes[offset + 1] == 'e'
                        && ((bytes[offset + 2] == 'i' && (bytes[offset + 3] == 'c'
                        || bytes[offset + 3] == 'x'))
                        || (bytes[offset + 2] == 'v' && (bytes[offset + 3] == 'c'
                        || bytes[offset + 3] == 'x'))))
                        || (bytes[offset] == 'm' && bytes[offset + 1] == 'i'
                        && bytes[offset + 2] == 'f' && bytes[offset + 3] == '1')) {
                    compatible = true;
                    break;
                }
            }
            if (!compatible) {
                throw new IOException("file has no HEIC-compatible ftyp brand: " + file.getName());
            }
        }
    }

    private static boolean metadataMatches(String tag, String expected, String actual) {
        if (expected == null || actual == null) return expected == null && actual == null;
        if (expected.equals(actual)) return true;
        if (!ExifInterface.TAG_EXPOSURE_TIME.equals(tag)
                && !ExifInterface.TAG_F_NUMBER.equals(tag)) return false;
        BigDecimal left = rationalValue(expected);
        BigDecimal right = rationalValue(actual);
        if (left == null || right == null || left.signum() <= 0 || right.signum() <= 0) {
            return false;
        }
        BigDecimal magnitude = left.abs().max(right.abs());
        BigDecimal tolerance = magnitude.multiply(new BigDecimal("1e-9"))
                .max(new BigDecimal("1e-12"));
        return left.subtract(right).abs().compareTo(tolerance) <= 0;
    }

    private static BigDecimal rationalValue(String value) {
        int slash = value.indexOf('/');
        try {
            if (slash < 0) return new BigDecimal(value.trim());
            if (slash == 0 || slash == value.length() - 1
                    || slash != value.lastIndexOf('/')) return null;
            BigDecimal numerator = new BigDecimal(value.substring(0, slash).trim());
            BigDecimal denominator = new BigDecimal(value.substring(slash + 1).trim());
            if (denominator.signum() == 0) return null;
            return numerator.divide(denominator, MathContext.DECIMAL128);
        } catch (NumberFormatException | ArithmeticException ignored) {
            return null;
        }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
        }
        StringBuilder value = new StringBuilder(digest.getDigestLength() * 2);
        for (byte item : digest.digest()) {
            value.append(String.format(java.util.Locale.US, "%02x", item & 0xff));
        }
        return value.toString();
    }
}
