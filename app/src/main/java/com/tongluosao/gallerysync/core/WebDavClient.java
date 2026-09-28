package com.tongluosao.gallerysync.core;

import android.util.Base64;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * 极简 WebDAV 客户端：MKCOL / PUT / HEAD / OPTIONS / DELETE / PROPFIND，零第三方依赖。
 * 运行在相册进程内，不引入 OkHttp 是为了避免与宿主自带版本冲突并控制模块体积。
 *
 * <p>为什么自己发 HTTP 而不用 {@code HttpURLConnection}：Android 的实现
 * （com.android.okhttp）对请求方法有硬白名单 [OPTIONS, GET, HEAD, POST, PUT, DELETE, TRACE, PATCH]，
 * MKCOL 与 PROPFIND 会在 setRequestMethod 阶段直接抛 ProtocolException，请求根本发不出去。
 * 而"让服务器建目录"必须有 MKCOL —— 用 {@code PUT <dir>/} 代替是不行的：OpenList 挂网盘时它
 * 建出来的是一个 0 字节同名文件，把真目录的路径永久挡死（实测挡死后写文件 404、建子目录 405）。
 *
 * <p>第二个好处是明文 HTTP 不再受平台拦截：targetSdk 28+ 的
 * {@code CleartextURLFilter} 做在平台 HTTP 栈里，而上传跑在相册进程、用的是相册那份
 * network_security_config（4.3.1.6 实测禁明文），模块的 manifest 在那个进程里不被读取。
 * 换自己的出口既绕开了拦截，也一点不碰相册声明的安全边界 —— 反射去改宿主策略不做。
 *
 * <p>TLS 不自己实现：https 用平台 {@code SSLSocketFactory}（系统信任库）包一层，
 * 并在握手前显式开启 {@code endpointIdentificationAlgorithm=HTTPS}，
 * 证书链校验与域名核对仍由平台完成。这一步不能省 —— 裸 SSLSocket 默认**不做**域名核对，
 * 少了它就等于把校验关掉，任何证书都能连上。
 *
 * <p>连接默认复用（HTTP/1.1 keep-alive）。一个文件要发 PUT + HEAD（必要时再 PROPFIND），
 * 一条连接一个请求的话，每张照片都要付两三次 TCP+TLS 握手；上次备份慢主要就慢在这里。
 * 复用的前提是每次都得把响应体排空，否则下一条响应会从半截开始读。排不掉的情况
 * （分块传输、没给 Content-Length、服务器说 close）就当场关掉重新开，不硬凑。
 *
 * <p>不跟随重定向，服务器要重定向就按"复核判不了"处理。
 */
public final class WebDavClient {

    private static final int CONNECT_TIMEOUT = 15_000;
    /** 读超时的固定部分：从写完请求到拿到状态行，正常服务器几秒内一定回 */
    private static final int READ_TIMEOUT_BASE = 30_000;
    /** 读超时按请求体上浮的分母：相当于给 64KB/s 的下限吞吐留够时间 */
    private static final int READ_TIMEOUT_PER_BYTE_MS = 64;
    private static final int READ_TIMEOUT_MAX = 600_000;
    /** 失败响应只取开头这么多字节进日志，够看清原因又不会把整页 HTML 灌进去 */
    private static final int DETAIL_LIMIT = 240;
    /**
     * 落盘复核的次数与间隔。
     *
     * <p>挂载型存储（OpenList 挂网盘）写进去是异步的：PUT 回 2xx 之后立刻问，
     * 有相当比例会问不到 —— 真机上表现为同一批文件里几个固定的小文件反复
     * "PUT returned 2xx but the server has no such file"，重传 7 次仍然失败。
     * 隔一小会儿再问两轮，就能把"还没来得及落盘"和"服务器根本没写"分开。
     * 代价只在复核失败时付，正常路径一次都不等。
     */
    private static final int VERIFY_ATTEMPTS = 3;
    private static final long VERIFY_RETRY_DELAY = 800L;
    private static final String PROPFIND_BODY =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                    + "<propfind xmlns=\"DAV:\"><prop><getcontentlength/><resourcetype/>"
                    + "</prop></propfind>";
    /** 命名空间前缀由服务器决定（OpenList 用 D:，别的用 d: 或没有），只认标签名 */
    private static final Pattern CONTENT_LENGTH = Pattern.compile(
            "<[A-Za-z0-9]*:?getcontentlength[^>]*>\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern NOT_FOUND_STATUS = Pattern.compile(
            "<D:status>[^<]*404", Pattern.CASE_INSENSITIVE);

