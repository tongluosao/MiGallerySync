package com.tongluosao.gallerysync.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

public class WebDavClientRangeStreamTest {
    @Test(timeout = 10_000L)
    public void rangeCallsUseIndependentConnectionsAndReturnOnlyRequestedBytes() throws Exception {
        byte[] source = "0123456789".getBytes(StandardCharsets.US_ASCII);
        List<String> ranges = new ArrayList<>();
        try (ServerSocket server = new ServerSocket(0)) {
            Thread worker = new Thread(() -> {
                try {
                    for (int i = 0; i < 2; i++) {
                        try (Socket socket = server.accept()) {
                            String range = readHeader(socket, "Range");
                            ranges.add(range);
                            String[] bounds = range.substring("bytes=".length()).split("-");
                            int start = Integer.parseInt(bounds[0]);
                            int end = Integer.parseInt(bounds[1]);
                            byte[] body = java.util.Arrays.copyOfRange(source, start, end + 1);
                            writeResponse(socket.getOutputStream(), 206,
                                    "Content-Range: bytes " + start + "-" + end + "/10\r\n",
                                    body);
                        }
                    }
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
            worker.setDaemon(true);
            worker.start();

            WebDavClient client = new WebDavClient(
                    "http://127.0.0.1:" + server.getLocalPort() + "/dav", null, null);
            try (WebDavClient.RangeResponse first = client.streamRange("movie.mp4", 1, 3)) {
                assertEquals(206, first.statusCode);
                assertEquals("bytes 1-3/10", first.contentRange);
                assertArrayEquals("123".getBytes(StandardCharsets.US_ASCII), readAll(first.body()));
            }
            try (WebDavClient.RangeResponse seek = client.streamRange("movie.mp4", 6, 8)) {
                assertArrayEquals("678".getBytes(StandardCharsets.US_ASCII), readAll(seek.body()));
            }
            client.close();
            worker.join(3_000L);
            assertFalse(worker.isAlive());
            assertEquals(java.util.Arrays.asList("bytes=1-3", "bytes=6-8"), ranges);
        }
    }

    @Test(timeout = 10_000L)
    public void crossOriginRedirectDropsWebDavAuthorizationButKeepsRange() throws Exception {
        try (ServerSocket origin = new ServerSocket(0);
             ServerSocket storage = new ServerSocket(0)) {
            String[] seen = new String[2];
            Thread storageWorker = new Thread(() -> {
                try (Socket socket = storage.accept()) {
                    Map<String, String> headers = readHeaders(socket);
                    seen[1] = headers.get("authorization");
                    assertEquals("bytes=2-4", headers.get("range"));
                    writeResponse(socket.getOutputStream(), 206,
                            "Content-Range: bytes 2-4/10\r\n",
                            "abc".getBytes(StandardCharsets.US_ASCII));
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
            Thread originWorker = new Thread(() -> {
                try (Socket socket = origin.accept()) {
                    seen[0] = readHeader(socket, "Authorization");
                    String location = "http://127.0.0.1:" + storage.getLocalPort()
                            + "/signed/video?token=private-value";
                    byte[] response = ("HTTP/1.1 302 Found\r\nLocation: " + location
                            + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                            .getBytes(StandardCharsets.ISO_8859_1);
                    socket.getOutputStream().write(response);
                    socket.getOutputStream().flush();
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
            storageWorker.setDaemon(true);
            originWorker.setDaemon(true);
            storageWorker.start();
            originWorker.start();

            WebDavClient client = new WebDavClient(
                    "http://127.0.0.1:" + origin.getLocalPort() + "/dav", "Basic secret");
            try (WebDavClient.RangeResponse response = client.streamRange("movie.mp4", 2, 4)) {
                assertEquals(206, response.statusCode);
                assertArrayEquals("abc".getBytes(StandardCharsets.US_ASCII),
                        readAll(response.body()));
            }
            originWorker.join(3_000L);
            storageWorker.join(3_000L);
            assertEquals("Basic secret", seen[0]);
            assertEquals(null, seen[1]);
        }
    }

    @Test(timeout = 10_000L)
    public void closingRangeResponseCancelsABlockedRead() throws Exception {
        CountDownLatch headersSent = new CountDownLatch(1);
        try (ServerSocket server = new ServerSocket(0)) {
            Thread worker = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    readHeader(socket, "Range");
                    byte[] headers = ("HTTP/1.1 206 Partial Content\r\n"
                            + "Content-Length: 6\r\nContent-Range: bytes 0-5/6\r\n"
                            + "Connection: keep-alive\r\n\r\n")
                            .getBytes(StandardCharsets.ISO_8859_1);
                    socket.getOutputStream().write(headers);
                    socket.getOutputStream().flush();
                    headersSent.countDown();
                    Thread.sleep(5_000L);
                } catch (Exception ignored) { }
            });
            worker.setDaemon(true);
            worker.start();

            WebDavClient client = new WebDavClient(
                    "http://127.0.0.1:" + server.getLocalPort(), null, null);
            WebDavClient.RangeResponse response = client.streamRange("movie.mp4", 0, 5);
            assertTrue(headersSent.await(1, TimeUnit.SECONDS));
            CountDownLatch readStarted = new CountDownLatch(1);
            Thread blockedRead = new Thread(() -> {
                readStarted.countDown();
                try {
                    response.body().read();
                } catch (Exception expected) { }
            });
            blockedRead.setDaemon(true);
            blockedRead.start();
            assertTrue(readStarted.await(1, TimeUnit.SECONDS));
            response.close();
            blockedRead.join(2_000L);
            assertFalse("closing the response should cancel socket reads", blockedRead.isAlive());
        }
    }

    @Test(timeout = 10_000L)
    public void closingRangeCallCancelsWhileWaitingForResponseHeaders() throws Exception {
        CountDownLatch requestReceived = new CountDownLatch(1);
        try (ServerSocket server = new ServerSocket(0)) {
            Thread worker = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    readHeader(socket, "Range");
                    requestReceived.countDown();
                    while (socket.getInputStream().read() >= 0) {
                        // Wait until the client cancels the pending response.
                    }
                } catch (Exception ignored) { }
            });
            worker.setDaemon(true);
            worker.start();

            WebDavClient client = new WebDavClient(
                    "http://127.0.0.1:" + server.getLocalPort(), null, null);
            WebDavClient.RangeCall call = client.newRangeCall("movie.mp4", 0, 5);
            CountDownLatch openStarted = new CountDownLatch(1);
            Thread opening = new Thread(() -> {
                openStarted.countDown();
                try {
                    call.open();
                } catch (Exception expected) { }
            });
            opening.setDaemon(true);
            opening.start();
            assertTrue(openStarted.await(1, TimeUnit.SECONDS));
            assertTrue(requestReceived.await(2, TimeUnit.SECONDS));
            call.close();
            opening.join(2_000L);
            assertFalse("closing a pending call should cancel header reads", opening.isAlive());
        }
    }

    @Test(timeout = 10_000L)
    public void cancelledRangeHandleRejectsSocketRegisteredAfterClose() throws Exception {
        WebDavClient client = new WebDavClient("http://127.0.0.1:9", null, null);
        WebDavClient.RangeCall call = client.newRangeCall("movie.mp4", 0, 1);
        Field firstClientField = WebDavClient.RangeCall.class.getDeclaredField("firstClient");
        firstClientField.setAccessible(true);
        WebDavClient isolated = (WebDavClient) firstClientField.get(call);
        TestNetworkContext context = new TestNetworkContext();
        WebDavClient.setHostContext(context);
        try {
            call.close();
            Method plainSocket = WebDavClient.class.getDeclaredMethod("plainSocket",
                    String.class, int.class, WebDavClient.RangeCall.class);
            plainSocket.setAccessible(true);
            try {
                plainSocket.invoke(isolated, "127.0.0.1", 9, call);
                fail("a cancelled range must not create a connection");
            } catch (InvocationTargetException expected) {
                assertTrue(expected.getCause() instanceof IOException);
            }

            assertEquals("cancelled setup must stop before network resolution", 0,
                    context.getSystemServiceCalls);
            Field pendingSocket = WebDavClient.class.getDeclaredField("mPendingSocket");
            pendingSocket.setAccessible(true);
            assertNull(pendingSocket.get(isolated));
        } finally {
            WebDavClient.setHostContext(null);
            call.close();
        }
    }

    /** Exposes accidental resolver entry without needing Android Context implementations. */
    public static final class TestNetworkContext {
        volatile int getSystemServiceCalls;

        public Object getSystemService(String name) {
            getSystemServiceCalls++;
            return null;
        }
    }

    private static String readHeader(Socket socket, String wanted) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(),
                StandardCharsets.ISO_8859_1));
        String line;
        String value = null;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).equalsIgnoreCase(wanted)) {
                value = line.substring(colon + 1).trim();
            }
        }
        return value;
    }

    /** Read a complete request header block once; each request has only one buffered reader. */
    private static Map<String, String> readHeaders(Socket socket) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(),
                StandardCharsets.ISO_8859_1));
        Map<String, String> headers = new HashMap<>();
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.put(line.substring(0, colon).trim().toLowerCase(java.util.Locale.US),
                        line.substring(colon + 1).trim());
            }
        }
        return headers;
    }

    private static void writeResponse(OutputStream output, int code, String extra,
            byte[] body) throws Exception {
        String headers = "HTTP/1.1 " + code + (code == 206 ? " Partial Content\r\n" : " OK\r\n")
                + extra + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        output.write(headers.getBytes(StandardCharsets.ISO_8859_1));
        output.write(body);
        output.flush();
    }

    private static byte[] readAll(InputStream input) throws Exception {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[64];
            int count;
            while ((count = stream.read(buffer)) >= 0) {
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }
}
