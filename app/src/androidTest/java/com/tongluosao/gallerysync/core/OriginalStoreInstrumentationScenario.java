package com.tongluosao.gallerysync.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.ExifInterface;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Real OriginalStore and preview pipeline checks using only synthetic files in app cache. */
final class OriginalStoreInstrumentationScenario {
    private static final String ROW_ID = "synthetic-row-41";
    private static final String REMOTE_PATH = "/synthetic/original.jpg";
    private static final String SYNTHETIC_CAPTURE_DATE = "2018:04:05 06:07:08";

    String run(Context context) throws Exception {
        File work = new File(context.getCacheDir(), "quality-validation-" + System.nanoTime());
        require(work.mkdirs(), "could not create cache-only test directory");
        try {
            return exercise(work);
        } finally {
            deleteTree(work);
        }
    }

    private String exercise(File work) throws Exception {
        File source = new File(work, "source-with-exif.jpg");
        createSyntheticJpeg(source);
        String heicResult = exerciseHeicEncoder(source, work);
        byte[] original = readAll(source);
        String originalHash = sha256(original);
        String rowSha1 = digest("SHA-1", original);
        File downloaded = new File(work, "downloaded.jpg");
        File preview = new File(work, "preview.jpg");
        File stagedPreview = new File(work, "preview-staged.tmp");
        File derived = new File(work, "derived-copy.jpg");
        File storeDir = new File(work, "registry");

        try (FakeDavServer contentServer = new FakeDavServer(original);
                FakeDavServer server = new FakeDavServer(
                        contentServer.baseUrl() + REMOTE_PATH)) {
            require(!server.baseUrl().equals(contentServer.baseUrl()),
                    "redirect target must use a different port");
            Config config = configFor(server.baseUrl());
            WebDavClient client = new WebDavClient(config.getBaseUrl(),
                    config.getUsername(), config.getPassword());
            try {
                client.get(REMOTE_PATH, downloaded);
            } finally {
                client.close();
            }
            require(Arrays.equals(original, readAll(downloaded)),
                    "fake DAV GET did not preserve the source JPEG bytes");

            require(DownloadQueue.createCompressedPreview(downloaded, preview),
                    "production HQ preview generation failed");
            require(preview.isFile() && preview.length() > 0L
                            && preview.length() < downloaded.length(),
                    "generated preview was not smaller than the original");
            ExifInterface previewExif = new ExifInterface(preview.getAbsolutePath());
            require(SYNTHETIC_CAPTURE_DATE.equals(
                            previewExif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)),
                    "generated preview did not preserve DateTimeOriginal");
            require(previewExif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                            ExifInterface.ORIENTATION_UNDEFINED)
                            == ExifInterface.ORIENTATION_NORMAL,
                    "generated preview orientation was not normalized");
            require("Synthetic Camera".equals(previewExif.getAttribute(ExifInterface.TAG_MAKE)),
                    "generated preview did not preserve camera make");
            require("Unit Test 1".equals(previewExif.getAttribute(ExifInterface.TAG_MODEL)),
                    "generated preview did not preserve camera model");
            require("37/1,46/1,4900/100".equals(
                            previewExif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
                            && "N".equals(previewExif.getAttribute(
                                    ExifInterface.TAG_GPS_LATITUDE_REF)),
                    "generated preview did not preserve GPS coordinates");
            File noMetadataPreview = new File(work, "preview-no-metadata.jpg");
            require(DownloadQueue.createCompressedPreview(downloaded, noMetadataPreview, false),
                    "preview generation with metadata preservation disabled failed");
            ExifInterface noMetadataExif = new ExifInterface(noMetadataPreview.getAbsolutePath());
            require(noMetadataExif.getAttribute(ExifInterface.TAG_MAKE) == null
                            && noMetadataExif.getAttribute(ExifInterface.TAG_GPS_LATITUDE) == null,
                    "metadata-disabled preview retained allowlisted EXIF");
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(preview.getAbsolutePath(), bounds);
            require(bounds.outWidth == 360 && bounds.outHeight == 640,
                    "EXIF orientation was not applied to the preview: "
                            + bounds.outWidth + "x" + bounds.outHeight);
            copy(preview, stagedPreview);

            OriginalStore store = isolatedStore(storeDir);
            store.registerDownloadedPreview(config, REMOTE_PATH, original.length,
                    originalHash, downloaded, preview, stagedPreview);
            require(store.bindIdentity(preview, ROW_ID, rowSha1),
                    "could not bind the synthetic gallery identity");
            require(store.isProtectedUpload(preview, ROW_ID, rowSha1),
                    "registered preview was not blocked from upload");
            exerciseMutationLease(store, config, rowSha1, preview, derived);

            // Reload the on-disk registry to verify that the upload guard survives restart.
            OriginalStore reloaded = isolatedStore(storeDir);
            require(reloaded.isProtectedUpload(preview, ROW_ID, rowSha1)
                            && reloaded.isProtectedUpload(derived, ROW_ID, rowSha1),
                    "persisted marker did not protect preview/derived paths after reload");
            require(Arrays.equals(readAll(preview), readAll(derived)),
                    "derived replacement did not finish after upload lease release");

            File restored = new File(work, "restored.jpg");
            reloaded.restoreOriginal(config, ROW_ID, rowSha1, restored);
            require(Arrays.equals(original, readAll(restored)),
                    "restored original does not match source SHA-256");
            require(originalHash.equals(sha256(readAll(restored))),
                    "restored original hash mismatch");

            byte[] sentinel = "keep-existing-local-file".getBytes(StandardCharsets.UTF_8);
            contentServer.setPayload(Arrays.copyOf(original, original.length - 1));
            File wrongLength = new File(work, "existing-length-target.jpg");
            write(wrongLength, sentinel);
            require(reloaded.registerDerivedPath(config, ROW_ID, rowSha1, wrongLength),
                    "could not register existing length-test preview");
            expectRestoreFailure(reloaded, config, rowSha1, wrongLength,
                    "wrong-length remote original unexpectedly restored");
            require(Arrays.equals(sentinel, readAll(wrongLength)),
                    "length mismatch overwrote the existing local file");

            byte[] wrongHashPayload = original.clone();
            wrongHashPayload[wrongHashPayload.length - 2] ^= 0x1;
            contentServer.setPayload(wrongHashPayload);
            File wrongHash = new File(work, "existing-hash-target.jpg");
            write(wrongHash, sentinel);
            require(reloaded.registerDerivedPath(config, ROW_ID, rowSha1, wrongHash),
                    "could not register existing hash-test preview");
            expectRestoreFailure(reloaded, config, rowSha1, wrongHash,
                    "wrong-hash remote original unexpectedly restored");
            require(Arrays.equals(sentinel, readAll(wrongHash)),
                    "hash mismatch overwrote the existing local file");

            contentServer.setPayload(original);
            File unrelated = new File(work, "unrelated-user-file.jpg");
            write(unrelated, sentinel);
            expectRestoreFailure(reloaded, config, rowSha1, unrelated,
                    "valid remote original overwrote an unrelated local file");
            require(Arrays.equals(sentinel, readAll(unrelated)),
                    "unrecognized local bytes were changed by restore");
            reloaded.restoreOriginal(config, ROW_ID, rowSha1, preview);
            require(Arrays.equals(original, readAll(preview)),
                    "recognized HQ preview was not restored to original bytes");

            File uploadSource = new File(work, "upload-source.jpg");
            write(uploadSource, original);
            long sourceMtime = uploadSource.lastModified();
            // An async completion must retain the bytes fingerprint captured before PUT.
            write(uploadSource, sentinel);
            reloaded.registerUploadedOriginal(config, "/synthetic/uploaded.jpg", uploadSource,
                    "async-source", "async-sha1", originalHash, original.length, sourceMtime);
            OriginalStore.Record uploadedRecord = reloaded.find(config, "async-source", "async-sha1");
            require(uploadedRecord != null && originalHash.equals(uploadedRecord.sha256)
                            && uploadedRecord.originalLength == original.length,
                    "async registration used a mutable local fingerprint");
            require(reloaded.isProtectedUpload(uploadSource, "async-source", "async-sha1"),
                    "changed uploaded source was not blocked from upload");

            require(!server.requests().isEmpty(), "fake DAV server received no requests");
            require(!contentServer.requests().isEmpty(),
                    "cross-origin redirect target received no GET");
            for (FakeDavServer.RequestRecord request : server.requests()) {
                require(request.method.equals("GET"),
                        "unexpected fake-server method (GET only): " + request.line);
                require(request.authorization != null && !request.authorization.isEmpty(),
                        "configured synthetic auth was not sent to the original DAV endpoint");
            }
            for (FakeDavServer.RequestRecord request : contentServer.requests()) {
                require(request.method.equals("GET"),
                        "unexpected redirect-target method (GET only): " + request.line);
                require(request.authorization == null || request.authorization.isEmpty(),
                        "Authorization leaked across the cross-port HTTP redirect");
            }
        }
        return heicResult;
    }

    /** Exercise actual platform encoding, while reporting missing device support explicitly. */
    private static String exerciseHeicEncoder(File source, File work) throws Exception {
        File output = new File(work, "synthetic-preview.heic");
        byte[] sentinel = "leave-existing-output-untouched".getBytes(StandardCharsets.US_ASCII);
        write(output, sentinel);
        boolean advertised = HeicPreviewEncoder.isPlatformEncoderAdvertised();
        boolean encoded = HeicPreviewEncoder.encode(source, output,
                HeicPreviewEncoder.DEFAULT_MAX_EDGE, HeicPreviewEncoder.DEFAULT_QUALITY, true);
        if (!encoded) {
            require(Arrays.equals(sentinel, readAll(output)),
                    "failed HEIC encode changed the pre-existing output file");
            String failure = HeicPreviewEncoder.getLastFailureReason();
            String status = advertised
                    ? "HEIC_NOT_VERIFIED: " + failure
                    : "HEIC_UNSUPPORTED: no flexible-YUV HEIC encoder advertised; helper returned false"
                            + (failure == null ? "" : " (" + failure + ")");
            android.util.Log.w("QualityValidation", status);
            return status;
        }

        require(output.isFile() && output.length() > 16L,
                "HEIC helper succeeded without producing a non-empty output");
        require(hasHeicFtypBrand(output), "HEIC output has no HEIC-compatible ftyp brand");
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(output.getAbsolutePath(), bounds);
        require(bounds.outWidth == 360 && bounds.outHeight == 640,
                "HEIC output did not bake in source orientation: "
                        + bounds.outWidth + "x" + bounds.outHeight);
        BitmapFactory.Options decodeOptions = new BitmapFactory.Options();
        decodeOptions.inSampleSize = 4;
        Bitmap decoded = BitmapFactory.decodeFile(output.getAbsolutePath(), decodeOptions);
        require(decoded != null, "HEIC output could not be decoded by the device");
        decoded.recycle();

        ExifInterface exif = new ExifInterface(output.getAbsolutePath());
        require("Synthetic Camera".equals(exif.getAttribute(ExifInterface.TAG_MAKE)),
                "HEIC output did not preserve Make");
        require("Unit Test 1".equals(exif.getAttribute(ExifInterface.TAG_MODEL)),
                "HEIC output did not preserve Model");
        require(SYNTHETIC_CAPTURE_DATE.equals(
                        exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)),
                "HEIC output did not preserve DateTimeOriginal");
        require("37/1,46/1,4900/100".equals(
                        exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
                        && "N".equals(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE_REF))
                        && "122/1,25/1,0/1".equals(
                                exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE))
                        && "E".equals(exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF)),
                "HEIC output did not preserve GPS coordinates");
        require(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_UNDEFINED) == ExifInterface.ORIENTATION_NORMAL,
                "HEIC output orientation metadata was not normalized");
        String status = "HEIC_ENCODE_VERIFIED: real HEIC signature, decode, orientation and EXIF passed";
        android.util.Log.i("QualityValidation", status);
        return status;
    }

    private static boolean hasHeicFtypBrand(File file) throws IOException {
        byte[] bytes = new byte[(int) Math.min(4096L, file.length())];
        try (InputStream input = new FileInputStream(file)) {
            int total = 0;
            while (total < bytes.length) {
                int count = input.read(bytes, total, bytes.length - total);
                if (count < 0) break;
                total += count;
            }
            if (total < 16 || bytes[4] != 'f' || bytes[5] != 't'
                    || bytes[6] != 'y' || bytes[7] != 'p') return false;
            long boxSize = ((bytes[0] & 0xffL) << 24) | ((bytes[1] & 0xffL) << 16)
                    | ((bytes[2] & 0xffL) << 8) | (bytes[3] & 0xffL);
            if (boxSize < 16 || boxSize == 1) return false;
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

    private static Config configFor(String baseUrl) {
        Map<String, Object> values = new HashMap<>();
        values.put(Config.KEY_BASE_URL, baseUrl);
        values.put(Config.KEY_USERNAME, "synthetic-user");
        values.put(Config.KEY_PASSWORD, "synthetic-password");
        values.put(Config.KEY_REMOTE_ROOT, "synthetic-root");
        return Config.from(new MemoryPreferences(values));
    }

    private static OriginalStore isolatedStore(File directory) throws Exception {
        Constructor<OriginalStore> constructor = OriginalStore.class.getDeclaredConstructor(File.class);
        constructor.setAccessible(true);
        return constructor.newInstance(directory);
    }

    private static void exerciseMutationLease(OriginalStore store, Config config, String rowSha1,
            File preview, File derived) throws Exception {
        byte[] before = "synthetic-before-mutation".getBytes(StandardCharsets.UTF_8);
        write(derived, before);
        require(!store.isProtectedUpload(derived, ROW_ID, rowSha1),
                "derived candidate was protected before mutation registration");

        CountDownLatch mutationAttempted = new CountDownLatch(1);
        CountDownLatch mutationAcquired = new CountDownLatch(1);
        CountDownLatch writerFinished = new CountDownLatch(1);
        AtomicReference<Throwable> writerFailure = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            mutationAttempted.countDown();
            try (OriginalStore.PathLease ignored = store.beginDerivedMutation(
                    config, ROW_ID, rowSha1, derived)) {
                if (!store.isProtectedUpload(derived, ROW_ID, rowSha1)) {
                    throw new AssertionError("writer lease did not persist upload protection");
                }
                mutationAcquired.countDown();
                copy(preview, derived);
            } catch (Throwable error) {
                writerFailure.set(error);
            } finally {
                writerFinished.countDown();
            }
        }, "synthetic-derived-mutation");

        OriginalStore.PathLease uploadLease = store.lockForUpload(derived);
        boolean acquiredWhileUploading;
        try {
            writer.start();
            require(mutationAttempted.await(5, TimeUnit.SECONDS),
                    "derived writer did not attempt its lease");
            acquiredWhileUploading = mutationAcquired.await(1, TimeUnit.SECONDS);
            require(!acquiredWhileUploading,
                    "derived replacement began while an upload held the read lease");
            require(Arrays.equals(before, readAll(derived)),
                    "derived bytes changed while an upload held the read lease");
        } finally {
            uploadLease.close();
        }

        require(mutationAcquired.await(5, TimeUnit.SECONDS),
                "derived writer did not acquire its lease after upload release");
        require(writerFinished.await(5, TimeUnit.SECONDS),
                "derived writer did not finish after upload release");
        writer.join(1_000L);
        require(!writer.isAlive(), "derived writer thread did not stop");
        Throwable failure = writerFailure.get();
        if (failure != null) {
            throw new AssertionError("derived mutation failed", failure);
        }
        require(store.isProtectedUpload(derived, ROW_ID, rowSha1),
                "derived path was not blocked after replacement");
    }

    private static void createSyntheticJpeg(File output) throws Exception {
        final int width = 640;
        final int height = 360;
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        int[] pixels = new int[width * height];
        Random random = new Random(0x51a7c0deL);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int noise = random.nextInt(64);
                int red = (x * 255 / width + noise) & 0xff;
                int green = (y * 255 / height + noise) & 0xff;
                int blue = ((x ^ y) + noise * 3) & 0xff;
                pixels[y * width + x] = 0xff000000 | (red << 16) | (green << 8) | blue;
            }
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
        try (FileOutputStream out = new FileOutputStream(output)) {
            require(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out),
                    "could not encode synthetic JPEG");
            out.getFD().sync();
        } finally {
            bitmap.recycle();
        }
        ExifInterface exif = new ExifInterface(output.getAbsolutePath());
        exif.setAttribute(ExifInterface.TAG_ORIENTATION,
                Integer.toString(ExifInterface.ORIENTATION_ROTATE_90));
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, SYNTHETIC_CAPTURE_DATE);
        exif.setAttribute(ExifInterface.TAG_MAKE, "Synthetic Camera");
        exif.setAttribute(ExifInterface.TAG_MODEL, "Unit Test 1");
        exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, "37/1,46/1,4900/100");
        exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N");
        exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "122/1,25/1,0/1");
        exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "E");
        exif.saveAttributes();
        ExifInterface verify = new ExifInterface(output.getAbsolutePath());
        require(verify.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_UNDEFINED)
                        == ExifInterface.ORIENTATION_ROTATE_90,
                "synthetic JPEG does not contain the requested EXIF orientation");
        require(SYNTHETIC_CAPTURE_DATE.equals(
                        verify.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)),
                "synthetic JPEG does not contain the fixed DateTimeOriginal");
    }

    private static void expectRestoreFailure(OriginalStore store, Config config, String rowSha1,
            File destination, String failureMessage) throws Exception {
        try {
            store.restoreOriginal(config, ROW_ID, rowSha1, destination);
            throw new AssertionError(failureMessage);
        } catch (IOException expected) {
            // A failed verification must leave the caller's existing file intact.
        }
    }

    private static void copy(File from, File to) throws IOException {
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) {
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = in.read(buffer)) >= 0) {
                out.write(buffer, 0, count);
            }
            out.flush();
        }
    }

    private static void write(File file, byte[] data) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(data);
            out.getFD().sync();
        }
    }

    private static byte[] readAll(File file) throws IOException {
        try (InputStream in = new FileInputStream(file); ByteArrayOutputStream out =
                new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = in.read(buffer)) >= 0) {
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        }
    }

    private static String sha256(byte[] data) throws Exception {
        return digest("SHA-256", data);
    }

    private static String digest(String algorithm, byte[] data) throws Exception {
        byte[] digest = MessageDigest.getInstance(algorithm).digest(data);
        StringBuilder text = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            text.append(String.format(java.util.Locale.US, "%02x", value & 0xff));
        }
        return text.toString();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteTree(child);
            }
        }
        file.delete();
    }

    private static final class FakeDavServer implements Closeable {
        static final class RequestRecord {
            final String method;
            final String line;
            final String authorization;

            RequestRecord(String line, String method, String authorization) {
                this.line = line;
                this.method = method;
                this.authorization = authorization;
            }
        }

        private final ServerSocket server;
        private final Thread thread;
        private final CopyOnWriteArrayList<RequestRecord> requests = new CopyOnWriteArrayList<>();
        private volatile byte[] payload;
        private final String redirectTo;
        private volatile boolean closed;

        FakeDavServer(byte[] initialPayload) throws IOException {
            this(initialPayload, null);
        }

        FakeDavServer(String redirectTo) throws IOException {
            this(new byte[0], redirectTo);
        }

        private FakeDavServer(byte[] initialPayload, String redirectTo) throws IOException {
            payload = initialPayload.clone();
            this.redirectTo = redirectTo;
            server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
            thread = new Thread(this::serve, "synthetic-fake-dav");
            thread.setDaemon(true);
            thread.start();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getLocalPort();
        }

        void setPayload(byte[] next) {
            payload = next.clone();
        }

        java.util.List<RequestRecord> requests() {
            return new ArrayList<>(requests);
        }

        private void serve() {
            while (!closed) {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(5_000);
                    handle(socket);
                } catch (IOException error) {
                    if (!closed) {
                        android.util.Log.e("QualityValidation", "fake DAV server error", error);
                    }
                }
            }
        }

        private void handle(Socket socket) throws IOException {
            InputStream input = socket.getInputStream();
            String requestLine = readLine(input);
            if (requestLine == null || requestLine.isEmpty()) {
                return;
            }
            String[] parts = requestLine.split(" ", 3);
            String method = parts.length == 0 ? "" : parts[0];
            String authorization = null;
            String line;
            do {
                line = readLine(input);
                if (line != null && line.regionMatches(true, 0, "Authorization:", 0, 14)) {
                    authorization = line.substring(14).trim();
                }
            } while (line != null && !line.isEmpty());
            requests.add(new RequestRecord(requestLine, method, authorization));

            boolean redirect = "GET".equals(method) && redirectTo != null;
            byte[] body = "GET".equals(method) && !redirect
                    ? payload.clone() : new byte[0];
            int status = redirect ? 302 : "GET".equals(method) ? 200 : 405;
            String reason = status == 302 ? "Found"
                    : status == 200 ? "OK" : "Method Not Allowed";
            OutputStream output = socket.getOutputStream();
            String headers = "HTTP/1.1 " + status + " " + reason + "\r\n"
                    + (redirect ? "Location: " + redirectTo + "\r\n" : "")
                    + "Content-Length: " + body.length + "\r\n"
                    + "Content-Type: application/octet-stream\r\n"
                    + "Connection: close\r\n\r\n";
            output.write(headers.getBytes(StandardCharsets.US_ASCII));
            output.write(body);
            output.flush();
        }

        private static String readLine(InputStream input) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            int previous = -1;
            int current;
            while ((current = input.read()) != -1) {
                if (previous == '\r' && current == '\n') {
                    byte[] line = bytes.toByteArray();
                    return new String(line, 0, Math.max(0, line.length - 1),
                            StandardCharsets.US_ASCII);
                }
                bytes.write(current);
                previous = current;
            }
            return bytes.size() == 0 ? null : bytes.toString("US-ASCII");
        }

        @Override
        public void close() {
            closed = true;
            try {
                server.close();
            } catch (IOException ignored) {
                // Closing the server socket is the shutdown signal for accept().
            }
            try {
                thread.join(1_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** In-memory preferences keep the scenario from reading or changing app configuration. */
    private static final class MemoryPreferences implements SharedPreferences {
        private final Map<String, Object> values;
        private final Set<OnSharedPreferenceChangeListener> listeners = new HashSet<>();

        MemoryPreferences(Map<String, Object> initial) {
            values = new HashMap<>(initial);
        }

        @Override
        public synchronized Map<String, ?> getAll() {
            return Collections.unmodifiableMap(new HashMap<>(values));
        }

        @Override
        public synchronized String getString(String key, String fallback) {
            Object value = values.get(key);
            return value instanceof String ? (String) value : fallback;
        }

        @Override
        @SuppressWarnings("unchecked")
        public synchronized Set<String> getStringSet(String key, Set<String> fallback) {
            Object value = values.get(key);
            return value instanceof Set ? new HashSet<>((Set<String>) value) : fallback;
        }

        @Override
        public synchronized int getInt(String key, int fallback) {
            Object value = values.get(key);
            return value instanceof Integer ? (Integer) value : fallback;
        }

        @Override
        public synchronized long getLong(String key, long fallback) {
            Object value = values.get(key);
            return value instanceof Long ? (Long) value : fallback;
        }

        @Override
        public synchronized float getFloat(String key, float fallback) {
            Object value = values.get(key);
            return value instanceof Float ? (Float) value : fallback;
        }

        @Override
        public synchronized boolean getBoolean(String key, boolean fallback) {
            Object value = values.get(key);
            return value instanceof Boolean ? (Boolean) value : fallback;
        }

        @Override
        public synchronized boolean contains(String key) {
            return values.containsKey(key);
        }

        @Override
        public Editor edit() {
            return new MemoryEditor();
        }

        @Override
        public synchronized void registerOnSharedPreferenceChangeListener(
                OnSharedPreferenceChangeListener listener) {
            listeners.add(listener);
        }

        @Override
        public synchronized void unregisterOnSharedPreferenceChangeListener(
                OnSharedPreferenceChangeListener listener) {
            listeners.remove(listener);
        }

        private final class MemoryEditor implements Editor {
            private final Map<String, Object> writes = new HashMap<>();
            private final Set<String> removals = new HashSet<>();
            private boolean clear;

            @Override
            public Editor putString(String key, String value) {
                return put(key, value);
            }

            @Override
            public Editor putStringSet(String key, Set<String> value) {
                return put(key, value == null ? null : new HashSet<>(value));
            }

            @Override
            public Editor putInt(String key, int value) {
                return put(key, value);
            }

            @Override
            public Editor putLong(String key, long value) {
                return put(key, value);
            }

            @Override
            public Editor putFloat(String key, float value) {
                return put(key, value);
            }

            @Override
            public Editor putBoolean(String key, boolean value) {
                return put(key, value);
            }

            private Editor put(String key, Object value) {
                writes.put(key, value);
                removals.remove(key);
                return this;
            }

            @Override
            public Editor remove(String key) {
                writes.remove(key);
                removals.add(key);
                return this;
            }

            @Override
            public Editor clear() {
                clear = true;
                return this;
            }

            @Override
            public boolean commit() {
                apply();
                return true;
            }

            @Override
            public void apply() {
                synchronized (MemoryPreferences.this) {
                    if (clear) {
                        values.clear();
                    }
                    for (String key : removals) {
                        values.remove(key);
                    }
                    values.putAll(writes);
                }
            }
        }
    }
}