    private final String mBaseUrl;
    private final String mAuthHeader;
    private final boolean mPlainHttp;
    private final boolean mSecure;

    /** 复用中的连接三件套，同一个客户端连发多个请求时它们一直在；断了就一起置空 */
    private Socket mSocket;
    private OutputStream mOut;
    private BufferedReader mReader;

    /**
     * 解析结果的缓存。
     *
     * <p>目标 host 在一次备份里不会变，但每建一条连接都要解析一次域名。
     * 真机上遇到过解析抖动：同一时刻 adb shell 解析 15/15 全通、TCP 443 可达，
     * 相册进程里却连续抛 UnknownHostException（Java 把 EAI_AGAIN 也报成这个），
     * 整个队列因此空转。解析成功一次就记住地址，后续重连直接用 IP，
     * 抖动就再也影响不到已经在跑的这一轮。
     */
    private String mResolvedHost;
    private InetAddress mResolvedAddress;

    public WebDavClient(String baseUrl, String username, String password) {
        mBaseUrl = trimTrailingSlash(baseUrl);
        mAuthHeader = buildAuthHeader(username, password);
        mPlainHttp = mBaseUrl.regionMatches(true, 0, "http://", 0, 7);
        mSecure = mBaseUrl.regionMatches(true, 0, "https://", 0, 8);
    }

    /** 只读探测：判断服务器可达性与认证，绝不写入任何东西 */
    public int probe(String remotePath) throws IOException {
        return send("OPTIONS", remotePath, null, null, false).code;
    }

    /**
     * 写入是否真的落盘。移动云盘这类挂载会先回 201、再把文件丢掉，
     * 只信状态码就等于"模块以为传完了、云端一个文件都没有、相册又被拦住"。
     *
     * <p>先 HEAD；但 OpenList 这类会把 HEAD 重定向到存储的直链（302、且不给长度），
     * 这时 HEAD 什么也证明不了，改用 PROPFIND Depth 0 问对象本身要长度。
     * 两条路都问不出信息时才按通过处理，避免把不支持这些方法的服务器误杀。
     *
     * <p>问不到不立刻判死：见 {@link #VERIFY_ATTEMPTS}，隔一会儿再问两轮。
     * 判定"确实没有"要 HEAD 与 PROPFIND 都不给活路，只看一条会误伤。
     */
    public boolean storedAs(String remotePath, long expectedSize) throws IOException {
        int headCode = -1;
        long headLength = -1L;
        int propCode = -1;
        long propSize = -1L;
        for (int attempt = 0; attempt < VERIFY_ATTEMPTS; attempt++) {
            if (attempt > 0) {
                sleepQuietly(VERIFY_RETRY_DELAY);
            }

            Res head = send("HEAD", remotePath, null, null, false);
            headCode = head.code;
            headLength = head.length;
            if (head.code == 200 && head.length > 0) {
                if (expectedSize <= 0 || head.length == expectedSize) {
                    return true;
                }
                // 长度对不上就继续往下用 PROPFIND 复核：HEAD 报的是"GET 会返回多大"，
                // 有些服务器在对象刚写进去时会给出还没稳定的值
            }

            // HEAD 被重定向、不给长度、或报 404 时都还不足以定论，改用 PROPFIND 问对象本身
            Res prop = propfind(remotePath, 0);
            if (prop == null) {
                // 服务器不支持 PROPFIND，判不了就按通过处理，不误杀
                return true;
            }
            propCode = prop.code;
            if (prop.code == 404
                    || (prop.body != null && NOT_FOUND_STATUS.matcher(prop.body).find())) {
                continue;
            }
            if (prop.code != 207) {
                // 不是多状态响应，说明这台服务器不用 WebDAV 的语义答话，同样判不了
                return true;
            }
            propSize = contentLengthOf(prop);
            if (expectedSize <= 0 || propSize <= 0 || propSize == expectedSize) {
                return true;
            }
        }
        // 三次都问不到：把证据一起记下来，否则下次只能看到"没有这个文件"，
        // 分不清是服务器真没写、还是复核方式对它不适用
        Logger.w("event=verify_failed path=" + remotePath + " expected=" + expectedSize
                + " head=" + headCode + "/" + headLength
                + " prop=" + propCode + "/" + propSize);
        return false;
    }

