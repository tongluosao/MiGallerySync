package com.tongluosao.gallerysync.core;

import android.util.Base64;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
    /**
     * 目录列表（PROPFIND Depth 1）响应体的上限。
     *
     * <p>默认那点额度（{@link #DETAIL_LIMIT} 级别）只够看错误原因，拿它列目录会被截断成
     * 半个 XML，解析出的名字是残缺的 —— 而"没看见这个名字"会让调用方把已经备份过的文件
     * 再传一遍。一个目录上千项也就一百多 KB，给到 256KB 足够。
     */
    private static final int LIST_BODY_LIMIT = 256 * 1024;
    private static final String PROPFIND_BODY =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                    + "<propfind xmlns=\"DAV:\"><prop><getcontentlength/><resourcetype/>"
                    + "<getlastmodified/></prop></propfind>";
    /** 命名空间前缀由服务器决定（OpenList 用 D:，别的用 d: 或没有），只认标签名 */
    private static final Pattern CONTENT_LENGTH = Pattern.compile(
            "<[A-Za-z0-9]*:?getcontentlength[^>]*>\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern NOT_FOUND_STATUS = Pattern.compile(
            "<D:status>[^<]*404", Pattern.CASE_INSENSITIVE);
    /** PROPFIND 响应里每一条 resource 的 href；前缀同样由服务器决定 */
    private static final Pattern HREF = Pattern.compile(
            "<[A-Za-z0-9]*:?href[^>]*>(.*?)</[A-Za-z0-9]*:?href>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    /**
     * 把一个 {@code <response>} 整块切出来。
     *
     * <p>必须整块切：目录列表里每条 response 都带自己的 {@code getcontentlength}，
     * 拿整份 XML 去抓第一个长度，会把所有文件都算成第一个的大小 —— 下载侧据此判
     * "大小一样就不用下"就会漏文件。
     */
    private static final Pattern RESPONSE = Pattern.compile(
            "<[A-Za-z0-9]*:?response[^>]*>(.*?)</[A-Za-z0-9]*:?response>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    /** 最后修改时间；下载侧用它跟本地文件的 mtime 比对 */
    private static final Pattern LAST_MODIFIED = Pattern.compile(
            "<[A-Za-z0-9]*:?getlastmodified[^>]*>([^<]+)",
            Pattern.CASE_INSENSITIVE);

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

    /**
     * 请求体发送进度回调。挂在实例上而不是改成 {@code put()} 的参数：每个 worker 各持一个
     * 客户端、且同一时刻只服务一个任务，实例字段天然就绑定了"当前在传的是哪一项"，
     * 不必把回调一路穿过 {@code send/exchange} 的签名。
     */
    public interface ProgressListener {
        /** @param sent 已写进 socket 的字节（不含响应） @param total 本次请求体的总字节 */
        void onProgress(long sent, long total);
    }

    /** worker 线程写、自己读，跨线程可见性用 volatile */
    private volatile ProgressListener mProgressListener;

    public void setProgressListener(ProgressListener listener) {
        mProgressListener = listener;
    }

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
        putSend(remotePath, localFile);
        requireStoredAs(remotePath, localFile.length());
        return true;
    }

    /**
     * 只把请求体发出去，**不复核**。
     *
     * <p>给"发完就走"的上传队列用：PUT 的字节写进 socket 之后，界面上就已经是 100% 了，
     * 后面那几轮 HEAD/PROPFIND（最多 {@link #VERIFY_ATTEMPTS} 轮 × {@link #VERIFY_RETRY_DELAY}）
     * 是在等挂载型存储落盘，跟"这张照片传完了没有"无关，却一直占着上传通道。
     * 拆出来之后复核可以挪到别处做，通道立刻空出来去传下一项。
     *
     * <p><b>调用方必须自己复核</b>（调 {@link #storedAs} 或 {@link #requireStoredAs}），
     * 否则"回了 2xx 却没落盘"的服务器会被当成上传成功 —— 相册那边一记账就再也不会重传，
     * 那是本模块最不能出现的结果。
     *
     * <p>同样重要的是：**发送与复核的先后顺序不能颠倒**。只做复核不做发送时，
     * 复核问到的"这个路径存在"可能来自上一轮传进去的旧文件、或别的客户端写进去的同名文件，
     * 于是"这次没传"被记成"传成功了"。所以这一条只负责发，判落盘一律交给下面那个方法。
     */
    public void putSend(String remotePath, File localFile) throws IOException {
        Res res = putWithDirFallback(remotePath, localFile, null);
        requireSuccess(res, "PUT", remotePath);
    }

    /**
     * 复核落盘，不通过就抛"回了 2xx 却没有这个文件"。
     *
     * <p>与 {@link #put} 里那条判据共用同一个消息模板：调用方捕获到的 reason 必须一致，
     * 否则日志里同一个故障会有两种写法，排查时分不清是发送失败还是复核失败。
     */
    public void requireStoredAs(String remotePath, long expectedSize) throws IOException {
        if (!storedAs(remotePath, expectedSize)) {
            throw new IOException("PUT returned 2xx but the server has no such file"
                    + " path=" + remotePath);
        }
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

    /** 目录在 / 不在 / 问不出来。第三态是必须的：不能把"问不出来"当成"没有" */
    private static final int COLL_EXISTS = 1;
    private static final int COLL_MISSING = 0;
    private static final int COLL_UNKNOWN = -1;

    /**
     * 问一句"这个目录到底在不在"。
     *
     * <p>先 HEAD（2xx/3xx 算在，OpenList 挂网盘会把 HEAD 302 到存储直链）。HEAD 说 404
     * **不能就此下结论** —— 不少服务器（包括 PC 回归里那个严格替身）对目录根本不支持 HEAD，
     * 一律回 404，于是必须再用一次 PROPFIND Depth 0 确认。两条都明确说"没有"才算没有；
     * 其余情形（不支持 PROPFIND、连接层出错、405/501）一律"问不出来"，
     * 由调用方决定怎么办 —— 把问不出来当成"不在"会让本来正常的服务器被反复 MKCOL。
     */
    private int collectionState(String remotePath) {
        Res head;
        try {
            head = send("HEAD", remotePath, null, null, false);
        } catch (IOException e) {
            return COLL_UNKNOWN;
        }
        if (isSuccess(head.code) || head.code == 301 || head.code == 302 || head.code == 307
                || head.code == 308) {
            return COLL_EXISTS;
        }
        if (head.code != 404 && head.code != 410) {
            return COLL_UNKNOWN;
        }
        Res prop = propfind(remotePath, 0);
        if (prop == null) {
            return COLL_UNKNOWN;
        }
        if (prop.code == 404 || (prop.body != null && NOT_FOUND_STATUS.matcher(prop.body).find())) {
            return COLL_MISSING;
        }
        return prop.code == 207 ? COLL_EXISTS : COLL_UNKNOWN;
    }

    /**
     * 逐级 MKCOL。失败不抛（认证失败除外）：建目录只是"尽力而为"，
     * 探测请求本身失败（服务器直接掐连接）不该让整次上传作废，
     * 能不能写交由随后的文件 PUT 给出真实结论。
     *
     * <p><b>两条自保</b>（都是真机上踩出来的，见 AGENTS.md「建目录」一节）：
     * <ol>
     *   <li><b>已经有了就不建。</b> MKCOL 一个已存在的目录在部分服务器上不是回 405，
     *       而是按自己的命名规则另建一个 `<名字>_<时间戳>`，在父目录里凭空多出一个目录。</li>
     *   <li><b>建完必须回头确认。</b> 服务器可以回 201 却什么都没建出来（根目录下存在一个
     *       仅大小写不同的同名项时实测如此：`MKCOL /Pictures` 回 201，随后
     *       `HEAD /Pictures` 是 302、`PUT /Pictures/x` 是 404）。这时**立刻停手**，
     *       绝不继续往下建 —— 继续建的话，下一级的 MKCOL 会在父目录里再留一个改名目录，
     *       而"重试一次就多一个垃圾目录"，累积起来是几百个。</li>
     * </ol>
     * 第三态（{@link #COLL_UNKNOWN}）不拦：问不出来的服务器不该被这两条挡住。
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
            if (collectionState(current.toString()) == COLL_EXISTS) {
                continue;
            }
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
                continue;
            }
            if (collectionState(current.toString()) == COLL_MISSING) {
                // 回了成功码却没有这个东西：再建下一级只会在父目录里多留一个改名目录，
                // 直接认输，把证据打全（上传随后仍会 PUT 一次，由它给出最终结论）
                Logger.w("event=mkdir_ineffective dir=" + current + " code=" + res.code
                        + " detail=" + res.detail);
                return false;
            }
        }
        return created;
    }

    private Res propfind(String remotePath, int depth) {
        return propfind(remotePath, depth, 0);
    }

    /**
     * PROPFIND。{@code cap > 0} 时用它当响应体上限（列目录要给大一点的额度，
     * 默认那点额度会把 XML 截断，解析出的名字残缺）。
     */
    private Res propfind(String remotePath, int depth, int cap) {
        try {
            return send("PROPFIND", remotePath, null,
                    PROPFIND_BODY.getBytes(StandardCharsets.UTF_8), true, depth, cap);
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

    /**
     * 远端有没有这个文件、有多大。<b>不存在返回 {@code -1}，不抛异常</b> ——
     * "没有"是一个正常答案，不是故障。
     *
     * <p>比 {@link #storedAs} 轻：只问一轮、不重试、不比大小。它服务的是"上传前先看看远端
     * 有没有同名文件"这条路径（见 {@code UploadQueue.upload}），那里要的是尽量少请求、
     * 尽量少流量，判错了的代价只是多传一次或少传一次覆盖写。
     *
     * <p>只走 HEAD：HEAD 被重定向（OpenList 挂网盘会把 HEAD 302 到存储直链）或服务器
     * 不支持时才退回 PROPFIND Depth 0。401/403 与连接层失败照常抛，交给队列的重试与熔断。
     *
     * @return 远端对象的字节数；{@code -1} 表示服务器明确说"没有这个对象"
     */
    public long remoteSize(String remotePath) throws IOException {
        Res head = send("HEAD", remotePath, null, null, false);
        if (head.code == 401 || head.code == 403) {
            throw new IOException("auth failed code=" + head.code + head.detail);
        }
        if (head.code == 404) {
            return -1L;
        }
        // 2xx：给了长度就是答案；没给长度（有些服务器 HEAD 不带 Content-Length）再问一次
        if (isSuccess(head.code) && head.length >= 0L) {
            return head.length;
        }
        Res prop = propfind(remotePath, 0);
        if (prop == null) {
            // 不支持 PROPFIND：只有当 HEAD 明确说"有"时才认为它存在，否则判"不知道"。
            // 判"不知道"返回 -1（= 当作不存在，照传一次覆盖写）比判"存在"安全：
            // 后者会直接把这一项记成已备份，而远端可能真的没有
            return isSuccess(head.code) ? 0L : -1L;
        }
        if (prop.code == 404
                || (prop.body != null && NOT_FOUND_STATUS.matcher(prop.body).find())) {
            return -1L;
        }
        if (prop.code != 207) {
            return isSuccess(head.code) ? 0L : -1L;
        }
        long size = contentLengthOf(prop);
        // PROPFIND 只说"有"、没说多大：用 0 表示"存在但大小未知"
        return size < 0L ? 0L : size;
    }

    /**
     * 列一个目录下的**直接子项名字**（PROPFIND Depth 1）。
     *
     * <p>省电的关键在这里：一次请求换回整个目录的名字集合，比"每个文件问一次 HEAD"
     * 少几十倍往返。子目录以 {@code name/} 结尾，与文件名区分开。
     *
     * <p>返回 {@code null} 表示"列不出来"（不支持 PROPFIND、响应被截断、目录不存在），
     * 调用方必须把它与"目录是空的"分开：{@code null} 时退回逐文件判断，
     * 空集合时才可以认为"这个目录下什么都没有"。
     */
    public Set<String> listNames(String remoteDir) throws IOException {
        List<RemoteEntry> entries = list(remoteDir);
        if (entries == null) {
            return null;
        }
        Set<String> names = new HashSet<>();
        for (RemoteEntry entry : entries) {
            names.add(entry.dir ? entry.name + "/" : entry.name);
        }
        return names;
    }

    /** 远端目录里的一项：名字、是不是目录、有多大、什么时候改的 */
    public static final class RemoteEntry {
        /** 名字（不含任何路径与尾斜杠）。{@code listNames} 会给目录补上尾斜杠 */
        public final String name;
        public final boolean dir;
        /** 字节数；未知或目录为 -1 */
        public final long size;
        /** 最后修改时间（毫秒）；服务器没给为 0 */
        public final long lastModified;

        RemoteEntry(String name, boolean dir, long size, long lastModified) {
            this.name = name;
            this.dir = dir;
            this.size = size;
            this.lastModified = lastModified;
        }
    }

    /**
     * 列一个目录下的直接子项（PROPFIND Depth 1）。
     *
     * <p>返回 {@code null} 表示"列不出来"（不支持 PROPFIND、响应被截断、目录不存在），
     * 调用方必须把它与"目录是空的"分开：{@code null} 时退回逐文件判断。
     */
    public List<RemoteEntry> list(String remoteDir) throws IOException {
        Res res = propfind(remoteDir, 1, LIST_BODY_LIMIT);
        if (res == null || res.code == 404 || res.body == null) {
            return null;
        }
        if (res.code != 207) {
            return null;
        }
        if (res.body.length() >= LIST_BODY_LIMIT) {
            // 顶到额度说明这一条响应被截断了，缺的名字会被当成"远端没有"而重传一遍。
            // 宁可退回逐文件判断，也不要拿半份名单当全部
            Logger.d("event=list_truncated dir=" + remoteDir + " bytes=" + res.body.length());
            return null;
        }
        String self = stripLeading(normalize(remoteDir));
        String basePath = stripLeading(normalize(pathOfUrl(mBaseUrl)));
        List<RemoteEntry> out = new ArrayList<>();
        Matcher response = RESPONSE.matcher(res.body);
        while (response.find()) {
            String block = response.group(1);
            Matcher href = HREF.matcher(block);
            if (!href.find()) {
                continue;
            }
            String name = nameOfHref(href.group(1), self, basePath);
            if (name == null || name.isEmpty()) {
                continue;
            }
            boolean dir = name.endsWith("/");
            if (dir) {
                name = name.substring(0, name.length() - 1);
            }
            long size = -1L;
            Matcher length = CONTENT_LENGTH.matcher(block);
            if (length.find()) {
                size = parseLong(length.group(1));
            }
            long modified = 0L;
            Matcher lastMod = LAST_MODIFIED.matcher(block);
            if (lastMod.find()) {
                modified = parseHttpDate(lastMod.group(1));
            }
            out.add(new RemoteEntry(name, dir, size, modified));
        }
        return out;
    }

    /** 解析 HTTP 日期（RFC 1123）；解不开返回 0 —— 下载侧只把它当"要不要重下"的参考 */
    private static long parseHttpDate(String value) {
        try {
            SimpleDateFormat format = new SimpleDateFormat(
                    "EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
            format.setTimeZone(java.util.TimeZone.getTimeZone("GMT"));
            return format.parse(value.trim()).getTime();
        } catch (Throwable t) {
            return 0L;
        }
    }

    /**
     * 把 PROPFIND 里的 href 还原成"这个目录下的一项"，不属于这个目录就返回 null。
     *
     * <p>href 的写法各家不同：有给绝对路径的（{@code /dav/root/DCIM/a.jpg}），有只给相对段的。
     * 所以先减掉服务器挂载点（{@code base_url} 的路径部分，它未必等于 href 的前缀），
     * 再拿"目标目录自己"当边界比对 —— 不能用 base_url 硬拼，否则换个部署结论就变了。
     *
     * @param self     目标目录相对挂载点的路径，无前后斜杠，根目录为空串
     * @param basePath 挂载点路径，无前后斜杠
     */
    private static String nameOfHref(String href, String self, String basePath) {
        String path = href == null ? "" : href.trim();
        if (path.isEmpty()) {
            return null;
        }
        // 绝对 URL（少数服务器给 http://host/...）：只取路径部分
        int scheme = path.indexOf("://");
        if (scheme >= 0) {
            int slash = path.indexOf('/', scheme + 3);
            path = slash < 0 ? "/" : path.substring(slash);
        }
        path = stripLeading(decodeUrl(path));
        boolean dir = path.endsWith("/");
        if (dir) {
            path = path.substring(0, path.length() - 1);
        }
        // 先减掉服务器挂载点（base_url 的路径部分），href 里通常带着它
        if (!basePath.isEmpty()) {
            if (path.equals(basePath)) {
                return null;    // 就是挂载点本身
            }
            if (path.startsWith(basePath + "/")) {
                path = path.substring(basePath.length() + 1);
            }
        }
        if (path.equals(self)) {
            return null;        // 目录自己那一条
        }
        if (!self.isEmpty() && path.startsWith(self + "/")) {
            path = path.substring(self.length() + 1);
        } else if (path.indexOf('/') >= 0) {
            // 还带斜杠说明它不在这个目录下（是别的分支或孙辈），忽略
            return null;
        }
        // 走到这里 path 应该只剩一段名字；少数服务器本来就直接给相对段，也走这条
        if (path.isEmpty() || path.indexOf('/') >= 0) {
            return null;
        }
        return dir ? path + "/" : path;
    }

    private static String stripLeading(String path) {
        String p = path;
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        return p;
    }

    /** 取 URL 的路径部分（用于换算服务器挂载点）；解析不了就返回空 */
    private static String pathOfUrl(String url) {
        try {
            String p = new URL(url).getPath();
            return p == null ? "" : p;
        } catch (Throwable t) {
            return "";
        }
    }

    /** href 里的百分号转义解回原文；解不开就原样返回（宁可名字对不上，也不要抛） */
    private static String decodeUrl(String value) {
        if (value.indexOf('%') < 0) {
            return value;
        }
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (Throwable t) {
            return value;
        }
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
        return send(method, remotePath, file, body, readBody, depth, 0);
    }

    /**
     * @param bodyCap 响应体留存上限，{@code 0} 表示用默认额度（只够记错误原因）。
     *                列目录必须显式调大，否则 XML 会被截断
     */
    private Res send(String method, String remotePath, File file, byte[] body,
            boolean readBody, int depth, int bodyCap) throws IOException {
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
            return exchange(method, host, port, target, file, body, length, readBody, depth, bodyCap);
        } catch (IOException e) {
            dropConnection();
            if (!reused) {
                throw transportFailure(method, host, e);
            }
            Logger.d("event=conn_reopen method=" + method + " reason=" + e.getMessage());
        }
        try {
            return exchange(method, host, port, target, file, body, length, readBody, depth, bodyCap);
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
            File file, byte[] body, long length, boolean readBody, int depth, int bodyCap)
            throws IOException {
        Socket socket = connect(host, port);
        socket.setSoTimeout(readTimeoutFor(length));
        try {
            OutputStream out = mOut;
            out.write(requestHead(method, host, port, target, file, body, length, depth));
            writeBody(out, file, body, length);
            out.flush();

            Res res = readResponse(method, readBody, bodyCap);
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
        return readResponse(method, readBody, 0);
    }

    /** 状态行 + 响应头。GET 要照着它决定怎么流式读体，所以单独抽出来 */
    private static final class Head {
        final int code;
        final long contentLength;
        final boolean chunked;
        final boolean http10;
        /** 服务器显式说的连接是否留存；没写就是 null */
        final Boolean keepAlive;

        Head(int code, long contentLength, boolean chunked, boolean http10, Boolean keepAlive) {
            this.code = code;
            this.contentLength = contentLength;
            this.chunked = chunked;
            this.http10 = http10;
            this.keepAlive = keepAlive;
        }
    }

    private Head readHead() throws IOException {
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
        return new Head(code, contentLength, chunked, http10, headerKeepAlive);
    }

    /**
     * @param bodyCap 响应体留存上限；{@code 0} 用默认额度。列目录必须显式调大
     *                （解析名字需要完整的 XML，截断的名单会被当成"远端没有"）
     */
    private Res readResponse(String method, boolean readBody, int bodyCap) throws IOException {
        BufferedReader reader = mReader;
        Head head = readHead();
        int code = head.code;
        long contentLength = head.contentLength;
        boolean chunked = head.chunked;
        Boolean headerKeepAlive = head.keepAlive;
        boolean http10 = head.http10;

        // HEAD 的 Content-Length 说的是"GET 会返回多大"，它自己没有响应体：
        // 照这个长度去排空会把下一条响应的开头吃掉，连接复用从此错位。
        // 1xx/204/304 同理。
        boolean bodyless = "HEAD".equals(method) || code == 204 || code == 304 || code < 200;

        int cap = bodyCap > 0 ? bodyCap : (readBody ? DETAIL_LIMIT * 20 : DETAIL_LIMIT);
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

    /**
     * 把请求体写出去；文件按 64KB 一块读、边读边发，每块回调一次进度。
     *
     * <p>由 static 改成实例方法就是为了这个回调：它是"当前这一项传到哪了"的唯一来源
     * （{@code put()} 要等整个请求体发完、服务器复核过才返回，那时才知道结果已经太晚）。
     *
     * <p>回调在下游必须自己节流 —— 这里一个 600MB 的视频会回调上万次。
     *
     * <p>重发（keep-alive 连接被关后重试、404/409 补建目录后重试）会让 sent 归零重涨，
     * 客户端看到进度条回退一次。这比假装前进诚实，也说明那一项确实重传了。
     */
    private void writeBody(OutputStream out, File file, byte[] body, long length)
            throws IOException {
        if (length <= 0) {
            return;
        }
        if (file != null) {
            ProgressListener listener = mProgressListener;
            try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(file))) {
                byte[] buf = new byte[64 * 1024];
                int read;
                long sent = 0;
                while ((read = in.read(buf)) != -1) {
                    out.write(buf, 0, read);
                    sent += read;
                    if (listener != null) {
                        listener.onProgress(sent, length);
                    }
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
