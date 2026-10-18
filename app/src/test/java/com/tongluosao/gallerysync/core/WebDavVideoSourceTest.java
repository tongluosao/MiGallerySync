package com.tongluosao.gallerysync.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

public class WebDavVideoSourceTest {
    @Test
    public void loopbackRelayServesHeadAndRandomRangesWithTokenGate() throws Exception {
        CountDownLatch upstreamAccepted = new CountDownLatch(1);
        try (ServerSocket upstream = new ServerSocket(0)) {
            Thread server = new Thread(() -> {
                try (Socket socket = upstream.accept()) {
                    upstreamAccepted.countDown();
                    String range = requestHeader(socket, "Range");
                    assertEquals("bytes=2-5", range);
                    byte[] bytes = "0123456789".getBytes(StandardCharsets.US_ASCII);
                    writeResponse(socket.getOutputStream(), 206,
                            "Content-Range: bytes 2-5/10\r\n",
                            java.util.Arrays.copyOfRange(bytes, 2, 6));
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
            server.setDaemon(true);
            server.start();

            WebDavClient client = new WebDavClient(
                    "http://127.0.0.1:" + upstream.getLocalPort() + "/dav", null, null);
            WebDavVideoSource source = new WebDavVideoSource(client, "movie.mp4", 10L);
            String playbackUrl = source.start();
            URL endpoint = new URL(playbackUrl);
            assertEquals("127.0.0.1", endpoint.getHost());
            assertTrue(endpoint.getPath().matches("/[A-Za-z0-9_-]{32}/movie\\.mp4"));

            HttpResponse head = request(playbackUrl, "HEAD", null, endpoint.getPath());
            assertEquals(200, head.code);
            assertEquals("10", head.header("Content-Length"));
            assertEquals(1L, upstreamAccepted.getCount());

            HttpResponse missing = request(playbackUrl, "GET", null,
                    "/wrong-token/movie.mp4");
            assertEquals(404, missing.code);
            HttpResponse method = request(playbackUrl, "PUT", null, endpoint.getPath());
            assertEquals(405, method.code);

            HttpResponse range = request(playbackUrl, "GET", "bytes=2-5", endpoint.getPath());
            assertEquals(206, range.code);
            assertEquals("bytes 2-5/10", range.header("Content-Range"));
            assertArrayEquals("2345".getBytes(StandardCharsets.US_ASCII), range.body);
            assertTrue(upstreamAccepted.await(1, TimeUnit.SECONDS));
            server.join(2_000L);
            assertFalse(server.isAlive());
            source.close();
        }
    }

    @Test
    public void rangeIgnoredByWebDavServerIsNotReportedAsPartialContent() throws Exception {
        try (ServerSocket upstream = new ServerSocket(0)) {
            Thread server = new Thread(() -> {
                try (Socket socket = upstream.accept()) {
                    assertEquals("bytes=2-5", requestHeader(socket, "Range"));
                    writeResponse(socket.getOutputStream(), 200, "",
                            "0123456789".getBytes(StandardCharsets.US_ASCII));
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
            server.setDaemon(true);
            server.start();

            WebDavVideoSource source = new WebDavVideoSource(new WebDavClient(
                    "http://127.0.0.1:" + upstream.getLocalPort() + "/dav", null, null),
                    "movie.mp4", 10L);
            String playbackUrl = source.start();
            URL endpoint = new URL(playbackUrl);
            HttpResponse response = request(playbackUrl, "GET", "bytes=2-5", endpoint.getPath());
            assertEquals(502, response.code);
            assertEquals("0", response.header("Content-Length"));
            source.close();
        }
    }

    @Test
    public void relayRejectsShortRangeAndMismatchedResourceLength() throws Exception {
        assertRangeRejected("bytes 2-4/10", "234".getBytes(StandardCharsets.US_ASCII));
        assertRangeRejected("bytes 2-5/9", "2345".getBytes(StandardCharsets.US_ASCII));
    }

    private static void assertRangeRejected(String contentRange, byte[] body) throws Exception {
        try (ServerSocket upstream = new ServerSocket(0)) {
            Thread server = new Thread(() -> {
                try (Socket socket = upstream.accept()) {
                    assertEquals("bytes=2-5", requestHeader(socket, "Range"));
                    writeResponse(socket.getOutputStream(), 206,
                            "Content-Range: " + contentRange + "\r\n", body);
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
            server.setDaemon(true);
            server.start();

            WebDavVideoSource source = new WebDavVideoSource(new WebDavClient(
                    "http://127.0.0.1:" + upstream.getLocalPort() + "/dav", null, null),
                    "movie.mp4", 10L);
            String playbackUrl = source.start();
            URL endpoint = new URL(playbackUrl);
            HttpResponse response = request(playbackUrl, "GET", "bytes=2-5", endpoint.getPath());
            assertEquals(502, response.code);
            assertEquals("0", response.header("Content-Length"));
            source.close();
        }
    }

    private static HttpResponse request(String url, String method, String range, String path)
            throws Exception {
        URL endpoint = new URL(url);
        try (Socket socket = new Socket(InetAddress.getByName(endpoint.getHost()),
                endpoint.getPort())) {
            OutputStream output = socket.getOutputStream();
            StringBuilder request = new StringBuilder()
                    .append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
                    .append("Host: 127.0.0.1\r\n");
            if (range != null) request.append("Range: ").append(range).append("\r\n");
            request.append("Connection: close\r\n\r\n");
            output.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
            output.flush();
            InputStream input = socket.getInputStream();
            String status = readLine(input);
            int code = Integer.parseInt(status.split(" ")[1]);
            java.util.Map<String, String> headers = new java.util.HashMap<>();
            String line;
            while ((line = readLine(input)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    headers.put(line.substring(0, colon).toLowerCase(java.util.Locale.US),
                            line.substring(colon + 1).trim());
                }
            }
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            if (!"HEAD".equals(method)) {
                long remaining = Long.parseLong(headers.getOrDefault("content-length", "0"));
                byte[] buffer = new byte[64];
                while (remaining > 0L) {
                    int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (count < 0) break;
                    body.write(buffer, 0, count);
                    remaining -= count;
                }
            }
            return new HttpResponse(code, headers, body.toByteArray());
        }
    }

    private static String requestHeader(Socket socket, String name) throws Exception {
        java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                socket.getInputStream(), StandardCharsets.ISO_8859_1));
        String line;
        String found = null;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).equalsIgnoreCase(name)) {
                found = line.substring(colon + 1).trim();
            }
        }
        return found;
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

    private static String readLine(InputStream input) throws Exception {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int value;
        while ((value = input.read()) >= 0) {
            if (value == '\n') break;
            if (value != '\r') line.write(value);
        }
        return value < 0 && line.size() == 0 ? null
                : line.toString(StandardCharsets.ISO_8859_1.name());
    }

    private static final class HttpResponse {
        final int code;
        final java.util.Map<String, String> headers;
        final byte[] body;

        HttpResponse(int code, java.util.Map<String, String> headers, byte[] body) {
            this.code = code;
            this.headers = headers;
            this.body = body;
        }

        String header(String name) {
            return headers.get(name.toLowerCase(java.util.Locale.US));
        }
    }
}