    /**
     * PUT 一次；只在服务器明确说"父目录不在"（404/409）时补建目录再试一次。
     * 探测与上传必须走同一条：否则年/月目录还没建起来时，界面报"这个目录收不了文件"
     * 而真实上传其实能成。
     */
    private Res putWithDirFallback(String remotePath, File file, byte[] body)
            throws IOException {
        Res res = send("PUT", remotePath, file, body, false);
        if (res.code == 404 || res.code == 409) {
            String parent = parentOf(remotePath);
            if (!parent.isEmpty()) {
                ensureCollection(parent);
                res = send("PUT", remotePath, file, body, false);
            }
        }
        return res;
    }

    /** 上传文件；父目录不存在时补建目录并重试一次 */
    public boolean put(String remotePath, File localFile) throws IOException {
        Res res = putWithDirFallback(remotePath, localFile, null);
        requireSuccess(res, "PUT", remotePath);
        if (!storedAs(remotePath, localFile.length())) {
            throw new IOException("PUT returned 2xx but the server has no such file"
                    + " path=" + remotePath);
        }
        return true;
    }

    /** 写入一小段内容；失败、以及"回了 2xx 却没落盘"都抛异常。界面用它做真实可写探测 */
    public void putBytes(String remotePath, byte[] body) throws IOException {
        Res res = putWithDirFallback(remotePath, null, body);
        requireSuccess(res, "PUT", remotePath);
        if (!storedAs(remotePath, body.length)) {
            throw new IOException("PUT returned " + res.code
                    + " but the server has no such file (this storage fakes writes)"
                    + " path=" + remotePath);
        }
    }

    /** 删除远端对象；服务器不支持删除时返回 false，调用方按"尽力清理"处理 */
    public boolean delete(String remotePath) throws IOException {
        Res res = send("DELETE", remotePath, null, null, false);
        return isSuccess(res.code) || res.code == 404;
    }

    /**
     * 逐级 MKCOL。失败不抛（认证失败除外）：建目录只是"尽力而为"，
     * 探测请求本身失败（服务器直接掐连接）不该让整次上传作废，
     * 能不能写交由随后的文件 PUT 给出真实结论。
     */
    public boolean ensureCollection(String relativeDir) throws IOException {
        String[] segments = split(relativeDir);
        StringBuilder current = new StringBuilder();
        boolean created = true;
        for (String segment : segments) {
            if (segment.isEmpty()) {
                continue;
            }
            current.append('/').append(segment);
            Res res;
            try {
                res = send("MKCOL", current.toString(), null, null, false);
            } catch (IOException e) {
                Logger.d("event=mkdir_failed dir=" + current + " reason=" + e.getMessage());
                return false;
            }
            if (res.code == 401 || res.code == 403) {
                throw new IOException("auth failed code=" + res.code + res.detail);
            }
            // 2xx=建好了；405/409/301/501 多半是"目录已存在"或"本就不允许显式建目录"
            if (!isSuccess(res.code) && res.code != 405 && res.code != 409
                    && res.code != 301 && res.code != 501) {
                created = false;
            }
        }
        return created;
    }

    private Res propfind(String remotePath, int depth) {
        try {
            return send("PROPFIND", remotePath, null,
                    PROPFIND_BODY.getBytes(StandardCharsets.UTF_8), true, depth);
        } catch (Throwable t) {
            // 服务器不支持 PROPFIND 时按"判不了"处理，不把它当成写失败
            Logger.d("event=propfind_failed reason=" + t.getMessage());
            return null;
        }
    }

    /** 诊断用：取一个对象的 PROPFIND 响应原文（Depth 1 时可列目录），不支持则返回 null */
    public String listRaw(String remotePath, int depth) {
        Res res = propfind(remotePath, depth);
        return res == null ? null : res.code + "\n" + res.body;
    }

