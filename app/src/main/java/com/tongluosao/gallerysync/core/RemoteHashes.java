package com.tongluosao.gallerysync.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 远端原图的“哈希旁车”：把上传时已经算好的 SHA-256 写成远端一行几十字节的小文件。
 *
 * <p>为什么要有它：空间优化前的校验（{@link OriginalStore#ensureVerifiedRemoteOriginal}）
 * 原本要把<b>整份</b>远端原图 GET 回来重算 SHA-256 —— 一张 5MB 的照片就是 5MB 下行，
 * 一个 605MB 的视频就是 605MB 下行，而这份摘要上传时本来就已经算过、也登记在
 * {@code originals.json} 里。把摘要落到远端之后，校验退化成“读几十字节 + 一次 HEAD”。
 *
 * <p>为什么不把 {@code x.jpg.sha256} 直接放在照片旁边：媒体枚举（{@code DownloadQueue.walk}
 * 与宿主的相册扫描）是按目录列文件的，旁车散在照片目录里会让每个目录的条目翻一倍；
 * 集中到点开头的 {@value #DIR} 目录之后，只要递归时跳过点开头目录就再也看不见它们。
 *
 * <p>这个类<b>不直接 new {@link WebDavClient}</b>：所有网络动作都从 {@link Io} 进来，
 * 于是路径映射、内容格式、三态判定都能在没有网络也没有 {@code android.util.Log} 的
 * JVM 单测里跑。
 */
public final class RemoteHashes {

    /** 旁车目录名。点开头是为了让媒体枚举（按目录列文件）自然跳过它。 */
    public static final String DIR = ".mgs-hashes";

    /** 旁车文件后缀。{@code .sha256} 不是媒体后缀，旧版本的目录过滤也不会把它当照片。 */
    static final String SUFFIX = ".sha256";

    /** 内容版本头。将来改格式时靠它一眼分辨，而不是靠“解析失败”去猜。 */
    static final String MAGIC = "mgs-hash/1";

    /**
     * 带分块摘要的版本头（v2）。
     *
     * <p>v1 只记整份摘要，可它<b>证明不了远端那份到底是什么</b>：命中时只比长度，等于把
     * “我们上传时算过的摘要”当成“远端现在还是这些字节”。v2 额外记下头尾两段的摘要
     * （{@link RemoteByteCheck#SAMPLE_CHUNK}），命中时就真的是“这两段字节核对过”的证据，
     * 而且写它的现场必然是读过远端（或上传后回读过）的那一次。
     */
    static final String MAGIC_CHUNKED = "mgs-hash/2";

    /**
     * 旁车字节上限（8 KiB）。
     *
     * <p>真正的旁车只有 79 字节；上限存在的理由是<b>不许把整份文件读进内存</b>：
     * 万一路径映射算错指到了整份原图（或旁车被别的工具覆盖成大文件），
     * 读到上限就停、判“不是旁车”，绝不去 GET 一份 600MB 的视频。
     */
    public static final int MAX_BYTES = 8 * 1024;

    /** 旁车的全部网络动作。抽成接口是为了单测用假的实现覆盖各条分支。 */
    public interface Io {
        /** 读远端内容；不存在、或超过 {@link #MAX_BYTES} 时返回 null（不抛异常）。 */
        byte[] read(String remotePath) throws IOException;

        /** 写一小段内容；父目录由实现方负责建出来。 */
        void write(String remotePath, byte[] body) throws IOException;

        /** 删掉一个远端对象。 */
        boolean delete(String remotePath) throws IOException;
    }

    /** 旁车解析结果。 */
    public static final class Entry {
        public final String sha256;
        public final long length;
        /** 头段摘要；v1 旁车为 null。 */
        public final String headSha256;
        public final long headLength;
        /** 尾段摘要；v1 旁车为 null。 */
        public final String tailSha256;
        public final long tailLength;

        public Entry(String sha256, long length) {
            this(sha256, length, null, 0L, null, 0L);
        }

        public Entry(String sha256, long length, String headSha256, long headLength,
                String tailSha256, long tailLength) {
            this.sha256 = sha256;
            this.length = length;
            this.headSha256 = headSha256;
            this.headLength = headLength;
            this.tailSha256 = tailSha256;
            this.tailLength = tailLength;
        }

        /** v2：带着已核对过的头尾证据。v1 旁车只是“上传时算过的摘要”。 */
        public boolean hasChunks() {
            return isHexDigest(headSha256) && isHexDigest(tailSha256)
                    && headLength >= 0L && tailLength >= 0L;
        }
    }

    private final String mRemoteRoot;
    private final Io mIo;

    public RemoteHashes(String remoteRoot, Io io) {
        mRemoteRoot = remoteRoot;
        mIo = io;
    }

    // ---------------------------------------------------------------- 路径映射

    /**
     * 相对路径：去掉 {@code remoteRoot} 前缀后的那一段（带前导 {@code /}）。
     *
     * <p>{@code remotePath} 不在 {@code remoteRoot} 下时<b>原样返回</b>而不是返回空串：
     * 空串会让所有越界路径映射到同一个旁车文件，用一条错误的证据互相覆盖。
     *
     * <p>根目录比较允许忽略大小写：用户在设置里手改根目录的大小写、或服务器自己把目录名
     * 改写了大小写（OpenList/CloudDrive2 上很常见）时，{@code remotePath} 与 {@code remoteRoot}
     * 只差大小写。切片按<b>长度</b>切，所以相对路径里的文件名保留原始大小写。
     */
    public static String relativeOf(String remoteRoot, String remotePath) {
        String root = trimRoot(remoteRoot);
        String path = normalize(remotePath);
        if (root.isEmpty()) {
            return "/" + stripLeadingSlashes(path);
        }
        if (path.equals(root)) {
            return "";
        }
        if (path.startsWith(root + "/")) {
            return path.substring(root.length());
        }
        if (path.length() > root.length()
                && path.regionMatches(true, 0, root, 0, root.length())
                && path.charAt(root.length()) == '/') {
            return path.substring(root.length());
        }
        return path;
    }

    /**
     * 旁车路径：{@code <remoteRoot>/.mgs-hashes/<相对路径>.sha256}。
     *
     * <p>用 {@code /} 手拼而不是 {@link File}，因为这是<b>远端</b>路径；Windows 上跑单测时
     * {@code File} 会给出反斜杠，拼出来的就不是 WebDAV 路径了。
     */
    public static String hashPathFor(String remoteRoot, String remotePath) {
        String root = trimRoot(remoteRoot);
        String relative = relativeOf(remoteRoot, remotePath);
        StringBuilder out = new StringBuilder(root);
        out.append('/').append(DIR);
        if (!relative.isEmpty() && !"/".equals(relative)) {
            if (relative.charAt(0) != '/') {
                out.append('/');
            }
            out.append(relative);
        }
        return out.append(SUFFIX).toString();
    }

    // ---------------------------------------------------------------- 内容格式

    /**
     * 序列化成单行 ASCII：{@code mgs-hash/1 <sha256> <length>\n}。
     *
     * <p>摘要不是 64 位十六进制、或长度为负时返回 null —— 宁可不写，也不写一条自相矛盾的证据。
     */
    public static String format(String sha256, long length) {
        if (!isHexDigest(sha256) || length < 0L) {
            return null;
        }
        return MAGIC + " " + sha256.toLowerCase(Locale.US) + " " + length + "\n";
    }

    /**
     * 序列化带分块证据的 v2 单行：{@code mgs-hash/2 <sha256> <length> <headSha> <headLen> <tailSha> <tailLen>}。
     *
     * <p>长度彼此自洽才写：{@code headLength + tailLength <= length}，且两段不重叠。
     * 任何一项不合法就返回 null —— 宁可不写，也不写一条自相矛盾的证据。
     */
    public static String formatChunked(String sha256, long length, String headSha256,
            long headLength, String tailSha256, long tailLength) {
        if (!isHexDigest(sha256) || !isHexDigest(headSha256) || !isHexDigest(tailSha256)
                || length < 0L || headLength < 0L || tailLength < 0L
                || headLength + tailLength > length) {
            return null;
        }
        return MAGIC_CHUNKED + " " + sha256.toLowerCase(Locale.US) + " " + length + " "
                + headSha256.toLowerCase(Locale.US) + " " + headLength + " "
                + tailSha256.toLowerCase(Locale.US) + " " + tailLength + "\n";
    }

    /**
     * 解析旁车内容；任何不合法的输入都返回 null，绝不抛异常。
     *
     * <p>调用方（{@link OriginalStore} 的校验）把 null 一律当成“没有旁车”，于是自然退回
     * 整份回读那条路 —— 旁车写坏了最多让这一次校验变慢，不会让校验变假。
     */
    public static Entry parse(byte[] raw) {
        if (raw == null || raw.length == 0 || raw.length > MAX_BYTES) {
            return null;
        }
        String text = new String(raw, StandardCharsets.US_ASCII);
        int newline = text.indexOf('\n');
        String line;
        if (newline < 0) {
            // 尾部少一个换行也接受：手工排查时用 echo -n 写出来的内容照样能读
            line = text;
        } else {
            if (text.substring(newline + 1).trim().length() > 0) {
                // 多行说明这不是我们写的那一行（或被追加过），整份不采信
                return null;
            }
            line = text.substring(0, newline);
        }
        if (line.endsWith("\r")) {
            line = line.substring(0, line.length() - 1);
        }
        String[] parts = line.split(" ", -1);
        if (parts.length == 7 && MAGIC_CHUNKED.equals(parts[0])) {
            return parseChunked(parts);
        }
        if (parts.length != 3 || !MAGIC.equals(parts[0])) {
            return null;
        }
        String digest = parts[1];
        if (!isHexDigest(digest)) {
            return null;
        }
        long length;
        try {
            length = Long.parseLong(parts[2]);
        } catch (NumberFormatException e) {
            return null;
        }
        if (length < 0L) {
            return null;
        }
        return new Entry(digest.toLowerCase(Locale.US), length);
    }

    /** v2 行：{@code mgs-hash/2 <sha> <len> <headSha> <headLen> <tailSha> <tailLen>}。 */
    private static Entry parseChunked(String[] parts) {
        if (!isHexDigest(parts[1]) || !isHexDigest(parts[3]) || !isHexDigest(parts[5])) {
            return null;
        }
        long length;
        long headLength;
        long tailLength;
        try {
            length = Long.parseLong(parts[2]);
            headLength = Long.parseLong(parts[4]);
            tailLength = Long.parseLong(parts[6]);
        } catch (NumberFormatException e) {
            return null;
        }
        if (length < 0L || headLength < 0L || tailLength < 0L
                || headLength + tailLength > length) {
            return null;
        }
        return new Entry(parts[1].toLowerCase(Locale.US), length,
                parts[3].toLowerCase(Locale.US), headLength,
                parts[5].toLowerCase(Locale.US), tailLength);
    }

    /** 旁车是否仍然描述“这一份”原图。摘要比较忽略大小写（登记表里历史数据有大写）。 */
    public static boolean matches(Entry entry, String sha256, long length) {
        if (entry == null || sha256 == null || length < 0L) {
            return false;
        }
        return entry.length == length && sha256.equalsIgnoreCase(entry.sha256);
    }

    // ---------------------------------------------------------------- 读写入口

    /**
     * 尽力写一份旁车；失败只记日志返回 false，绝不抛给调用方。
     *
     * <p>旁车是<b>加速缓存</b>，不是正确性来源：写不进去的结果只是下次校验多做一次
     * 整份回读，所以它永远不该影响上传/校验本身的结论。
     */
    public boolean tryWrite(String remotePath, String sha256, long length) {
        if (mIo == null || remotePath == null || remotePath.isEmpty()) {
            return false;
        }
        String body = format(sha256, length);
        if (body == null) {
            Logger.w("event=remote_hash_write_failed path=" + remotePath + " reason=invalid_digest");
            return false;
        }
        return writeBody(remotePath, body);
    }

    /** 落盘：路径冲突（映射退化成原图自己）与 IO 失败都只记日志。 */
    private boolean writeBody(String remotePath, String body) {
        String hashPath = hashPathFor(mRemoteRoot, remotePath);
        if (hashPath.equals(remotePath)) {
            // 兜底：映射一旦退化成原图自己，写入就会覆盖用户的照片
            Logger.w("event=remote_hash_write_failed path=" + remotePath + " reason=path_collision");
            return false;
        }
        try {
            mIo.write(hashPath, body.getBytes(StandardCharsets.US_ASCII));
            return true;
        } catch (Throwable t) {
            Logger.w("event=remote_hash_write_failed path=" + remotePath + " reason=" + t);
            return false;
        }
    }

    /**
     * 写一份带头尾证据的 v2 旁车。调用方只在<b>真的核对过远端这两段字节</b>之后调用，
     * 于是“旁车存在”就等于“这两段当时是对的”。
     */
    public boolean tryWriteChunked(String remotePath, String sha256, long length,
            String headSha256, long headLength, String tailSha256, long tailLength) {
        if (mIo == null || remotePath == null || remotePath.isEmpty()) {
            return false;
        }
        String body = formatChunked(sha256, length, headSha256, headLength, tailSha256,
                tailLength);
        if (body == null) {
            Logger.w("event=remote_hash_write_failed path=" + remotePath
                    + " reason=invalid_chunked_digest");
            return false;
        }
        return writeBody(remotePath, body);
    }

    /** 读旁车；没有、内容不合法、IO 失败一律返回 null（调用方据此退回整份回读）。 */
    public Entry readOrNull(String remotePath) {
        if (mIo == null || remotePath == null || remotePath.isEmpty()) {
            return null;
        }
        try {
            return parse(mIo.read(hashPathFor(mRemoteRoot, remotePath)));
        } catch (Throwable t) {
            Logger.d("event=remote_hash_read_failed path=" + remotePath + " reason=" + t);
            return null;
        }
    }

    // ---------------------------------------------------------------- WebDAV 适配

    /**
     * 把 {@link WebDavClient} 适配成 {@link Io}。
     *
     * <p>读走 {@code get} 到 {@code tempDir} 下的临时文件、读完即删：{@code get} 是流式落盘的，
     * 不会把响应体攒在内存里；临时文件只有几十字节，落盘比在内存里攒更省事也更安全。
     * 读之前先用 {@code directRemoteSize} 问一次大小 —— 旁车被写坏、或路径算到了整份原图时，
     * 直接 GET 等于“为了省一次全量回读又做了一次全量回读”。
     *
     * <p>写走 {@code ensureCollection} + {@code putBytes}：后者自己会复核“回了 2xx 却没落盘”
     * 的假写服务器，所以写失败会抛出来、由 {@link #tryWrite} 吞掉并记日志。
     */
    public static Io forClient(final WebDavClient client, final File tempDir) {
        return new Io() {
            @Override
            public byte[] read(String remotePath) throws IOException {
                // 先 PROPFIND 问大小：这台 OpenList 对移动客户端的 HEAD 会挂十几秒，而空间优化
                // 每张照片都要先读一次旁车，HEAD 一卡就是 20 秒一张。问不出来才退回 HEAD 那条路。
                long known = client.propfindSize(remotePath);
                if (known == -2L || known == 0L) {
                    known = client.directRemoteSize(remotePath);
                }
                if (known < 0L) {
                    return null;
                }
                if (known > MAX_BYTES) {
                    Logger.d("event=remote_hash_too_large path=" + remotePath + " size=" + known);
                    return null;
                }
                if (tempDir == null) {
                    throw new IOException("no temp directory for remote hash reads");
                }
                if (!tempDir.exists() && !tempDir.mkdirs()) {
                    throw new IOException("cannot create remote hash temp directory");
                }
                File tmp = File.createTempFile("hash-", ".tmp", tempDir);
                try {
                    client.get(remotePath, tmp);
                    long size = tmp.length();
                    if (size <= 0L || size > MAX_BYTES) {
                        return null;
                    }
                    byte[] body = new byte[(int) size];
                    try (FileInputStream in = new FileInputStream(tmp)) {
                        int offset = 0;
                        while (offset < body.length) {
                            int count = in.read(body, offset, body.length - offset);
                            if (count < 0) {
                                return null;
                            }
                            offset += count;
                        }
                        if (in.read() != -1) {
                            // 文件在读完大小之后又长大了：内容不可信
                            return null;
                        }
                    }
                    return body;
                } finally {
                    if (tmp.exists() && !tmp.delete()) {
                        Logger.d("event=remote_hash_temp_cleanup_failed path="
                                + tmp.getAbsolutePath());
                    }
                }
            }

            @Override
            public void write(String remotePath, byte[] body) throws IOException {
                if (body == null || body.length > MAX_BYTES) {
                    throw new IOException("remote hash payload is too large");
                }
                // 先按常规路径走：能解析出父目录就用常规 PUT（大小写归一更稳）。
                // 解析失败（OpenList + S3 这类后端不支持 MKCOL，见 putBytesDirect 的注释）
                // 就退回直接 PUT —— 服务器会跟着 PUT 自动建目录，旁车自己的命名空间
                // 全小写、不需要大小写归一。
                try {
                    String dir = parentOf(remotePath);
                    if (!dir.isEmpty()) {
                        client.ensureCollection(dir);
                    }
                    client.putBytes(remotePath, body);
                } catch (IOException first) {
                    client.putBytesDirect(remotePath, body);
                }
            }

            @Override
            public boolean delete(String remotePath) throws IOException {
                return client.delete(remotePath);
            }
        };
    }

    // ---------------------------------------------------------------- 小工具

    private static String parentOf(String remotePath) {
        int slash = remotePath.lastIndexOf('/');
        return slash <= 0 ? "" : remotePath.substring(0, slash);
    }

    /** 根目录统一成“无尾斜杠”的形式；{@code null}/空白当作空根。 */
    private static String trimRoot(String remoteRoot) {
        String root = normalize(remoteRoot);
        while (root.length() > 1 && root.endsWith("/")) {
            root = root.substring(0, root.length() - 1);
        }
        return "/".equals(root) ? "" : root;
    }

    /** 统一成带前导 {@code /} 的远端路径，并收掉重复斜杠。 */
    private static String normalize(String value) {
        if (value == null) {
            return "/";
        }
        String path = value.trim().replace('\\', '/');
        StringBuilder out = new StringBuilder("/");
        for (String segment : path.split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            if (out.length() > 1) {
                out.append('/');
            }
            out.append(segment);
        }
        return out.toString();
    }

    private static String stripLeadingSlashes(String path) {
        int at = 0;
        while (at < path.length() && path.charAt(at) == '/') {
            at++;
        }
        return path.substring(at);
    }

    private static boolean isHexDigest(String value) {
        if (value == null || value.length() != 64) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }
}
