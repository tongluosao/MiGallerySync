package com.tongluosao.gallerysync.core;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.Log;

import androidx.exifinterface.media.ExifInterface;

import android.os.Bundle;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;

/** Reads only ExposureTime from one cache-only HEIC and tests an EXIF JPEG round trip. */
final class ExposureTimeRoundTripInstrumentation {
    private static final String TAG = "ExposureTimeRoundTrip";
    private static final String PROBE_DIRECTORY = "photo-quality-probe";
    private static final String INPUT_DIRECTORY = "incoming";

    private ExposureTimeRoundTripInstrumentation() {}

    static Bundle runProbe(Context instrumentationContext, String inputName) throws Exception {
        if (instrumentationContext == null) {
            throw new IllegalArgumentException("test context missing");
        }
        if (inputName == null || !inputName.matches("[A-Za-z0-9_.-]+\\.(?i:heic|heif)")) {
            throw new IllegalArgumentException("input must be one HEIC/HEIF basename");
        }

        File cache = instrumentationContext.getCacheDir().getCanonicalFile();
        File inputDirectory = new File(new File(cache, PROBE_DIRECTORY), INPUT_DIRECTORY)
                .getCanonicalFile();
        File input = new File(inputDirectory, inputName).getCanonicalFile();
        if (!input.getPath().startsWith(inputDirectory.getPath() + File.separator)) {
            throw new IOException("input outside private diagnostic directory");
        }
        if (!input.isFile() || input.length() == 0L) {
            throw new IOException("private diagnostic input missing");
        }

        ExifInterface sourceExif = new ExifInterface(input.getAbsolutePath());
        String sourceValue = sourceExif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME);
        BigDecimal sourceNumber = parseNumber(sourceValue);
        if (sourceNumber == null) {
            throw new IOException("source ExposureTime missing or invalid");
        }

        File jpeg = File.createTempFile("exposure-time-roundtrip-", ".jpg", cache);
        try {
            writeSyntheticJpeg(jpeg);
            ExifInterface jpegExif = new ExifInterface(jpeg.getAbsolutePath());
            jpegExif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, sourceValue);
            jpegExif.saveAttributes();

            String jpegValue = new ExifInterface(jpeg.getAbsolutePath())
                    .getAttribute(ExifInterface.TAG_EXPOSURE_TIME);
            BigDecimal jpegNumber = parseNumber(jpegValue);
            if (jpegNumber == null) {
                throw new IOException("JPEG ExposureTime missing or invalid after round trip");
            }
            String delta = jpegNumber.subtract(sourceNumber).stripTrailingZeros().toPlainString();

            Bundle result = new Bundle();
            result.putString("status", "EXPOSURE_TIME_ROUNDTRIP");
            result.putString("source_exposure_time", sourceValue);
            result.putString("jpeg_exposure_time", jpegValue);
            result.putString("delta", delta);
            Log.i(TAG, "status=EXPOSURE_TIME_ROUNDTRIP source=" + sourceValue
                    + " jpeg=" + jpegValue + " delta=" + delta);
            return result;
        } finally {
            // Keep the original cache copy for the operator's post-run integrity check.
            if (jpeg.exists() && !jpeg.delete()) {
                Log.w(TAG, "could not remove temporary synthetic JPEG");
            }
        }
    }

    private static void writeSyntheticJpeg(File jpeg) throws IOException {
        Bitmap bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.RGB_565);
        try {
            bitmap.eraseColor(Color.GRAY);
            try (FileOutputStream output = new FileOutputStream(jpeg)) {
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)) {
                    throw new IOException("synthetic JPEG creation failed");
                }
                output.getFD().sync();
            }
        } finally {
            bitmap.recycle();
        }
    }

    private static BigDecimal parseNumber(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        int slash = trimmed.indexOf('/');
        try {
            if (slash < 0) return new BigDecimal(trimmed);
            if (slash == 0 || slash == trimmed.length() - 1
                    || slash != trimmed.lastIndexOf('/')) return null;
            BigDecimal numerator = new BigDecimal(trimmed.substring(0, slash).trim());
            BigDecimal denominator = new BigDecimal(trimmed.substring(slash + 1).trim());
            if (denominator.signum() == 0) return null;
            return numerator.divide(denominator, MathContext.DECIMAL128);
        } catch (NumberFormatException | ArithmeticException ignored) {
            return null;
        }
    }
}