    /** 从 PROPFIND 的响应体里取 getcontentlength；取不到返回 -1（表示"判不了"，不是"没有"） */
    private static long contentLengthOf(Res prop) {
        Matcher m = prop.body == null ? null : CONTENT_LENGTH.matcher(prop.body);
        return m != null && m.find() ? parseLong(m.group(1)) : -1L;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void requireSuccess(Res res, String method, String path) throws IOException {
        if (res.code == 401 || res.code == 403) {
            throw new IOException("auth failed code=" + res.code + res.detail);
        }
        if (!isSuccess(res.code)) {
            throw new IOException(method + " failed code=" + res.code + res.detail
                    + " path=" + path);
        }
    }

    // region 传输层

    /** 一次请求的结果：状态码、Content-Length（没有则 -1）、失败说明、小响应体、连接能否接着用 */
    private static final class Res {
        final int code;
        final long length;
        final String detail;
        final String body;
        final boolean keepAlive;

        Res(int code, long length, String detail, String body, boolean keepAlive) {
            this.code = code;
            this.length = length;
            this.detail = detail == null ? "" : detail;
            this.body = body;
            this.keepAlive = keepAlive;
        }
    }

    /** 释放复用中的连接。队列不再上传、或目标变了就该调一次，别把相册进程的 socket 一直挂着 */
    public void close() {
        dropConnection();
    }

    private void dropConnection() {
        closeQuietly(mSocket);
        mSocket = null;
        mOut = null;
        mReader = null;
        // 连接断了就把解析结果一起丢掉：IP 漂移（CDN/负载均衡换节点）只有重新解析才跟得上
        mResolvedHost = null;
        mResolvedAddress = null;
    }

    private Res send(String method, String remotePath, File file, byte[] body, boolean readBody)
            throws IOException {
        return send(method, remotePath, file, body, readBody, -1);
    }

    /** depth 仅对 PROPFIND 有意义：-1 表示不带该头 */
    private Res send(String method, String remotePath, File file, byte[] body,
            boolean readBody, int depth) throws IOException {
        URL url = new URL(mBaseUrl + encodePath(remotePath));
        String host = url.getHost();
        int port = url.getPort() == -1 ? url.getDefaultPort() : url.getPort();
        String target = url.getFile().isEmpty() ? "/" : url.getFile();
        long length = sizeOf(file, body);
        // 复用来的连接可能刚好被服务器关掉：这是常态不是故障，换一条新的重试一次就好。
        // 新连接上的失败不重试 —— 那说明目标确实有问题，重试只是把同一个错再犯一遍，
        // 大文件还要再付一遍上传流量。
        boolean reused = mSocket != null;
        try {
            return exchange(method, host, port, target, file, body, length, readBody, depth);
        } catch (IOException e) {
            dropConnection();
            if (!reused) {
                throw transportFailure(method, host, e);
            }
            Logger.d("event=conn_reopen method=" + method + " reason=" + e.getMessage());
        }
        try {
            return exchange(method, host, port, target, file, body, length, readBody, depth);
        } catch (IOException e) {
            dropConnection();
            throw transportFailure(method, host, e);
        }
    }

    /**
     * 连接层失败：只该记在这一项头上，不计入"目标不可用"的熔断。
     *
     * <p>走到这里的全是**连接层**的失败：连不上、握手断、写到一半被掐、读超时。
     * 服务器自己回的错误码装在 Res 里返回，不会进这个分支。分出来是因为这类失败只能说明
     * "这一项这次没传动"，不能拿来判整台服务器死刑 —— 否则一个 605MB 的视频就能把
     * 连续失败凑满 5 次，让一万两千张照片整批退回小米云。
     */
    public static class TransportFailure extends IOException {
        public TransportFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static TransportFailure transportFailure(String method, String host, IOException e) {
        return new TransportFailure(method + " " + host + " failed: " + e, e);
    }

    private Socket connect(String host, int port) throws IOException {
        if (mSocket != null && mSocket.isConnected() && !mSocket.isClosed()) {
            return mSocket;
        }
        Socket socket = mSecure ? tlsSocket(host, port) : plainSocket(host, port);
        mSocket = socket;
        mOut = new BufferedOutputStream(socket.getOutputStream(), 32 * 1024);
        // 缓冲层必须跟着连接一起复用：它是读超前的，每次新建就会丢掉上一次多读进来的字节
        mReader = new BufferedReader(new InputStreamReader(
                new BufferedInputStream(socket.getInputStream(), 32 * 1024),
                StandardCharsets.ISO_8859_1), 8192);
        return socket;
    }

    private Res exchange(String method, String host, int port, String target,
            File file, byte[] body, long length, boolean readBody, int depth)
            throws IOException {
        Socket socket = connect(host, port);
        socket.setSoTimeout(readTimeoutFor(length));
        try {
            OutputStream out = mOut;
            out.write(requestHead(method, host, port, target, file, body, length, depth));
            writeBody(out, file, body, length);
            out.flush();

            Res res = readResponse(method, readBody);
            if (!res.keepAlive) {
                dropConnection();
            }
            return res;
        } catch (IOException e) {
            dropConnection();
            throw e;
        }
    }

    /**
     * 读超时按请求体上浮。
     *
     * <p>小文件用短超时：连接被掐时能早点知道，不至于每个失败都干等两分钟。
     * 大文件必须给足时间 —— 服务器往往要先把整个请求体收完、落盘，才肯回响应，
     * 一个 600MB 的视频几十秒根本不够。
     */
    private static int readTimeoutFor(long bodyLength) {
        long allowance = READ_TIMEOUT_BASE + bodyLength / READ_TIMEOUT_PER_BYTE_MS;
        return (int) Math.min(allowance, READ_TIMEOUT_MAX);
    }

    private Res readResponse(String method, boolean readBody) throws IOException {
        BufferedReader reader = mReader;
        String statusLine = reader.readLine();
        if (statusLine == null) {
            throw new IOException("connection closed before response");
        }
        int code = parseStatus(statusLine);
        boolean http10 = statusLine.startsWith("HTTP/1.0");
        long contentLength = -1L;
        boolean chunked = false;
        Boolean headerKeepAlive = null;
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            if (name.equalsIgnoreCase("Content-Length")) {
                contentLength = parseLong(value);
            } else if (name.equalsIgnoreCase("Transfer-Encoding")
                    && value.toLowerCase(Locale.US).contains("chunked")) {
                chunked = true;
            } else if (name.equalsIgnoreCase("Connection")) {
                headerKeepAlive = Boolean.valueOf(
                        !value.toLowerCase(Locale.US).contains("close"));
            }
        }

        // HEAD 的 Content-Length 说的是"GET 会返回多大"，它自己没有响应体：
        // 照这个长度去排空会把下一条响应的开头吃掉，连接复用从此错位。
        // 1xx/204/304 同理。
        boolean bodyless = "HEAD".equals(method) || code == 204 || code == 304 || code < 200;

        int cap = readBody ? DETAIL_LIMIT * 20 : DETAIL_LIMIT;
        String snippet;
        boolean drainable;
        if (bodyless) {
            snippet = "";
            drainable = true;
        } else if (chunked) {
            // 分块也要读完：读到 0 长度块为止，之后边界是干净的，连接一样能接着用。
            // 不读就复用，下一条响应会从半截数据开始解析
            snippet = readChunked(reader, cap);
            drainable = true;
        } else if (contentLength > 0L) {
            // 有确切长度就能排空，多长的体都不影响复用
            snippet = readCapped(reader, contentLength, cap);
            drainable = true;
        } else if (contentLength == 0L) {
            snippet = "";
            drainable = true;
        } else {
            // 没给长度也没说分块：体到连接关闭为止，边界判定不了，这条用完就关
            snippet = readCapped(reader, cap);
            drainable = false;
        }
        boolean keepAlive = drainable && !http10;
        if (headerKeepAlive != null && !headerKeepAlive.booleanValue()) {
            keepAlive = false;
        }
        return new Res(code, contentLength,
                code >= 400 || code == 0 ? oneLine(snippet) : "",
                readBody ? snippet : null,
                keepAlive);
    }

    /**
     * 解析 host，成功一次就记住。
     *
     * <p>解析抖动在真机上真实发生过：同一时刻 adb shell 侧解析 15/15 全通、TCP 443 可达，
     * 相册进程里却连续抛 UnknownHostException（Java 把 EAI_AGAIN 这类临时失败也报成这个），
     * 整条队列因此空转。目标在一次备份里不会变，缓存住地址就不会被这种抖动打断。
     * 连不上时 dropConnection() 会把它清掉，下次重新解析，IP 真的变了也跟得上。
     */
    private InetAddress addressFor(String host) throws IOException {
        if (mResolvedAddress != null && host.equals(mResolvedHost)) {
            return mResolvedAddress;
        }
        InetAddress address = InetAddress.getByName(host);
        mResolvedHost = host;
        mResolvedAddress = address;
        return address;
    }

    private Socket plainSocket(String host, int port) throws IOException {
        Socket socket = new Socket();
        // 用缓存下来的 IP 建连；TLS 那条路仍把 host 传给 createSocket，SNI 与域名核对不受影响
        socket.connect(new InetSocketAddress(addressFor(host), port), CONNECT_TIMEOUT);
        // 握手阶段也得有上限，否则卡在对端不回应时会一直等下去。
        // exchange() 随后会按请求体大小把它换成真正的读超时
        socket.setSoTimeout(CONNECT_TIMEOUT * 2);
        return socket;
    }

    private SSLSocket tlsSocket(String host, int port) throws IOException {
        // 平台默认信任库（Android 上是系统 CA 那套），与 HttpsURLConnection 同源
        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        Socket raw = plainSocket(host, port);
        SSLSocket socket;
        try {
            // host 参数用于 SNI；autoClose=true 让外层 close() 一起关掉底层 socket
            socket = (SSLSocket) factory.createSocket(raw, host, port, true);
            // 裸 SSLSocket 默认不做域名核对，这一行就是它与 HttpsURLConnection 的唯一区别
            javax.net.ssl.SSLParameters params = socket.getSSLParameters();
            params.setEndpointIdentificationAlgorithm("HTTPS");
            socket.setSSLParameters(params);
            socket.startHandshake();
            return socket;
        } catch (IOException | RuntimeException e) {
            closeQuietly(raw);
            throw e;
        }
    }

    private byte[] requestHead(String method, String host, int port, String target,
            File file, byte[] body, long length, int depth) {
        StringBuilder sb = new StringBuilder(256);
        sb.append(method).append(' ').append(target).append(" HTTP/1.1\r\n")
                .append("Host: ").append(hostHeader(host, port)).append("\r\n")
                .append("Connection: keep-alive\r\n")
                .append("Accept-Encoding: identity\r\n");
        if (mAuthHeader != null) {
            sb.append("Authorization: ").append(mAuthHeader).append("\r\n");
        }
        if ("PROPFIND".equals(method)) {
            if (depth >= 0) {
                sb.append("Depth: ").append(depth).append("\r\n");
            }
            sb.append("Content-Type: application/xml; charset=utf-8\r\n")
                    .append("Content-Length: ").append(length).append("\r\n");
        } else if ("PUT".equals(method)) {
            sb.append("Content-Length: ").append(length).append("\r\n")
                    .append("Content-Type: ").append(contentType(file)).append("\r\n");
        }
        sb.append("\r\n");
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private static String hostHeader(String host, int port) {
        // IPv6 字面量必须带方括号，URL.getHost() 返回的就是带括号的形式，直接拼
        if (port == 80 || port == 443) {
            return host;
        }
        return host + ":" + port;
    }

    private static void writeBody(OutputStream out, File file, byte[] body, long length)
            throws IOException {
        if (length <= 0) {
            return;
        }
        if (file != null) {
            try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(file))) {
                byte[] buf = new byte[64 * 1024];
                int read;
                while ((read = in.read(buf)) != -1) {
                    out.write(buf, 0, read);
                }
            }
        } else if (body != null) {
            out.write(body);
        }
    }

    private static long sizeOf(File file, byte[] body) {
        if (file != null) {
            return file.length();
        }
        return body == null ? 0L : body.length;
    }

    private static int parseStatus(String statusLine) throws IOException {
        // HTTP/1.1 201 Created。按空白切而不是按单个空格：状态码前多打一个空格的服务器是存在的，
        // 用 split(" ") 会切出空串，把合法响应误判成畸形
        String[] parts = statusLine.trim().split("\\s+");
        int code = parts.length < 2 ? -1 : (int) parseLong(parts[1]);
        if (code <= 0) {
            throw new IOException("malformed status line: " + statusLine);
        }
        return code;
    }

    private static long parseLong(String text) {
        try {
            return Long.parseLong(text.trim());
        } catch (Throwable t) {
            return -1L;
        }
    }

    /**
     * 读满整段响应体，只留开头一段进日志。
     *
     * <p>复用连接前必须把体排空：留在缓冲里的字节会被下一条响应当成自己的开头读走。
     * 超出日志上限的部分也要跳掉，否则一条连接就废了。
     */
    private static String readCapped(BufferedReader reader, long bodyLen, int cap) {
        int want = (int) Math.min(bodyLen, cap);
        char[] buf = new char[Math.max(want, 0)];
        int total = 0;
        try {
            while (total < want) {
                int n = reader.read(buf, total, want - total);
                if (n < 0) {
                    break;
                }
                total += n;
            }
            long remaining = bodyLen - total;
            while (remaining > 0) {
                long skipped = reader.skip(remaining);
                if (skipped <= 0) {
                    break;
                }
                remaining -= skipped;
            }
        } catch (Throwable t) {
            // 排空与记日志都不该改变这次请求的结论
            Logger.d("event=body_drain_failed read=" + total + "/" + bodyLen);
        }
        return new String(buf, 0, total);
    }

    /** 长度未知时只取开头一段；连接随后会关掉，不需要排空 */
    private static String readCapped(BufferedReader reader, int cap) {
        char[] buf = new char[cap];
        try {
            int read = reader.read(buf);
            return read <= 0 ? "" : new String(buf, 0, read);
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 读完一段分块响应体，只留开头一段进日志。
     *
     * <p>分块是 WebDAV 服务器回 201 时的常见形态（不给 Content-Length），
     * 不读完就没法复用连接；读到 0 长度块之后再吃掉拖尾头，边界就是干净的。
     */
    private static String readChunked(BufferedReader reader, int cap) {
        StringBuilder log = new StringBuilder();
        try {
            while (true) {
                String sizeLine = reader.readLine();
                if (sizeLine == null) {
                    return log.toString();
                }
                int semi = sizeLine.indexOf(';');
                String hex = (semi >= 0 ? sizeLine.substring(0, semi) : sizeLine).trim();
                long size;
                try {
                    size = Long.parseLong(hex, 16);
                } catch (Throwable t) {
                    Logger.d("event=chunk_size_unparsable line=" + sizeLine);
                    return log.toString();
                }
                if (size == 0L) {
                    // 0 长度块后面是拖尾头，读到空行就结束了
                    String line;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) {
                        // 拖尾头不关心
                    }
                    return log.toString();
                }
                long remaining = size;
                while (remaining > 0) {
                    int want = (int) Math.min(remaining, 4096);
                    char[] buf = new char[want];
                    int total = 0;
                    while (total < want) {
                        int n = reader.read(buf, total, want - total);
                        if (n < 0) {
                            break;
                        }
                        total += n;
                    }
                    if (total <= 0) {
                        return log.toString();
                    }
                    if (log.length() < cap) {
                        log.append(buf, 0, Math.min(total, cap - log.length()));
                    }
                    remaining -= total;
                }
                // 块数据后面的 CRLF
                if (reader.readLine() == null) {
                    return log.toString();
                }
            }
        } catch (Throwable t) {
            Logger.d("event=chunk_read_failed collected=" + log.length());
        }
        return log.toString();
    }

    private static String oneLine(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.replace('\r', ' ').replace('\n', ' ').trim();
        return text.isEmpty() ? "" : " server=" + text;
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (Throwable ignored) {
            // 连接已经用完，关不掉不影响结果
        }
    }

    // endregion

    // region 路径与杂项

    private static boolean isSuccess(int code) {
        return code >= 200 && code < 300;
    }

    private static String trimTrailingSlash(String url) {
        if (url == null) {
            return "";
        }
        String trimmed = url.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String[] split(String path) {
        return path.replace("\\", "/").split("/");
    }

    private static String normalize(String remotePath) {
        if (remotePath == null || remotePath.isEmpty()) {
            return "/";
        }
        return remotePath.startsWith("/") ? remotePath : "/" + remotePath;
    }

    /**
     * 逐段转义后再拼 URL。曾经只有目录段转义、文件名直接拼，而相册里带空格、中文、#、%
     * 的文件名是常态：空格会让请求行在服务端被拆开、# 会被当成 fragment（后半段根本不发出去）、
     * % 会让服务端解出非法序列。顺带把重复的 // 收成单个，手填的根目录带斜杠也不会拼出空段。
     */
    private static String encodePath(String remotePath) {
        String normalized = normalize(remotePath);
        StringBuilder sb = new StringBuilder();
        for (String segment : split(normalized)) {
            if (segment.isEmpty()) {
                continue;
            }
            sb.append('/').append(encode(segment));
        }
        return sb.length() == 0 ? "/" : sb.toString();
    }

    private static String encode(String segment) {
        StringBuilder sb = new StringBuilder();
        for (byte b : segment.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            boolean keep = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '~';
            if (keep) {
                sb.append((char) c);
            } else {
                // 必须钉 Locale：阿拉伯语等区域下 %02X 会输出本地数字，转义串就不是合法 URL 了
                sb.append('%').append(String.format(Locale.US, "%02X", c));
            }
        }
        return sb.toString();
    }

    private static String parentOf(String remotePath) {
        String p = normalize(remotePath);
        int idx = p.lastIndexOf('/');
        return idx <= 0 ? "" : p.substring(1, idx);
    }

    /**
     * 时间分目录规则：{@code yyyy/MM}。上传与配置页的写入探测共用它，
     * 这样"测试连接"验的就是真正要写的那个目录（含建目录那一步），而不是它的父级。
     */
    public static String monthDir(long time) {
        return new SimpleDateFormat("yyyy/MM", Locale.US).format(new Date(time));
    }

    /** 已知的存储根前缀。长的排在前面，免得 /sdcard 先把 /storage/emulated/0 的匹配吃掉 */
    private static final String[] STORAGE_ROOTS = {
            "/storage/self/primary/", "/storage/emulated/0/", "/mnt/sdcard/", "/sdcard/"
    };
    /** 相册目录最多保留几级：再深对"按相册分"没帮助，反而平白多建一堆目录 */
    private static final int ALBUM_MAX_DEPTH = 4;

    /**
     * 从本地绝对路径推出相册目录，也就是相对存储根的那一段。
     *
     * <p>相册界面上是按文件夹分组的，备份只留年/月的话，用户到了 WebDAV 上就分不清
     * 哪张照片来自哪个相册。{@code /storage/emulated/0/DCIM/Camera/IMG.jpg} → {@code DCIM/Camera}。
     *
     * <p>不在已知存储根下（可移动存储）时连卷标一起带上，避免与主存储的同名目录撞车；
     * 完全推不出来时返回空串，由调用方决定退回哪种分法。
     */
    public static String albumDirOf(String absolutePath) {
        if (absolutePath == null) {
            return "";
        }
        String path = absolutePath.trim().replace('\\', '/');
        String relative = null;
        for (String root : STORAGE_ROOTS) {
            if (path.startsWith(root)) {
                relative = path.substring(root.length());
                break;
            }
        }
        if (relative == null && path.startsWith("/storage/")) {
            // /storage/XXXX-XXXX/DCIM/...：卷标一起带上，别和主存储的同名目录混在一起
            relative = path.substring("/storage/".length());
        }
        if (relative == null) {
            return "";
        }
        int lastSlash = relative.lastIndexOf('/');
        if (lastSlash <= 0) {
            // 直接躺在存储根下的文件没有相册目录可言
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int kept = 0;
        for (String segment : relative.substring(0, lastSlash).split("/")) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                continue;
            }
            if (kept >= ALBUM_MAX_DEPTH) {
                break;
            }
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(segment);
            kept++;
        }
        return sb.toString();
    }

    /**
     * 把两段路径拼成 a/b 形式：去掉首尾斜杠、统一反斜杠、收成单个分隔符，中间的分段保持原样。     * 远端根目录是手填的，前后斜杠与重复斜杠都会拼出空段；上传与界面的写入探测共用这一条，
     * 保证"探测的路径"和"真正要写的路径"是同一个。
     */
    public static String joinPath(String base, String child) {
        String head = base == null ? "" : base.trim().replace('\\', '/');
        while (head.startsWith("/")) {
            head = head.substring(1);
        }
        while (head.endsWith("/")) {
            head = head.substring(0, head.length() - 1);
        }
        String tail = child == null ? "" : child.trim().replace('\\', '/');
        while (tail.startsWith("/")) {
            tail = tail.substring(1);
        }
        return head.isEmpty() ? tail : head + "/" + tail;
    }

    private static String buildAuthHeader(String username, String password) {
        if (username == null || username.isEmpty()) {
            return null;
        }
        String raw = username + ":" + (password == null ? "" : password);
        byte[] encoded = Base64.encode(raw.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        return "Basic " + new String(encoded, StandardCharsets.UTF_8);
    }

    private static String contentType(File file) {
        return file == null ? "text/plain" : guessMime(file.getName());
    }

    private static String guessMime(String name) {
        String lower = name.toLowerCase(Locale.US);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".heic")) {
            return "image/heic";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        if (lower.endsWith(".mp4")) {
            return "video/mp4";
        }
        if (lower.endsWith(".mov")) {
            return "video/quicktime";
        }
        return "application/octet-stream";
    }

    // endregion
}
