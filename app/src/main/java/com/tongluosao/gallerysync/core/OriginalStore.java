package com.tongluosao.gallerysync.core;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Durable mapping between a local gallery item and its verified WebDAV original.
 *
 * <p>The registry is intentionally separate from the upload/download queue ledger. Queue state is
 * disposable and target-scoped; these mappings must survive queue resets so an optimized preview
 * can never be mistaken for a new original and the remote original remains discoverable.
 */
public final class OriginalStore {

    private static final String DIR = "webdav_sync";
    private static final String FILE_NAME = "originals.json";
    private static final int MAX_RECORDS = 100_000;
    private static volatile OriginalStore sInstance;
    private static final ConcurrentHashMap<String, ReentrantReadWriteLock> PATH_LOCKS =
            new ConcurrentHashMap<>();
    /**
     * 远端校验专用的共享客户端。
     *
     * <p>空间优化每张照片都要先确认远端原件还在。之前每张都 {@code new WebDavClient(...)}，
     * 而 {@link WebDavClient} 内部自持一个 OkHttp 客户端，等于每张照片重新做一次 DNS + TLS
     * 握手，再发三四个往返请求；实测把 {@code verify} 拖到 13–30 秒，占掉单张总耗时的大头。
     * 这里按配置缓存一个实例，其他上传/下载队列各自持有自己的客户端，互不影响。
     */
    private static volatile WebDavClient sVerifyClient;
    private static volatile String sVerifyClientKey;

    private final Object mLock = new Object();
    private final File mFile;
    private final LinkedHashMap<String, Record> mRecords = new LinkedHashMap<>();
    /** 远端路径索引：接管时逐行查重，线性扫描会退化成 O(n^2)。 */
    private final java.util.HashMap<String, String> mPathIndex = new java.util.HashMap<>();
    private boolean mPathIndexReady;
    /** A corrupt registry disables uploads until it can be inspected or repaired. */
    private boolean mHealthy = true;
    private int mBatchDepth;
    private boolean mBatchDirty;

    private OriginalStore(Context context) {
        File dir = new File(context.getApplicationContext().getFilesDir(), DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            mHealthy = false;
            Logger.w("event=original_store_dir_failed dir=" + dir.getAbsolutePath());
        }
        mFile = new File(dir, FILE_NAME);
        load();
    }

    /** Package-private for deterministic persistence tests. */
    OriginalStore(File directory) {
        if (!directory.exists() && !directory.mkdirs()) {
            mHealthy = false;
        }
        mFile = new File(directory, FILE_NAME);
        load();
    }

    public static OriginalStore get(Context context) {
        if (sInstance == null) {
            synchronized (OriginalStore.class) {
                if (sInstance == null) {
                    sInstance = new OriginalStore(context);
                }
            }
        }
        return sInstance;
    }

    /** Stable target identity without storing credentials. Password rotation keeps old mappings usable. */
    public static String targetKey(Config config) {
        if (config == null) {
            return "";
        }
        String base = safeBaseUrl(config.getBaseUrl());
        return sha256((base + "\n" + safe(config.getUsername()) + "\n"
                + safe(config.getRemoteRoot())).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 归属路径上这份文件当前装的是什么。
     *
     * <p>引入它是因为“空间优化”从“隐藏目录侧车预览”改成了“原地同名同扩展名覆盖”：
     * 同一个路径在不同阶段可能是完整原图、也可能是已经被替换掉的预览，光看路径分不出来，
     * 只能把状态显式记进注册表，让上传/恢复两侧都能按状态做判定。
     *
     * <p>JSON 里用 {@link #wire()} 的小写下划线写法；旧版本 JSON 没有这个键，读出来按
     * {@link #ORIGINAL}（当时只有“本地即原图”这一种可能）。
     */
    public enum LocalState {
        /** 本地就是远端完整原图：老数据的缺省值，也是唯一能靠长度/摘要判定上传的状态。 */
        ORIGINAL("original"),
        /** 原地模式：原路径上现在是预览，完整原图只在远端。 */
        INPLACE_PREVIEW("inplace_preview"),
        /** 原地覆盖写到一半（进程死在发布之前）：下一步是重做覆盖，绝不能当原图上传。 */
        PENDING_INPLACE("pending_inplace"),
        /** 老模式：预览放在 {@code <相册>/.gallerysync-slim/} 侧车目录，等待迁移成原地覆盖。 */
        SIDECAR_PREVIEW("sidecar_preview"),
        /** 本地字节对不上任何已知副本：不许上传，等人工确认。 */
        DAMAGED("damaged"),
        /**
         * “接管”登记：只知道远端有这份文件（长度与相册行一致），本地既没下载过、也没有
         * 校验过的字节指纹 —— 因此 {@code sha256} 允许为 null。上传侧按“非 ORIGINAL”处理，
         * 一律不传，直到有人真的把字节核对一遍再升级成 {@link #ORIGINAL}。
         */
        REMOTE_ONLY("remote_only");

        private final String mWire;

        LocalState(String wire) {
            mWire = wire;
        }

        /** JSON / 日志里的写法：小写下划线，跨版本稳定。 */
        public String wire() {
            return mWire;
        }

        /**
         * 解析 JSON 里的状态。缺失、空串、{@code "null"}、认不出来的值一律回落到
         * {@link #ORIGINAL}：旧 JSON 没有这个键，少读一个键不能变成“这份文件不可用”，
         * 更不能抛异常把整张注册表打成不健康。
         */
        public static LocalState fromWire(String raw) {
            String value = normalizeWire(raw);
            if (value != null) {
                for (LocalState state : values()) {
                    if (state.mWire.equals(value)) {
                        return state;
                    }
                }
            }
            return ORIGINAL;
        }

        private static String normalizeWire(String raw) {
            if (raw == null) {
                return null;
            }
            String value = raw.trim().toLowerCase(Locale.US).replace('-', '_');
            if (value.isEmpty() || "null".equals(value)) {
                return null;
            }
            return value;
        }
    }

    /**
     * Immutable metadata returned to hook code.
     *
     * <p>语义约定（2026-11 起，原地覆盖模式）：
     * <ul>
     *   <li>{@link #originalLength} / {@link #sha256} 永远是<b>远端完整原图</b>的真相，
     *       不管归属路径上现在装的是什么；</li>
     *   <li>{@link #originalLocalPath} 降级为“归属路径”：它只说明这个相册项属于哪个本地文件，
     *       文件内容可能是原图、也可能是原地预览，由 {@link #localState} 决定；</li>
     *   <li>{@link #previewLocalPath} 在原地模式下恒为 null（预览就写在归属路径上），
     *       只有老侧车模式才有值；</li>
     *   <li>{@link #previewLength} / {@link #previewSha256} / {@link #previewFormat} 描述
     *       “写在归属路径上的那份预览”，旧数据全为 0 / null。</li>
     * </ul>
     */
    public static final class Record {
        public final String target;
        public final String remotePath;
        public final long originalLength;
        public final String sha256;
        public final String originalLocalPath;
        public final String previewLocalPath;
        public final String rowId;
        public final String sha1;
        public final long originalLocalMtime;
        public final Set<String> derivedPaths;
        /** SHA-256 and byte length of generated copies, catching copied/renamed previews too. */
        public final Map<String, Long> derivedHashes;
        /** 归属路径上这份文件装的是什么；永远非 null，旧数据为 {@link LocalState#ORIGINAL}。 */
        public final LocalState localState;
        /** 原地预览的字节数；没有预览（含旧数据）时为 0。 */
        public final long previewLength;
        /** 原地预览的 SHA-256；未知时为 null。 */
        public final String previewSha256;
        /** 原地预览的编码格式（如 {@code heic} / {@code jpg}）；未知时为 null。 */
        public final String previewFormat;
        private final Set<String> identities;
        private final Set<String> localAliases;

        /**
         * 包级可见：单测要能直接构造一条记录来验证 {@link #withLocalState} / {@link #withPreview}
         * 只动自己那几个字段。生产代码一律经由 {@link #load()} 或注册入口构造。
         */
        Record(String target, String remotePath, long originalLength, String sha256,
                String originalLocalPath, String previewLocalPath, String rowId, String sha1,
                long originalLocalMtime, Set<String> derivedPaths) {
            this(target, remotePath, originalLength, sha256, originalLocalPath, previewLocalPath,
                    rowId, sha1, originalLocalMtime, derivedPaths,
                    initialIdentities(rowId, sha1), initialAliases(originalLocalPath,
                            previewLocalPath, derivedPaths), Collections.emptyMap());
        }

        private Record(String target, String remotePath, long originalLength, String sha256,
                String originalLocalPath, String previewLocalPath, String rowId, String sha1,
                long originalLocalMtime, Set<String> derivedPaths, Set<String> identities,
                Set<String> localAliases, Map<String, Long> derivedHashes) {
            this(target, remotePath, originalLength, sha256, originalLocalPath, previewLocalPath,
                    rowId, sha1, originalLocalMtime, derivedPaths, identities, localAliases,
                    derivedHashes, LocalState.ORIGINAL, 0L, null, null);
        }

        private Record(String target, String remotePath, long originalLength, String sha256,
                String originalLocalPath, String previewLocalPath, String rowId, String sha1,
                long originalLocalMtime, Set<String> derivedPaths, Set<String> identities,
                Set<String> localAliases, Map<String, Long> derivedHashes, LocalState localState,
                long previewLength, String previewSha256, String previewFormat) {
            this.target = target;
            this.remotePath = remotePath;
            this.originalLength = originalLength;
            this.sha256 = sha256;
            this.originalLocalPath = originalLocalPath;
            this.previewLocalPath = previewLocalPath;
            this.rowId = rowId;
            this.sha1 = sha1;
            this.originalLocalMtime = originalLocalMtime;
            Set<String> paths = new LinkedHashSet<>(derivedPaths);
            if (previewLocalPath != null) paths.add(previewLocalPath);
            this.derivedPaths = Collections.unmodifiableSet(paths);
            Set<String> allIdentities = new LinkedHashSet<>(identities);
            addIdentity(allIdentities, rowId, sha1);
            this.identities = Collections.unmodifiableSet(allIdentities);
            Set<String> allAliases = new LinkedHashSet<>(localAliases);
            if (originalLocalPath != null) allAliases.add(originalLocalPath);
            if (previewLocalPath != null) allAliases.add(previewLocalPath);
            allAliases.addAll(paths);
            this.localAliases = Collections.unmodifiableSet(allAliases);
            this.derivedHashes = Collections.unmodifiableMap(new LinkedHashMap<>(derivedHashes));
            this.localState = localState == null ? LocalState.ORIGINAL : localState;
            this.previewLength = Math.max(0L, previewLength);
            this.previewSha256 = clean(previewSha256);
            this.previewFormat = clean(previewFormat);
        }

        private static Set<String> initialIdentities(String rowId, String sha1) {
            Set<String> result = new LinkedHashSet<>();
            addIdentity(result, rowId, sha1);
            return result;
        }

        private static Set<String> initialAliases(String original, String preview,
                Set<String> derived) {
            Set<String> result = new LinkedHashSet<>();
            if (original != null) result.add(original);
            if (preview != null) result.add(preview);
            if (derived != null) result.addAll(derived);
            return result;
        }

        private static void addIdentity(Set<String> into, String rowId, String sha1) {
            String id = clean(rowId);
            String hash = clean(sha1);
            if (id != null) into.add("r:" + id);
            if (hash != null) into.add("s:" + hash.toLowerCase(Locale.US));
        }

        private Record withIdentity(String nextRowId, String nextSha1) {
            Set<String> aliases = new LinkedHashSet<>(identities);
            addIdentity(aliases, nextRowId, nextSha1);
            return new Record(target, remotePath, originalLength, sha256, originalLocalPath,
                    previewLocalPath, rowId == null ? clean(nextRowId) : rowId,
                    sha1 == null ? clean(nextSha1) : sha1, originalLocalMtime,
                    derivedPaths, aliases, localAliases, derivedHashes, localState,
                    previewLength, previewSha256, previewFormat);
        }

        private Record withRenamedDerivedAlias(String path, String nextRowId, String nextSha1) {
            Set<String> paths = new LinkedHashSet<>(derivedPaths);
            paths.add(path);
            Set<String> aliases = new LinkedHashSet<>(localAliases);
            aliases.add(path);
            Set<String> allIdentities = new LinkedHashSet<>(identities);
            addIdentity(allIdentities, nextRowId, nextSha1);
            return new Record(target, remotePath, originalLength, sha256, originalLocalPath,
                    previewLocalPath, rowId, sha1, originalLocalMtime, paths, allIdentities,
                    aliases, derivedHashes, localState, previewLength, previewSha256,
                    previewFormat);
        }

        /**
         * 把行身份整体换成另一个相册行（keeper）：{@code rowId/sha1} 覆盖成新值，旧 identity
         * 一并丢弃 —— 被删掉的那条幽灵行不该再被 {@code find} 命中。其余字段原样保留。
         */
        private Record withReboundIdentity(String nextRowId, String nextSha1) {
            return new Record(target, remotePath, originalLength, sha256, originalLocalPath,
                    previewLocalPath, clean(nextRowId), clean(nextSha1), originalLocalMtime,
                    derivedPaths, Collections.emptySet(), localAliases, derivedHashes, localState,
                    previewLength, previewSha256, previewFormat);
        }

        /**
         * 派生出一条“被接管”的记录：补齐相册行身份，必要时改状态，其余字段原样保留。
         *
         * <p>状态只在“这条记录本来就没有可信信息”（没有 {@code sha256}、也没有预览记录）
         * 时才改；已有摘要的记录说明当初真的校验过字节，一次接管登记不能把更强的结论降级成
         * “只知道远端有”，更不能抹掉原地预览/损坏之类的状态。
         */
        private Record withTakenOver(String nextRowId, String nextSha1, LocalState nextState) {
            Set<String> all = new LinkedHashSet<>(identities);
            addIdentity(all, nextRowId, nextSha1);
            LocalState state = localState;
            if (sha256 == null && previewLength <= 0L && nextState != null) {
                state = nextState;
            }
            return new Record(target, remotePath, originalLength, sha256, originalLocalPath,
                    previewLocalPath, clean(nextRowId), clean(nextSha1), originalLocalMtime,
                    derivedPaths, all, localAliases, derivedHashes, state, previewLength,
                    previewSha256, previewFormat);
        }

        private Record withDerivedPath(String path) {
            Set<String> paths = new LinkedHashSet<>(derivedPaths);
            paths.add(path);
            Set<String> aliases = new LinkedHashSet<>(localAliases);
            aliases.add(path);
            Map<String, Long> hashes = new LinkedHashMap<>(derivedHashes);
            return new Record(target, remotePath, originalLength, sha256, originalLocalPath,
                    previewLocalPath, rowId, sha1, originalLocalMtime, paths,
                    identities, aliases, hashes, localState, previewLength, previewSha256,
                    previewFormat);
        }

        private Record withDerivedHash(String hash, long length) {
            Map<String, Long> hashes = new LinkedHashMap<>(derivedHashes);
            hashes.put(hash, length);
            return new Record(target, remotePath, originalLength, sha256, originalLocalPath,
                    previewLocalPath, rowId, sha1, originalLocalMtime, derivedPaths,
                    identities, localAliases, hashes, localState, previewLength, previewSha256,
                    previewFormat);
        }

        /**
         * 派生出一条“本地状态是 {@code next}”的记录：只改状态，预览三个字段与其余字段原样保留。
         * {@code null} 按 {@link LocalState#ORIGINAL} 处理（与反序列化同一套兜底）。
         */
        public Record withLocalState(LocalState next) {
            return new Record(target, remotePath, originalLength, sha256, originalLocalPath,
                    previewLocalPath, rowId, sha1, originalLocalMtime, derivedPaths,
                    identities, localAliases, derivedHashes,
                    next == null ? LocalState.ORIGINAL : next, previewLength, previewSha256,
                    previewFormat);
        }

        /**
         * 派生出一条“原地预览就写在这个路径上”的记录：只改预览三个字段，状态与其余字段原样保留。
         * 负长度按 0 处理，空串摘要/格式按 null 处理（与写入 JSON 时的兜底一致）。
         */
        public Record withPreview(long nextLength, String nextSha256, String nextFormat) {
            return new Record(target, remotePath, originalLength, sha256, originalLocalPath,
                    previewLocalPath, rowId, sha1, originalLocalMtime, derivedPaths,
                    identities, localAliases, derivedHashes, localState, nextLength,
                    nextSha256, nextFormat);
        }

        /**
         * 归属路径上装的是什么。构造期已经兜底，永远非 null；{@code hook/} 与后续波次
         * 不在这个包里，只能通过访问器读，不能直接读字段。
         */
        public LocalState getLocalState() {
            return localState;
        }

        /** 原地预览的字节数；没有预览（含旧数据）时为 0。 */
        public long getPreviewLength() {
            return previewLength;
        }

        /** 原地预览的 SHA-256；未知时为 null。 */
        public String getPreviewSha256() {
            return previewSha256;
        }

        /** 原地预览的编码格式（{@code heic} / {@code jpg} 等）；未知时为 null。 */
        public String getPreviewFormat() {
            return previewFormat;
        }

        private Record withRestoredLocal(File file) throws IOException {
            return withRestoredLocal(file, false);
        }

        /**
         * 归属路径换成 {@code file} 且状态回落 {@code ORIGINAL}。
         *
         * <p>{@code clearPreview} 供“原图取回后侧车预览会被删除”的调用点使用：连
         * {@code previewLocalPath} 与对应的派生路径别名一起清掉，不给注册表留下指向已删文件的
         * 幽灵预览。老 {@link #restoreOriginal} 的恢复语义仍然保留侧车预览，因此默认 {@code false}。
         */
        private Record withRestoredLocal(File file, boolean clearPreview) throws IOException {
            String path = canonical(file);
            Set<String> paths = new LinkedHashSet<>(derivedPaths);
            paths.remove(path);
            String preview = clearPreview || path.equals(previewLocalPath) ? null : previewLocalPath;
            if (clearPreview && previewLocalPath != null) {
                paths.remove(previewLocalPath);
            }
            Set<String> aliases = new LinkedHashSet<>(localAliases);
            aliases.add(path);
            if (clearPreview && previewLocalPath != null) {
                aliases.remove(previewLocalPath);
            }
            // 恢复成功后本地又是完整原图：走默认构造，状态回落 ORIGINAL、预览字段清零
            return new Record(target, remotePath, originalLength, sha256, path, preview,
                    rowId, sha1, originalLocalMtime > 0L ? originalLocalMtime : file.lastModified(),
                    paths, identities, aliases, derivedHashes);
        }
    }

    /**
     * Register an upload only after PUT and remote length verification succeeded.
     * The current file digest is retained so later in-place compression is detectable.
     */
    public void registerUploadedOriginal(Config config, String remotePath, File localOriginal,
            String rowId, String sha1) throws IOException {
        register(config, remotePath, localOriginal, null, localOriginal, rowId, sha1);
    }

    /**
     * Register the source fingerprint captured before PUT. Callers may finish verification on a
     * different thread, after the source has changed, so hashing it at registration time would
     * describe current local bytes rather than the bytes supplied to the upload.
     */
    public void registerUploadedOriginal(Config config, String remotePath, File localOriginal,
            String rowId, String sha1, String uploadedSha256, long uploadedLength,
            long uploadedMtime) throws IOException {
        if (config == null || remotePath == null || remotePath.isEmpty() || localOriginal == null
                || uploadedLength <= 0 || !isSha256(uploadedSha256)) {
            throw new IOException("cannot register missing uploaded original fingerprint");
        }
        requireUsablePayload(localOriginal, "uploaded_original");
        requireNonZeroDigest(uploadedSha256, uploadedLength, "uploaded_original");
        persist(new Record(targetKey(config), remotePath, uploadedLength,
                uploadedSha256.toLowerCase(Locale.US), canonical(localOriginal), null, rowId, sha1,
                uploadedMtime, Collections.emptySet()));
    }

    /** Register a completed normal download; mirror files remain protected even after restart. */
    public void registerDownloadedOriginal(Config config, String remotePath, File localOriginal,
            long originalLength, String originalSha256) throws IOException {
        if (localOriginal == null || !localOriginal.isFile()) {
            throw new IOException("download original is missing");
        }
        String digest = originalSha256 == null || originalSha256.isEmpty()
                ? sha256(localOriginal) : originalSha256;
        if (localOriginal.length() != originalLength || !digest.equals(sha256(localOriginal))) {
            throw new IOException("download original changed before registration");
        }
        // 闸门按最终扩展名验签（这里两者同一个文件），拒绝空/全 0/截断/签名不符，
        // 并抛带原因标签的 IOException：调用方能把它归进"可重试/不可重试"。
        DownloadedPayloadGate.requirePublishable(localOriginal, localOriginal, originalLength);
        requireNonZeroDigest(digest, originalLength, "downloaded_original");
        persist(new Record(targetKey(config), remotePath, originalLength, digest,
                canonical(localOriginal), null, null, null, localOriginal.lastModified(),
                Collections.emptySet()));
    }

    /** Persist protection for a staged normal download before publishing it at its final path. */
    public void registerDownloadedOriginal(Config config, String remotePath, File stagedOriginal,
            File finalPath, long originalLength, String originalSha256) throws IOException {
        if (stagedOriginal == null || !stagedOriginal.isFile() || finalPath == null
                || stagedOriginal.length() != originalLength
                || !originalSha256.equals(sha256(stagedOriginal))) {
            throw new IOException("staged download changed before registration");
        }
        // 登记闸门按**最终路径**的扩展名验签：staging 文件叫
        // ".<name>.jpg.mgs-<uuid>.tmp"，按它自己的扩展名（tmp）判等于什么都没查，
        // 而按内容判又能抓住"中间层返回全 0 / 被截断"这两类真机故障。字节仍从 staged 读。
        DownloadedPayloadGate.requirePublishable(stagedOriginal, finalPath, originalLength);
        requireNonZeroDigest(originalSha256, originalLength, "downloaded_original");
        // mtime 沿用 staged 上的值：调用方在登记前已经把远端时间写进 staged，
        // 这里再传 0 会让重启后丢掉服务端时间，空间优化判定退化成"按当前时间"。
        long stagedMtime = stagedOriginal.lastModified();
        persist(new Record(targetKey(config), remotePath, originalLength, originalSha256,
                canonical(finalPath), null, null, null,
                stagedMtime <= 0L ? 0L : stagedMtime, Collections.emptySet()));
    }

    /** Register a downloaded compressed preview before its final path is made visible to scanners. */
    public void registerDownloadedPreview(Config config, String remotePath, long originalLength,
            String originalSha256, File preview) throws IOException {
        registerDownloadedPreview(config, remotePath, originalLength, originalSha256,
                null, preview);
    }

    /** Register both intended raw destination and the separately named compressed preview. */
    public void registerDownloadedPreview(Config config, String remotePath, long originalLength,
            String originalSha256, File originalDestination, File preview) throws IOException {
        registerDownloadedPreview(config, remotePath, originalLength, originalSha256,
                originalDestination, preview, preview != null && preview.isFile() ? preview : null);
    }

    /** Protect the final path and persist the staged image fingerprint before publishing it. */
    public void registerDownloadedPreview(Config config, String remotePath, long originalLength,
            String originalSha256, File originalDestination, File preview, File stagedPreview)
            throws IOException {
        if (preview == null) {
            throw new IOException("preview path is missing");
        }
        if (stagedPreview != null && (!stagedPreview.isFile() || stagedPreview.length() <= 0)) {
            throw new IOException("staged preview is missing");
        }
        String stagedDigest = null;
        if (stagedPreview != null) {
            requireUsablePayload(stagedPreview, "staged_preview");
            stagedDigest = sha256(stagedPreview);
            requireNonZeroDigest(stagedDigest, stagedPreview.length(), "staged_preview");
        }
        requireNonZeroDigest(originalSha256, originalLength, "downloaded_preview");
        String path = canonical(preview);
        Record record = new Record(targetKey(config), remotePath, originalLength, originalSha256,
                originalDestination == null ? null : canonical(originalDestination),
                path, null, null, 0L, Collections.singleton(path));
        if (stagedDigest != null) {
            record = record.withDerivedHash(stagedDigest, stagedPreview.length());
        }
        persist(record);
    }

    /**
     * “接管”登记：相册里已经存在的云端行，只补一条注册表记录，<b>不下载任何字节</b>。
     *
     * <p>与三个 {@code register*} 入口最大的不同是 <b>{@code sha256} 可以是 null</b>：接管只
     * 证明了“远端有这么一个大小一致的对象”，一个字节都没读过。所以这里既不抛异常（维护作业
     * 逐行调用，一行失败不该打断整批），也不拿长度去猜摘要 —— 记录按 {@code localStateWire}
     * 落成 {@link LocalState#ORIGINAL}（本地文件字节数与远端一致）或
     * {@link LocalState#REMOTE_ONLY}（只知道远端有）。
     *
     * <p>同一远端路径已有记录时只补身份与状态，绝不覆盖已有的 {@code sha256}：那个摘要是当初
     * 真的读过字节才有的，接管没有资格改它。
     *
     * @return 是否写进注册表（参数不合法、注册表不健康、长度冲突、条数上限都返回 false）
     */
    public boolean registerTakenOver(Config config, String remotePath, long length, long rowId,
            String sha1, String localStateWire) {
        String path = clean(remotePath);
        if (config == null || path == null || length <= 0L || rowId <= 0L) {
            return false;
        }
        LocalState state = LocalState.fromWire(localStateWire);
        if (state != LocalState.ORIGINAL && state != LocalState.REMOTE_ONLY) {
            // 接管只产出这两种结论；别的状态说明调用方传错了，按“有疑问就跳过”处理
            Logger.w("event=original_store_takeover_skipped reason=invalid_state state="
                    + localStateWire);
            return false;
        }
        String target = targetKey(config);
        String id = Long.toString(rowId);
        String hash = clean(sha1);
        if (hash != null) {
            hash = hash.toLowerCase(Locale.US);
        }
        synchronized (mLock) {
            if (!mHealthy) {
                Logger.w("event=original_store_takeover_skipped reason=registry_unhealthy");
                return false;
            }
            Record existing = recordForRemotePathLocked(target, path);
            Record record;
            if (existing != null) {
                if (existing.originalLength != length) {
                    // 同一路径登记过另一个长度：远端这份可能被换过，宁可不碰
                    Logger.w("event=original_store_takeover_skipped reason=length_conflict path="
                            + path);
                    return false;
                }
                record = existing.withTakenOver(id, hash, state);
            } else {
                if (mRecords.size() >= MAX_RECORDS) {
                    return false;
                }
                record = new Record(target, path, length, null, null, null, id, hash, 0L,
                        Collections.emptySet(), Collections.emptySet(), Collections.emptySet(),
                        Collections.emptyMap(), state, 0L, null, null);
            }
            String key = recordKey(record);
            if (existing != null) {
                String staleKey = recordKey(existing);
                if (!staleKey.equals(key)) {
                    mRecords.remove(staleKey);
                }
            }
            putRecordLocked(key, record);
            saveQuietlyLocked();
            return true;
        }
    }

    /**
     * 这个 target 下已经登记过的远端路径：接管时用来跳过重复登记。
     *
     * <p>返回的是快照副本，调用方随便用；顺序保持注册表内的插入序，便于日志复现。
     */
    public java.util.Set<String> remotePathsForTarget(Config config) {
        Set<String> paths = new LinkedHashSet<>();
        if (config == null) {
            return paths;
        }
        String target = targetKey(config);
        synchronized (mLock) {
            for (Record record : mRecords.values()) {
                if (target.equals(record.target)) {
                    paths.add(record.remotePath);
                }
            }
        }
        return paths;
    }

    /**
     * 这个 target 下所有记录的只读快照（插入序）。
     *
     * <p>给"按注册表把 {@code REMOTE_ONLY} 行排进下载队列"用：那个循环要逐行看状态、远端路径
     * 与长度，而 {@link #findByRemotePath} 是 O(全表) 的单条查询，逐行调就成平方了。
     * 返回的是不可变副本，调用方拿着它慢慢判，不会卡住注册表的写锁。
     */
    public java.util.List<Record> recordsForTarget(Config config) {
        java.util.List<Record> out = new java.util.ArrayList<>();
        if (config == null) {
            return out;
        }
        String target = targetKey(config);
        synchronized (mLock) {
            for (Record record : mRecords.values()) {
                if (target.equals(record.target)) {
                    out.add(record);
                }
            }
        }
        return java.util.Collections.unmodifiableList(out);
    }

    private Record recordForRemotePathLocked(String target, String remotePath) {        String indexKey = pathKey(target, remotePath);
        if (indexKey == null) {
            return null;
        }
        if (!mPathIndexReady) {
            rebuildPathIndexLocked();
        }
        String key = mPathIndex.get(indexKey);
        Record hit = key == null ? null : mRecords.get(key);
        if (hit != null && target.equals(hit.target) && remotePath.equals(hit.remotePath)) {
            return hit;
        }
        // 索引过期（记录换过路径）时退回线性扫描，并顺手修好索引，避免下次再扫
        for (java.util.Map.Entry<String, Record> entry : mRecords.entrySet()) {
            Record record = entry.getValue();
            if (target.equals(record.target) && remotePath.equals(record.remotePath)) {
                mPathIndex.put(indexKey, entry.getKey());
                return record;
            }
        }
        return null;
    }

    private static String pathKey(String target, String remotePath) {
        if (target == null || remotePath == null) {
            return null;
        }
        return target + '\u0000' + remotePath;
    }

    private void rebuildPathIndexLocked() {
        mPathIndex.clear();
        for (java.util.Map.Entry<String, Record> entry : mRecords.entrySet()) {
            Record record = entry.getValue();
            String indexKey = pathKey(record.target, record.remotePath);
            if (indexKey != null) {
                mPathIndex.put(indexKey, entry.getKey());
            }
        }
        mPathIndexReady = true;
    }

    private void putRecordLocked(String key, Record record) {
        mRecords.put(key, record);
        if (mPathIndexReady && record != null) {
            String indexKey = pathKey(record.target, record.remotePath);
            if (indexKey != null) {
                mPathIndex.put(indexKey, key);
            }
        }
    }

    /** 落盘但不抛：接管是一条“尽力而为”的补登记，写不进去只记日志，绝不打断整批。 */
    private void saveQuietlyLocked() {
        try {
            saveLocked();
        } catch (IOException failure) {
            Logger.w("event=original_store_takeover_save_failed", failure);
        }
    }

    /**
     * Confirm an old backup that predates the registry. A size match alone is insufficient: the
     * remote object must still hold the same bytes as the current local original.
     *
     * <p>比对强度与上传侧一致（{@link RemoteByteCheck}）：{@link RemoteByteCheck#FULL_CHECK_LIMIT}
     * 以内整份回读，更大的文件只读头尾各 {@link RemoteByteCheck#SAMPLE_CHUNK}——截断、补零、
     * 只写了开头这几种现实损坏都能抓住，而一万多张老照片不必每张都整份下一遍。
     *
     * <p>通过后顺手写下旁车，下一次优化前的校验就只剩一个 HEAD。
     */
    public boolean backfillFromRemote(Config config, String remotePath, File localOriginal,
            String rowId, String sha1) throws IOException {
        if (config == null || remotePath == null || remotePath.isEmpty()
                || localOriginal == null || !localOriginal.isFile() || localOriginal.length() <= 0) {
            return false;
        }
        long length = localOriginal.length();
        String localDigest = sha256(localOriginal);
        File tempDir = new File(mFile.getParentFile(), "original_verify");
        if (!tempDir.exists() && !tempDir.mkdirs()) {
            throw new IOException("cannot create original verification directory");
        }
        WebDavClient client = new WebDavClient(config.getBaseUrl(), config.getUsername(),
                config.getPassword());
        try {
            // 先确认对象还在、长度没变；长度不对连读都不用读。
            // 优先 PROPFIND：这台 OpenList 对移动客户端的 HEAD 会挂十几秒，而老照片建档是逐个
            // 走的，一次 HEAD 卡住就是一张 20 秒。问不出来才退回 HEAD 那条路。
            boolean present;
            long remoteLength = client.propfindSize(remotePath);
            if (remoteLength >= 0L) {
                present = remoteLength == 0L || remoteLength == length;
            } else {
                present = remoteLength != -1L && client.storedAs(remotePath, length);
            }
            if (!present) {
                return false;
            }
            if (!RemoteByteCheck.verify(client, remotePath, localOriginal, length, localDigest)) {
                return false;
            }
            persist(new Record(targetKey(config), remotePath, length, localDigest,
                    canonical(localOriginal), null, rowId, sha1,
                    localOriginal.lastModified(), Collections.emptySet()));
            // 刚核对过的就是头尾这两段，直接写成 v2 证据：下一次校验只需一次 HEAD。
            RemoteHashes hashes = new RemoteHashes(config.getRemoteRoot(),
                    RemoteHashes.forClient(client, tempDir));
            RemoteHashes.Entry evidence = RemoteByteCheck.chunkedEntry(localOriginal, localDigest);
            if (evidence != null) {
                hashes.tryWriteChunked(remotePath, evidence.sha256, evidence.length,
                        evidence.headSha256, evidence.headLength, evidence.tailSha256,
                        evidence.tailLength);
            }
            return true;
        } finally {
            client.close();
        }
    }

    /** Add cloud row identity after a scanner has created the row for a downloaded preview. */
    public boolean bindIdentity(File localPath, String rowId, String sha1) throws IOException {
        if (localPath == null || (clean(rowId) == null && clean(sha1) == null)) {
            return false;
        }
        String path = canonical(localPath);
        synchronized (mLock) {
            requireHealthyLocked();
            for (Map.Entry<String, Record> entry : mRecords.entrySet()) {
                Record record = entry.getValue();
                if (containsLocalPath(record, path)) {
                    Record updated = record.withIdentity(rowId, sha1);
                    putRecordLocked(entry.getKey(), updated);
                    saveLocked();
                    return true;
                }
            }
        }
        return false;
    }

    /** Alias used by ScannerIngest after the gallery creates a row for a downloaded preview. */
    public boolean registerScannedAlias(File localPath, String rowId, String sha1)
            throws IOException {
        return bindIdentity(localPath, rowId, sha1);
    }

    /**
     * 把登记着这张照片的记录改绑到一个“留下”的相册行上（reconcile_inplace 收尾用）。
     *
     * <p>原地迁移后扫描器为同一路径又插了一行，删掉旧行之后注册表里的 {@code rowId/sha1} 指向的
     * 就是一条已经不存在的行；这里按归属路径（{@code originalLocalPath}）或派生副本路径
     * （{@code derivedPaths}）命中记录，把行身份整体换成 keeper 行 —— 旧 identity 不保留，
     * 否则 {@link #find(Config, String, String)} 还会命中那条被删掉的幽灵行。
     *
     * <p>注册表里没有这条记录不是错误：返回 false，调用方只把它记进日志。
     */
    public boolean bindRowForLocalPath(Config config, File path, String rowId, String sha1)
            throws IOException {
        String id = clean(rowId);
        if (config == null || path == null || id == null) return false;
        String hash = clean(sha1);
        String local = canonical(path);
        String target = targetKey(config);
        synchronized (mLock) {
            requireHealthyLocked();
            for (Map.Entry<String, Record> entry : mRecords.entrySet()) {
                Record record = entry.getValue();
                if (!target.equals(record.target)) continue;
                if (!local.equals(record.originalLocalPath) && !record.derivedPaths.contains(local)) {
                    continue;
                }
                putRecordLocked(entry.getKey(), record.withReboundIdentity(id,
                        hash == null ? null : hash.toLowerCase(Locale.US)));
                saveLocked();
                return true;
            }
        }
        return false;
    }

    /**
     * Register a scanner-renamed file only when it still matches a known derived fingerprint.
     * The record key pins the operation to the captured target, remote path, and original digest.
     */
    public boolean registerRenamedDerivedAlias(Record expected, File newPath, String rowId,
            String sha1, String expectedHash, long expectedLength) throws IOException {
        if (expected == null || newPath == null || expectedLength <= 0L
                || !isSha256(expectedHash)
                || (clean(rowId) == null && clean(sha1) == null)) {
            return false;
        }
        String digest = expectedHash.toLowerCase(Locale.US);
        Long capturedLength = expected.derivedHashes.get(digest);
        if (capturedLength == null || capturedLength.longValue() != expectedLength) {
            return false;
        }
        String path = canonical(newPath);
        synchronized (mLock) {
            requireHealthyLocked();
            String key = recordKey(expected);
            Record current = mRecords.get(key);
            if (current == null) return false;
            Long currentLength = current.derivedHashes.get(digest);
            if (currentLength == null || currentLength.longValue() != expectedLength
                    || !newPath.isFile() || newPath.length() != expectedLength
                    || !digest.equals(sha256(newPath))) {
                return false;
            }
            Record updated = current.withRenamedDerivedAlias(path, rowId, sha1);
            putRecordLocked(key, updated);
            saveLocked();
            return true;
        }
    }

    /** Find a mapping by any known local original, preview, or derived path. */
    public Record findByLocalPath(File localPath) {
        if (localPath == null) {
            return null;
        }
        String path;
        try {
            path = canonical(localPath);
        } catch (IOException e) {
            return null;
        }
        synchronized (mLock) {
            for (Record record : mRecords.values()) {
                if (containsLocalPath(record, path)) {
                    return record;
                }
            }
        }
        return null;
    }

    /** Find a local alias belonging to one exact target and remote object. */
    public Record findByRemotePath(Config config, String remotePath, File localPath) {
        if (config == null || remotePath == null || remotePath.isEmpty() || localPath == null) {
            return null;
        }
        String path;
        try {
            path = canonical(localPath);
        } catch (IOException e) {
            return null;
        }
        String target = targetKey(config);
        synchronized (mLock) {
            for (Record record : mRecords.values()) {
                if (target.equals(record.target) && remotePath.equals(record.remotePath)
                        && containsLocalPath(record, path)) {
                    return record;
                }
            }
        }
        return null;
    }

    /** Find by gallery identity under the currently configured remote target. */
    public Record find(Config config, String rowId, String sha1) {
        String target = targetKey(config);
        synchronized (mLock) {
            Record latest = null;
            for (Record record : mRecords.values()) {
                if (target.equals(record.target) && identityMatches(record, rowId, sha1)) {
                    latest = record;
                }
            }
            return latest;
        }
    }

    /**
     * 该 target 下每个已登记 identity 的本地状态快照，键就是记录里的 identity 串
     * （{@code r:<rowId>} / {@code s:<sha1 小写>}）。
     *
     * <p>空间优化候选过滤要逐行判状态，而注册表上限 10 万条：每条候选都调一次
     * {@link #find(Config, String, String)} 就是 O(候选 x 登记) 的全表扫描。这里一次建表，
     * 之后逐行 O(1)。同一 identity 命中多条记录时非 ORIGINAL 的状态优先（更保守：
     * 宁可跳过优化也不要拿预览再压一次）。
     */
    public Map<String, LocalState> localStateIndex(Config config) {
        if (config == null) {
            return Collections.emptyMap();
        }
        String target = targetKey(config);
        Map<String, LocalState> index = new HashMap<>();
        synchronized (mLock) {
            for (Record record : mRecords.values()) {
                if (!target.equals(record.target)) {
                    continue;
                }
                LocalState state = record.getLocalState();
                for (String identity : record.identities) {
                    LocalState known = index.get(identity);
                    if (known == null || known == LocalState.ORIGINAL) {
                        index.put(identity, state);
                    }
                }
            }
        }
        return index;
    }

    /**
     * 在 {@link #localStateIndex(Config)} 的快照里查一行登记的状态：rowId 与 sha1 都试，
     * 都没有记录时返回 null（调用方按“未登记”处理）。
     */
    public static LocalState stateForRow(Map<String, LocalState> index, String rowId, String sha1) {
        if (index == null || index.isEmpty()) {
            return null;
        }
        String id = clean(rowId);
        if (id != null) {
            LocalState byRow = index.get("r:" + id);
            if (byRow != null) {
                return byRow;
            }
        }
        String hash = clean(sha1);
        if (hash != null) {
            return index.get("s:" + hash.toLowerCase(Locale.US));
        }
        return null;
    }

    /** Return true only when a registered original still exists remotely at its recorded length. */
    public boolean hasRemoteOriginal(Config config, String rowId, String sha1) throws IOException {
        Record record = find(config, rowId, sha1);
        if (record == null || record.originalLength <= 0 || record.remotePath.isEmpty()) {
            return false;
        }
        WebDavClient client = new WebDavClient(config.getBaseUrl(), config.getUsername(),
                config.getPassword());
        try {
            return client.storedAs(record.remotePath, record.originalLength);
        } finally {
            client.close();
        }
    }

    /** Verify the actual remote bytes against the registry digest before optimizing local storage. */
    public boolean ensureVerifiedRemoteOriginal(Config config, String rowId, String sha1)
            throws IOException {
        Record record = find(config, rowId, sha1);
        return record != null && verifyRemote(record, config);
    }

    /**
     * Safe migration path for originals uploaded before this registry existed. A matching size is
     * never sufficient: the remote GET must match the current local original byte-for-byte.
     */
    public boolean ensureVerifiedRemoteOriginal(Config config, String remotePath,
            File localOriginal, String rowId, String sha1) throws IOException {
        return backfillFromRemote(config, remotePath, localOriginal, rowId, sha1);
    }

    /**
     * Restore the registered original with GET only. The existing preview remains intact unless
     * length and SHA-256 both match, then a same-directory atomic replacement publishes the bytes.
     */
    public File restoreOriginal(Config config, String rowId, String sha1, File destination)
            throws IOException {
        Record record = find(config, rowId, sha1);
        if (record == null) {
            throw new IOException("no registered remote original for gallery item");
        }
        return restoreOriginal(config, record, destination);
    }

    /**
     * Restore a registered original when a download mapping has not yet received a gallery row
     * identity. The immutable record must still belong to this target and remain in the registry.
     */
    public File restoreOriginal(Config config, Record requested, File destination)
            throws IOException {
        if (destination == null) {
            throw new IOException("restore destination is missing");
        }
        if (requested == null || !targetKey(config).equals(requested.target)) {
            throw new IOException("no registered remote original for gallery item");
        }
        Record record;
        synchronized (mLock) {
            requireHealthyLocked();
            record = mRecords.get(recordKey(requested));
        }
        if (record == null || !targetKey(config).equals(record.target)) {
            throw new IOException("registered remote original is no longer available");
        }
        if (record.sha256 == null || record.sha256.length() != 64) {
            // 接管登记的原图只有远端长度、没有摘要：没有可比对的指纹就不往相册里写字节
            // （fail-closed）。要恢复只能先走一次真正的校验把摘要补上。
            throw new IOException("registered original has no digest");
        }
        PathLease restoreLease = lockPath(destination, true);
        try {
        RestoreDestinationSnapshot initial = snapshotRestoreDestination(record, destination);
        if (initial.original) {
            restoreOriginalTimestamp(record, destination);
            saveRestoredLocal(config, record, destination);
            return destination;
        }
        File parent = destination.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("cannot create restore destination directory");
        }
        File staged = File.createTempFile(".mgs-original-", ".tmp", parent);
        WebDavClient client = new WebDavClient(config.getBaseUrl(), config.getUsername(),
                config.getPassword());
        try {
            client.get(record.remotePath, staged);
            if (staged.length() != record.originalLength
                    || !record.sha256.equals(sha256(staged))) {
                throw new IOException("remote original failed length or SHA-256 verification");
            }
            RestoreDestinationSnapshot beforePublish = snapshotRestoreDestination(record,
                    destination);
            if (beforePublish.original) {
                restoreOriginalTimestamp(record, destination);
                saveRestoredLocal(config, record, destination);
                return destination;
            }
            if (!canPublishOver(initial, beforePublish)) {
                throw new IOException("restore destination changed during download");
            }
            try {
                if (recheckRestoreDestinationBeforePublish(record, destination, beforePublish)) {
                    restoreOriginalTimestamp(record, destination);
                    saveRestoredLocal(config, record, destination);
                    return destination;
                }
                Files.move(staged.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicFailure) {
                try {
                    if (recheckRestoreDestinationBeforePublish(record, destination,
                            beforePublish)) {
                        restoreOriginalTimestamp(record, destination);
                        saveRestoredLocal(config, record, destination);
                        return destination;
                    }
                    Files.move(staged.toPath(), destination.toPath(),
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException moveFailure) {
                    moveFailure.addSuppressed(atomicFailure);
                    throw moveFailure;
                }
            }
            restoreOriginalTimestamp(record, destination);
            saveRestoredLocal(config, record, destination);
            return destination;
        } finally {
            client.close();
            if (staged.exists() && !staged.delete()) {
                Logger.d("event=restore_temp_cleanup_failed path=" + staged.getAbsolutePath());
            }
        }
        } finally {
            restoreLease.close();
        }
    }

    /** Snapshot only missing paths or exact registered bytes before a restore can replace them. */
    private static RestoreDestinationSnapshot snapshotRestoreDestination(Record record,
            File destination) throws IOException {
        if (!Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            return RestoreDestinationSnapshot.missing();
        }
        if (!destination.isFile()) {
            throw new IOException("restore destination contains unrecognized local bytes");
        }
        long length = destination.length();
        long modified = destination.lastModified();
        String digest = sha256(destination);
        if (!destination.isFile() || destination.length() != length
                || destination.lastModified() != modified) {
            throw new IOException("restore destination changed during verification");
        }
        if (length == record.originalLength && record.sha256.equals(digest)) {
            return RestoreDestinationSnapshot.original();
        }
        Long derivedLength = record.derivedHashes.get(digest);
        if (!containsLocalPath(record, canonical(destination)) || derivedLength == null
                || derivedLength.longValue() != length) {
            throw new IOException("restore destination contains unrecognized local bytes");
        }
        return RestoreDestinationSnapshot.derived(digest, length, modified);
    }

    /** Recheck immediately before each move, accepting only unchanged bytes or a finished restore. */
    private static boolean recheckRestoreDestinationBeforePublish(Record record, File destination,
            RestoreDestinationSnapshot expected) throws IOException {
        RestoreDestinationSnapshot current = snapshotRestoreDestination(record, destination);
        if (current.original) return true;
        if (!sameRestoreDestination(expected, current)) {
            throw new IOException("restore destination changed before publish");
        }
        return false;
    }

    private static boolean canPublishOver(RestoreDestinationSnapshot initial,
            RestoreDestinationSnapshot current) {
        if (initial.missing) {
            // A concurrently created copy is replaceable only when it is already registered.
            return current.missing || current.derived;
        }
        return sameRestoreDestination(initial, current);
    }

    private static boolean sameRestoreDestination(RestoreDestinationSnapshot first,
            RestoreDestinationSnapshot second) {
        if (first.missing || second.missing) return first.missing && second.missing;
        return first.derived && second.derived && first.length == second.length
                && first.modified == second.modified && first.sha256.equals(second.sha256);
    }

    private static final class RestoreDestinationSnapshot {
        final boolean missing;
        final boolean original;
        final boolean derived;
        final long length;
        final long modified;
        final String sha256;

        private RestoreDestinationSnapshot(boolean missing, boolean original, boolean derived,
                long length, long modified, String sha256) {
            this.missing = missing;
            this.original = original;
            this.derived = derived;
            this.length = length;
            this.modified = modified;
            this.sha256 = sha256;
        }

        static RestoreDestinationSnapshot missing() {
            return new RestoreDestinationSnapshot(true, false, false, 0L, 0L, "");
        }

        static RestoreDestinationSnapshot original() {
            return new RestoreDestinationSnapshot(false, true, false, 0L, 0L, "");
        }

        static RestoreDestinationSnapshot derived(String sha256, long length, long modified) {
            return new RestoreDestinationSnapshot(false, false, true, length, modified, sha256);
        }
    }

    private static void restoreOriginalTimestamp(Record record, File destination)
            throws IOException {
        if (record.originalLocalMtime > 0L
                && destination.lastModified() != record.originalLocalMtime
                && !destination.setLastModified(record.originalLocalMtime)) {
            throw new IOException("cannot restore original file modification time");
        }
    }

    private void saveRestoredLocal(Config config, Record record, File destination)
            throws IOException {
        synchronized (mLock) {
            Record latest = mRecords.get(recordKey(record));
            if (latest != null) {
                Record restored = latest.withRestoredLocal(destination);
                putRecordLocked(recordKey(restored), restored);
                saveLocked();
            }
        }
    }

    /** Persist protection before a hook publishes a generated or in-place optimized copy. */
    public boolean registerDerivedPath(Config config, String rowId, String sha1, File path)
            throws IOException {
        return registerDerivedPath(config, rowId, sha1, path, true);
    }

    private boolean registerDerivedPath(Config config, String rowId, String sha1, File path,
            boolean fingerprintCurrentBytes) throws IOException {
        if (path == null) {
            return false;
        }
        String target = targetKey(config);
        String canonicalPath = canonical(path);
        synchronized (mLock) {
            requireHealthyLocked();
            Record record = findLocked(target, rowId, sha1);
            if (record == null) {
                return false;
            }
            Record updated = record.withDerivedPath(canonicalPath);
            if (fingerprintCurrentBytes && path.isFile()) {
                updated = updated.withDerivedHash(sha256(path), path.length());
            }
            putRecordLocked(recordKey(updated), updated);
            saveLocked();
            return true;
        }
    }

    /**
     * Take the shared per-path write lock and persist protection before replacing a source file.
     * The caller must close the returned lease after the entire mutation/publish operation.
     */
    public PathLease beginDerivedMutation(Config config, String rowId, String sha1, File path)
            throws IOException {
        if (path == null) return null;
        PathLease lease = lockPath(path, true);
        boolean marked = false;
        try {
            if (!registerDerivedPath(config, rowId, sha1, path, false)) return null;
            marked = true;
            return lease;
        } finally {
            if (!marked) lease.close();
        }
    }

    /**
     * 原地覆盖前的意图登记：状态切到 {@link LocalState#PENDING_INPLACE}，并记下将要写上去的预览。
     *
     * <p>调用方拿到非 null 之后才敢动原路径上的字节：进程若死在第 6 步（原子改名）之后，
     * 磁盘上这份字节已经是预览，而注册表里也已经写着“它不是原图”，上传侧不会把它当原图传。
     */
    public Record markInplacePending(Config config, Record record, long previewLength,
            String previewSha256, String previewFormat) {
        return markLocal(config, record, LocalState.PENDING_INPLACE, true, false, previewLength,
                previewSha256, previewFormat);
    }

    /**
     * 原地覆盖成功：状态切成 {@link LocalState#INPLACE_PREVIEW}，并把原路径登记成派生位置、
     * 把预览指纹写进 {@code derivedHashes}。
     *
     * <p>路径与指纹都指向“归属路径上现在这份字节”：上传分类、恢复菜单、下一次优化都靠它
     * 判断这份文件已经不是原图；没有它，恢复流程会拒绝覆盖自己刚写下的预览。
     */
    public Record markInplaceCommitted(Config config, Record record, long previewLength,
            String previewSha256, String previewFormat) {
        return markLocal(config, record, LocalState.INPLACE_PREVIEW, true, true, previewLength,
                previewSha256, previewFormat);
    }

    /**
     * 下载链路的原地覆盖成功：语义与 {@link #markInplaceCommitted} <b>完全相同</b>
     * （同一个 {@link #markLocal} 出口、同一个 {@code INPLACE_PREVIEW} 状态），只是多打一条
     * {@code event=registry_download_inplace} 日志，便于把“下载下来的预览”与“瘦身留下的预览”
     * 在设备日志里分开统计。
     *
     * <p>为什么不直接在 DownloadQueue 里调 {@code markInplaceCommitted} 再打日志：状态写入点只有
     * 一个（避免两处 log 与写入顺序不一致），而 {@code markLocal} 找不到记录时静默返回 null，
     * 调用方拿不到“到底有没有写进去”的事实；这里把这件事和 row 一起记进日志。
     *
     * <p>找不到记录、注册表不健康、写盘失败一律返回 null，**不抛**。
     */
    public Record markDownloadedInPlace(Config config, Record record, long previewLength,
            String previewSha256, String previewFormat) {
        Record updated = markInplaceCommitted(config, record, previewLength, previewSha256,
                previewFormat);
        if (updated == null) {
            Logger.w("event=registry_download_inplace_skipped reason=record_not_found"
                    + " remote=" + (record == null ? null : safe(record.remotePath)));
            return null;
        }
        Logger.i("event=registry_download_inplace row=" + safe(updated.rowId)
                + " bytes=" + previewLength + " format=" + safe(previewFormat)
                + " path=" + safe(updated.originalLocalPath));
        return updated;
    }

    /**
     * 记录损坏：只切状态，原因进日志（不新增字段，JSON 契约不再扩）。
     *
     * <p>典型场景是原地覆盖后 DB 没切过来 —— 字节已是合法预览，记录不能再被当原图。
     */
    public Record markDamaged(Config config, Record record, String reason) {
        Record updated = markLocal(config, record, LocalState.DAMAGED, false, false, 0L, null,
                null);
        if (updated != null) {
            Logger.w("event=original_store_mark_damaged reason=" + safe(reason)
                    + " path=" + safe(updated.originalLocalPath));
        }
        return updated;
    }

    /**
     * 原图已经重新落回归属路径（外部恢复流程写盘并自行校验通过）：状态切回
     * {@link LocalState#ORIGINAL}，归属路径指向 {@code destination}，预览登记一并清空。
     *
     * <p>与 {@link #restoreOriginal} 内部的 {@code saveRestoredLocal} 同一语义，区别是这里
     * <b>不下载、不碰字节</b>，只修注册表；调用方必须自己证明文件长度与 SHA-256 都等于登记原图。
     * 找不到记录、注册表不健康、写盘失败一律返回 {@code null}，不抛。
     */
    public Record markRestoredOriginal(Config config, Record record, File destination) {
        if (config == null || record == null || destination == null) {
            return null;
        }
        try {
            synchronized (mLock) {
                if (!mHealthy) {
                    return null;
                }
                Record latest = findForUpdateLocked(targetKey(config), record);
                if (latest == null) {
                    return null;
                }
                Record updated = latest.withRestoredLocal(destination, true);
                putRecordLocked(recordKey(updated), updated);
                saveLocked();
                return updated;
            }
        } catch (Throwable failure) {
            Logger.e("event=original_store_mark_restored_failed", failure);
            return null;
        }
    }

    /**
     * 自动下载把 {@code REMOTE_ONLY} 行的原图落回相册之后，把它升级成 {@link LocalState#ORIGINAL}。
     *
     * <p>为什么不复用 {@link #registerDownloadedOriginal}：那个入口走 {@code merge(old, incoming)}，
     * 而 merge 的规则是"新登记里的 ORIGINAL 不能覆盖已登记的状态"（见 merge 里 state 那一行），
     * 于是 {@code REMOTE_ONLY} 会被原样留下 —— 上传侧继续把它当"只有远端"，下次扫描又排一遍。
     * 这里显式写状态，并顺手把这一次真的读过的字节摘要补进记录（接管登记时 sha256 是 null）。
     *
     * <p>校验放在写锁之外：长度必须与远端一致、给了摘要就必须与本地字节一致。任何一项对不上
     * 都返回 {@code null} 且**不改注册表** —— 失败由下载队列按次数上限重试，不能把状态提前改掉。
     *
     * @param sha256 刚下载/刚核对过的本地字节摘要，可为 null（保持记录里已有的值）
     * @return 更新后的记录；找不到记录、注册表不健康、校验失败都返回 null
     */
    public Record markAutoDownloaded(Config config, Record expected, File localFile, String sha256) {
        if (config == null || expected == null || localFile == null) {
            return null;
        }
        try {
            if (localFile.length() != expected.originalLength || expected.originalLength <= 0L) {
                Logger.w("event=original_store_auto_download_rejected reason=length_mismatch path="
                        + localFile + " expected=" + expected.originalLength
                        + " actual=" + localFile.length());
                return null;
            }
            String digest = clean(sha256);
            if (digest != null) {
                digest = digest.toLowerCase(Locale.US);
                if (!isSha256(digest) || !digest.equals(sha256(localFile))) {
                    Logger.w("event=original_store_auto_download_rejected reason=digest_mismatch path="
                            + localFile);
                    return null;
                }
            }
            synchronized (mLock) {
                if (!mHealthy) {
                    return null;
                }
                Record latest = findForUpdateLocked(targetKey(config), expected);
                if (latest == null) {
                    return null;
                }
                // withRestoredLocal(clearPreview) 已经清掉预览字段与派生别名，只差把摘要写进去
                Record restored = latest.withRestoredLocal(localFile, true);
                Record updated = new Record(restored.target, restored.remotePath,
                        restored.originalLength, digest == null ? restored.sha256 : digest,
                        restored.originalLocalPath, restored.previewLocalPath, restored.rowId,
                        restored.sha1, restored.originalLocalMtime, restored.derivedPaths,
                        restored.identities, restored.localAliases, restored.derivedHashes,
                        LocalState.ORIGINAL, 0L, null, null);
                putRecordLocked(recordKey(updated), updated);
                saveLocked();
                return updated;
            }
        } catch (Throwable failure) {
            Logger.e("event=original_store_auto_download_failed", failure);
            return null;
        }
    }

    /**
     * 三个状态变更的唯一实现：找到记录 → 不可变派生 → 原子落盘。
     *
     * <p>找不到记录、注册表不健康、写盘失败一律回 {@code null}，**不抛**：调用方已经在
     * “失败就放弃这次覆盖”的位置，让它再包一层 try/catch 只会把真正的失败原因埋掉。
     */
    private Record markLocal(Config config, Record expected, LocalState state,
            boolean touchPreview, boolean derived, long previewLength, String previewSha256,
            String previewFormat) {
        if (config == null || expected == null) {
            return null;
        }
        String target = targetKey(config);
        try {
            synchronized (mLock) {
                if (!mHealthy) {
                    return null;
                }
                Record latest = findForUpdateLocked(target, expected);
                if (latest == null) {
                    return null;
                }
                Record updated = latest.withLocalState(state);
                if (touchPreview) {
                    updated = updated.withPreview(previewLength, previewSha256, previewFormat);
                }
                String path = clean(latest.originalLocalPath);
                String digest = clean(previewSha256);
                // 路径在登记时就已规范化（构造与 load 都走 canonical），这里不再重复 IO
                if (derived && path != null && digest != null && previewLength > 0L) {
                    updated = updated.withDerivedPath(path)
                            .withDerivedHash(digest.toLowerCase(Locale.US), previewLength);
                }
                putRecordLocked(recordKey(updated), updated);
                saveLocked();
                return updated;
            }
        } catch (Throwable t) {
            Logger.e("event=original_store_mark_failed state=" + state.wire(), t);
            return null;
        }
    }

    /** 优先按身份找；身份缺失（下载映射还没拿到相册行）时退回 target + 远端路径 + 摘要。 */
    private Record findForUpdateLocked(String target, Record expected) {
        Record byIdentity = findLocked(target, expected.rowId, expected.sha1);
        if (byIdentity != null) {
            return byIdentity;
        }
        Record matched = null;
        for (Record record : mRecords.values()) {
            if (target.equals(record.target) && record.remotePath.equals(expected.remotePath)
                    // 摘要可能是 null（接管登记）：用 Objects.equals 而不是 equals 链
                    && java.util.Objects.equals(record.sha256, expected.sha256)) {
                matched = record;
            }
        }
        return matched;
    }

    /** Read lease held from upload classification until the PUT has finished reading this path. */
    public PathLease lockForUpload(File path) throws IOException {
        if (path == null) throw new IOException("upload path is missing");
        return lockPath(path, false);
    }

    public static final class PathLease implements AutoCloseable {
        private final ReentrantReadWriteLock lock;
        private final boolean write;
        private boolean closed;

        private PathLease(ReentrantReadWriteLock lock, boolean write) {
            this.lock = lock;
            this.write = write;
        }

        @Override public synchronized void close() {
            if (closed) return;
            closed = true;
            if (write) lock.writeLock().unlock(); else lock.readLock().unlock();
        }
    }

    private static PathLease lockPath(File path, boolean write) throws IOException {
        String canonical = canonical(path);
        ReentrantReadWriteLock lock = PATH_LOCKS.computeIfAbsent(canonical,
                ignored -> new ReentrantReadWriteLock(true));
        if (write) lock.writeLock().lock(); else lock.readLock().lock();
        return new PathLease(lock, write);
    }

    /**
     * Fail-closed upload classifier. It blocks reserved download mirrors, registered previews,
     * derived paths, and any original path whose bytes changed since the verified upload.
     *
     * <p>第 3 波起最前面多一道“本地状态”判据：这条路径登记的状态是原地预览 / 写入中途 /
     * 侧车预览 / 损坏，或 {@code ORIGINAL} 但本地长度已经和登记的原图长度对不上时，直接按
     * 受保护返回，不再走到后面的长度/摘要比较。查不到对应记录时保持原有逻辑不变。
     *
     * <p>用 {@code File.length()} 拿本地长度，不为判摘要整份哈希：四个调用点（上传队列三处 +
     * 原生上传出口一处）都只经过这一个函数，改这里就够了。
     */
    public boolean isProtectedUpload(File candidate, String rowId, String sha1) {
        if (candidate == null) {
            return true;
        }
        String path;
        try {
            path = canonical(candidate);
        } catch (IOException e) {
            return true;
        }
        if (isReservedDownloadPath(path)) {
            return true;
        }
        synchronized (mLock) {
            if (!mHealthy) {
                return true;
            }
            if (blockingPreviewStateLocked(path, candidate) != null) {
                return true;
            }
            for (Record record : mRecords.values()) {
                if (record.derivedPaths.contains(path)
                        || path.equals(record.previewLocalPath)) {
                    return true;
                }
                if (path.equals(record.originalLocalPath)) {
                    if (!candidate.isFile() || candidate.length() != record.originalLength) {
                        return true;
                    }
                    // Catch same-size replacements too. The worker calls this again immediately
                    // before PUT, so an already queued optimized copy cannot slip through.
                    try {
                        // 接管登记没有摘要：这条路径只能按“说不清”挡掉（fail-closed）
                        if (record.sha256 == null || !record.sha256.equals(sha256(candidate))) {
                            return true;
                        }
                    } catch (IOException e) {
                        return true;
                    }
                }
            }
            if (candidate.isFile()) {
                long length = candidate.length();
                String digest = null;
                for (Record record : mRecords.values()) {
                    for (Map.Entry<String, Long> entry : record.derivedHashes.entrySet()) {
                        if (entry.getValue() != null && entry.getValue() == length) {
                            try {
                                if (digest == null) digest = sha256(candidate);
                            } catch (IOException e) {
                                return true;
                            }
                            if (entry.getKey().equals(digest)) return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /**
     * 上传被“本地状态”拦下的原因：该路径登记的状态，且只有
     * {@link PreviewStateRules#blocksUpload} 判真时才返回非 null。
     *
     * <p>只读状态与本地长度，不哈希、不读文件内容；没有对应记录、注册表不健康或状态层面
     * 不拦时返回 null。判定入口仍然是 {@link #isProtectedUpload}，这里只给上传队列记
     * {@code event=upload_skip_preview_state} 用。
     */
    public LocalState blockingUploadState(File candidate) {
        if (candidate == null) {
            return null;
        }
        String path;
        try {
            path = canonical(candidate);
        } catch (IOException e) {
            return null;
        }
        synchronized (mLock) {
            return mHealthy ? blockingPreviewStateLocked(path, candidate) : null;
        }
    }

    /**
     * 按路径找到那条登记记录，再用 {@link PreviewStateRules#blocksUpload} 判一次。
     * 调用方必须已经持有 {@link #mLock} 并确认注册表健康。
     */
    private LocalState blockingPreviewStateLocked(String canonicalPath, File candidate) {
        Record record = recordForLocalPathLocked(canonicalPath);
        if (record == null) {
            return null;
        }
        long localLength = candidate.isFile() ? candidate.length() : 0L;
        return PreviewStateRules.blocksUpload(record.getLocalState(), localLength,
                record.originalLength) ? record.getLocalState() : null;
    }

    /**
     * 这条本地路径属于哪条登记记录：原路径、预览路径、派生副本与重命名别名都算。
     * 调用方必须已经持有 {@link #mLock}。
     */
    private Record recordForLocalPathLocked(String canonicalPath) {
        for (Record record : mRecords.values()) {
            if (containsLocalPath(record, canonicalPath)) {
                return record;
            }
        }
        return null;
    }

    /**
     * 该路径是否只是这条记录登记过的本地副本（路径或字节指纹）。
     * 下载与瘦身共用这一判断：只有它可以被就地覆盖，别的文件一律不碰。
     */
    public boolean isRegisteredDerivedCopy(Record record, File path) {
        return isRegisteredDerivedPath(record, path) || isVerifiedDerivedBytes(record, path);
    }

    /**
     * 只按路径判断：该文件是否是这条记录登记过的副本路径（预览 / 下载镜像 / 瘦身副本）。
     * 纯路径比较、不做任何哈希，可以安全地在主线程的菜单可见性钩子里对每个 item 调用；
     * 需要按字节指纹确认副本时用 {@link #isRegisteredDerivedCopy(Record, File)}。
     */
    public boolean isRegisteredDerivedPath(Record record, File path) {
        if (record == null || path == null) {
            return false;
        }
        String canonicalPath;
        try {
            canonicalPath = canonical(path);
        } catch (IOException e) {
            return false;
        }
        return record.derivedPaths.contains(canonicalPath)
                || canonicalPath.equals(record.previewLocalPath);
    }

    /** 该文件是否就是这条记录里登记过的副本字节：是的话可以直接复用，不必重新编码。 */
    public boolean isVerifiedDerivedBytes(Record record, File path) {
        if (record == null || path == null || !path.isFile() || path.length() <= 0L) {
            return false;
        }
        try {
            Long expectedLength = record.derivedHashes.get(sha256(path));
            return expectedLength != null && expectedLength.longValue() == path.length();
        } catch (IOException e) {
            return false;
        }
    }

    /** Exact reserved mirror root; this remains a safety net even if registry writes fail. */
    public static boolean isReservedDownloadPath(String canonicalPath) {
        if (canonicalPath == null) {
            return false;
        }
        String path = normalizeStorageAlias(canonicalPath);
        String root = "/storage/emulated/0/dcim/migallerysync";
        return path.equals(root) || path.startsWith(root + "/");
    }

    /** Pure policy helper exposed package-private for unit tests. */
    static boolean identityMatches(Record record, String rowId, String sha1) {
        if (record == null) {
            return false;
        }
        String id = clean(rowId);
        String hash = clean(sha1);
        boolean idMatches = id != null && (id.equals(record.rowId)
                || record.identities.contains("r:" + id));
        boolean shaMatches = hash != null && (hash.equalsIgnoreCase(safe(record.sha1))
                || record.identities.contains("s:" + hash.toLowerCase(Locale.US)));
        return (id != null || hash != null) && (idMatches || shaMatches);
    }

    /**
     * “下载原图”菜单判据（纯逻辑，单测直接覆盖）：本地这份文件是否还需要从 WebDAV 取回原图。
     *
     * <p>第 3 波起读侧（上传拦截 / 恢复菜单 / 候选筛选）统一改用
     * {@link PreviewStateRules}，按注册表登记的 {@link LocalState} 判；本函数保留下来只为
     * 兼容旧调用与既有单测，新代码不要再调它。
     *
     * <p>注册表里有记录只说明这张照片做过云备份登记（上传成功即登记），并不表示本地原图被删过；
     * 从未优化过的普通照片，本地文件就是完整原图，必须判成“不需要恢复”，菜单不显示。
     *
     * <p>判定顺序按代价从低到高，主线程绝不为了菜单哈希整个原图：
     * <ol>
     *   <li>{@code originalLength <= 0}：没登记原图大小，菜单没有意义 → false；</li>
     *   <li>本地文件不存在（空间优化成功后原图被删）→ true；</li>
     *   <li>该路径就是记录登记的派生副本/预览（{@code .gallerysync-slim}）→ true；</li>
     *   <li>本地长度 != {@code originalLength} → true；</li>
     *   <li>长度一致且调用方已算好 sha256 时再比摘要，不一致 → true；</li>
     *   <li>长度一致、非登记副本、没有摘要可比 → false，本地就是完整原图。</li>
     * </ol>
     * 同长度原位替换属于极小概率：真发生时恢复流程自己还会校验远端长度与 SHA-256，
     * 因此菜单侧不再为此额外读盘。
     */
    public static boolean needsOriginalRestore(long originalLength, String originalSha256,
            boolean localFileExists, long localLength, boolean registeredDerivedCopy,
            String knownLocalSha256) {
        if (originalLength <= 0L) {
            return false;
        }
        if (!localFileExists) {
            return true;
        }
        if (registeredDerivedCopy) {
            return true;
        }
        if (localLength != originalLength) {
            return true;
        }
        if (knownLocalSha256 != null && originalSha256 != null && !originalSha256.isEmpty()) {
            return !originalSha256.equalsIgnoreCase(knownLocalSha256);
        }
        return false;
    }

    private void register(Config config, String remotePath, File source, File preview,
            File localOriginal, String rowId, String sha1) throws IOException {
        if (config == null || remotePath == null || remotePath.isEmpty() || source == null
                || !source.isFile() || source.length() <= 0) {
            throw new IOException("cannot register missing original");
        }
        long length = source.length();
        requireUsablePayload(source, "uploaded_original");
        String digest = sha256(source);
        requireNonZeroDigest(digest, length, "uploaded_original");
        String originalPath = localOriginal == null ? null : canonical(localOriginal);
        String previewPath = preview == null ? null : canonical(preview);
        Set<String> derived = previewPath == null
                ? Collections.emptySet() : Collections.singleton(previewPath);
        persist(new Record(targetKey(config), remotePath, length, digest, originalPath,
                previewPath, rowId, sha1,
                localOriginal == null ? 0L : localOriginal.lastModified(), derived));
    }

    private void persist(Record record) throws IOException {
        synchronized (mLock) {
            requireHealthyLocked();
            if (!mRecords.containsKey(recordKey(record)) && mRecords.size() >= MAX_RECORDS) {
                throw new IOException("original registry is full");
            }
            String key = recordKey(record);
            Record existing = mRecords.get(key);
            putRecordLocked(key, existing == null ? record : merge(existing, record));
            saveLocked();
        }
    }

    private static Record merge(Record old, Record incoming) {
        Set<String> derived = new LinkedHashSet<>(old.derivedPaths);
        derived.addAll(incoming.derivedPaths);
        Set<String> identities = new LinkedHashSet<>(old.identities);
        identities.addAll(incoming.identities);
        Set<String> aliases = new LinkedHashSet<>(old.localAliases);
        aliases.addAll(incoming.localAliases);
        if (old.originalLocalPath != null) aliases.add(old.originalLocalPath);
        if (old.previewLocalPath != null) { aliases.add(old.previewLocalPath); derived.add(old.previewLocalPath); }
        if (incoming.originalLocalPath != null) aliases.add(incoming.originalLocalPath);
        if (incoming.previewLocalPath != null) { aliases.add(incoming.previewLocalPath); derived.add(incoming.previewLocalPath); }
        Map<String, Long> hashes = new LinkedHashMap<>(old.derivedHashes);
        hashes.putAll(incoming.derivedHashes);
        // 状态与预览字段：新登记里“没有信息”的取值（ORIGINAL / 0 / null）不能覆盖已登记的原状态，
        // 否则一次普通重登记就会把“原路径上已经是预览”这件事抹掉，上传侧随即会把预览当原图。
        LocalState state = incoming.localState == LocalState.ORIGINAL
                ? old.localState : incoming.localState;
        long previewLength = incoming.previewLength > 0L
                ? incoming.previewLength : old.previewLength;
        String previewSha256 = incoming.previewSha256 == null
                ? old.previewSha256 : incoming.previewSha256;
        String previewFormat = incoming.previewFormat == null
                ? old.previewFormat : incoming.previewFormat;
        return new Record(incoming.target, incoming.remotePath, incoming.originalLength,
                // 接管登记没有摘要（null）：不能让一次补登记把已校验过的远端 SHA-256 抹掉
                incoming.sha256 == null ? old.sha256 : incoming.sha256,
                incoming.originalLocalPath == null ? old.originalLocalPath
                        : incoming.originalLocalPath,
                incoming.previewLocalPath == null ? old.previewLocalPath
                        : incoming.previewLocalPath,
                incoming.rowId == null ? old.rowId : incoming.rowId,
                incoming.sha1 == null ? old.sha1 : incoming.sha1,
                incoming.originalLocalMtime == 0L ? old.originalLocalMtime
                        : incoming.originalLocalMtime,
                derived, identities, aliases, hashes, state, previewLength, previewSha256,
                previewFormat);
    }

    /**
     * 批量写入：期间所有保存只标记脏，最外层结束时统一落盘一次。
     *
     * <p>维护作业逐项更新时，整份注册表（数 MB）逐项重写会让总耗时随条数平方增长，
     * 调用方应把整批更新包在 {@link #beginBatch()} / {@link #endBatch()} 之间。
     */
    public void beginBatch() {
        synchronized (mLock) {
            mBatchDepth++;
        }
    }

    /** 结束批量；最外层退出时若确实有改动，才真正落盘一次。 */
    public void endBatch() {
        boolean flush;
        synchronized (mLock) {
            if (mBatchDepth > 0) {
                mBatchDepth--;
            }
            flush = mBatchDepth == 0 && mBatchDirty;
            if (flush) {
                mBatchDirty = false;
            }
        }
        if (!flush) {
            return;
        }
        synchronized (mLock) {
            try {
                saveLocked();
            } catch (IOException e) {
                Logger.w("event=registry_batch_flush_failed", e);
            }
        }
    }
    private void saveLocked() throws IOException {
        if (mBatchDepth > 0) {
            mBatchDirty = true;
            return;
        }
        try {
            JSONObject root = new JSONObject();
            // v2 起多出 local_state / preview_length / preview_sha256 / preview_format 四个键。
            // 读侧对缺键一律走兜底（ORIGINAL / 0 / null），所以 v1 文件不必迁移、直接可读。
            root.put("version", 2);
            JSONArray records = new JSONArray();
            for (Record record : mRecords.values()) {
                JSONObject item = new JSONObject();
                item.put("target", record.target);
                item.put("remote", record.remotePath);
                item.put("length", record.originalLength);
                // 接管登记没有摘要：写空串（org.json 的 put(name, null) 会直接删掉这个键，
                // 读侧就分不出“没有这个键”和“值为 null”，而空串是稳定的）。
                item.put("sha256", safe(record.sha256));
                item.put("original", nullable(record.originalLocalPath));
                item.put("preview", nullable(record.previewLocalPath));
                item.put("row", nullable(record.rowId));
                item.put("sha1", nullable(record.sha1));
                item.put("mtime", record.originalLocalMtime);
                item.put("local_state", record.localState.wire());
                item.put("preview_length", record.previewLength);
                item.put("preview_sha256", nullable(record.previewSha256));
                item.put("preview_format", nullable(record.previewFormat));
                JSONArray derived = new JSONArray();
                for (String path : record.derivedPaths) {
                    derived.put(path);
                }
                item.put("derived", derived);
                JSONArray aliases = new JSONArray();
                for (String path : record.localAliases) aliases.put(path);
                item.put("aliases", aliases);
                JSONArray identities = new JSONArray();
                for (String identity : record.identities) identities.put(identity);
                item.put("identities", identities);
                JSONArray fingerprints = new JSONArray();
                for (Map.Entry<String, Long> entry : record.derivedHashes.entrySet()) {
                    JSONObject fingerprint = new JSONObject();
                    fingerprint.put("sha256", entry.getKey());
                    fingerprint.put("length", entry.getValue());
                    fingerprints.put(fingerprint);
                }
                item.put("derived_hashes", fingerprints);
                records.put(item);
            }
            root.put("records", records);
            writeJsonAtomically(mFile, FILE_NAME, root);
            mHealthy = true;
        } catch (Throwable t) {
            mHealthy = false;
            throw new IOException("cannot persist original registry", t);
        }
    }

    /**
     * 原子写 JSON：临时文件 + fsync + 改名。
     *
     * <p>{@link RemoteSuppress} 的抑制账本用同一套写法（同目录、同风格）：
     * 两个账本都是"写一半就断电"会读坏的文件，而读坏之后行为会悄悄退化，
     * 所以只能靠改名来保证磁盘上永远是完整的一份。
     */
    static void writeJsonAtomically(File file, String fileName, JSONObject root)
            throws IOException {
        if (file == null) {
            throw new IOException("no ledger file configured");
        }
        File tmp = new File(file.getParentFile(), fileName + ".tmp");
        try {
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(root.toString().getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicFailure) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Throwable t) {
            try {
                Files.deleteIfExists(tmp.toPath());
            } catch (Throwable ignored) {
                // A half-written ledger must never be left behind under the real name.
            }
            if (t instanceof IOException) {
                throw (IOException) t;
            }
            throw new IOException("cannot persist ledger " + fileName, t);
        }
    }

    /** 读 JSON 文件；"读不出来"由调用方决定是兜底还是失败。 */
    static JSONObject readJsonFile(File file) throws IOException {
        if (file == null) {
            throw new IOException("no ledger file configured");
        }
        try {
            return new JSONObject(new String(Files.readAllBytes(file.toPath()),
                    StandardCharsets.UTF_8));
        } catch (JSONException invalid) {
            throw new IOException("invalid ledger json " + file.getName(), invalid);
        }
    }

    private void load() {
        if (!mFile.exists()) {
            return;
        }
        try {
            JSONObject root = readJsonFile(mFile);
            JSONArray records = root.optJSONArray("records");
            if (records == null || records.length() > MAX_RECORDS) {
                throw new IOException("invalid original registry record list");
            }
            for (int i = 0; i < records.length(); i++) {
                JSONObject item = records.getJSONObject(i);
                Set<String> derived = new LinkedHashSet<>();
                JSONArray paths = item.optJSONArray("derived");
                if (paths != null) {
                    for (int j = 0; j < paths.length(); j++) {
                        String path = clean(paths.optString(j, ""));
                        if (path != null) {
                            derived.add(path);
                        }
                    }
                }
                Set<String> aliases = readStringSet(item.optJSONArray("aliases"));
                Set<String> identities = readStringSet(item.optJSONArray("identities"));
                Map<String, Long> hashes = new LinkedHashMap<>();
                JSONArray fingerprints = item.optJSONArray("derived_hashes");
                if (fingerprints != null) {
                    for (int j = 0; j < fingerprints.length(); j++) {
                        JSONObject fingerprint = fingerprints.optJSONObject(j);
                        if (fingerprint == null) continue;
                        String hash = clean(fingerprint.optString("sha256", ""));
                        long length = fingerprint.optLong("length", -1L);
                        if (hash != null && hash.length() == 64 && length > 0) {
                            hashes.put(hash, length);
                        }
                    }
                }
                // 新字段一律走“缺键就兜底”：旧 v1 JSON 没有这四个键，读出来是
                // ORIGINAL / 0 / null，行为与升级前完全一致，且解析绝不抛异常。
                LocalState state = LocalState.fromWire(item.optString("local_state", ""));
                long previewLength = item.optLong("preview_length", 0L);
                if (previewLength < 0L) {
                    previewLength = 0L;
                }
                String previewSha256 = clean(item.optString("preview_sha256", ""));
                String previewFormat = clean(item.optString("preview_format", ""));
                Record record = new Record(item.optString("target", ""),
                        item.optString("remote", ""), item.optLong("length", 0L),
                        readDigest(item.optString("sha256", ""), state),
                        clean(item.optString("original", "")),
                        clean(item.optString("preview", "")),
                        clean(item.optString("row", "")), clean(item.optString("sha1", "")),
                        item.optLong("mtime", 0L), derived, identities, aliases, hashes, state,
                        previewLength, previewSha256, previewFormat);
                // 只有“接管登记”的两个状态允许没有摘要：ORIGINAL 表示“本地字节数与远端一致，
                // 但一个字节都没校验过”，REMOTE_ONLY 表示“只知道远端有”。这两种记录照样被
                // isProtectedUpload 拦上传、被 verifyRemote 判成“没验证过”，所以缺摘要不等于放行；
                // 其余状态（预览/写入中途/损坏）声称的是“校验过的字节”，缺摘要只能说明文件坏了。
                boolean digestOk = record.sha256 != null ? record.sha256.length() == 64
                        : state == LocalState.ORIGINAL || state == LocalState.REMOTE_ONLY;
                if (record.target.isEmpty() || record.remotePath.isEmpty()
                        || record.originalLength <= 0 || !digestOk) {
                    throw new IOException("invalid original registry record");
                }
                String key = recordKey(record);
                Record old = mRecords.get(key);
                putRecordLocked(key, old == null ? record : merge(old, record));
            }
        } catch (Throwable t) {
            mHealthy = false;
            Logger.e("event=original_store_load_failed", t);
        }
    }

    /**
     * 读摘要：空串（含缺失键与旧版本写出的 {@code "null"}）在“接管登记”的状态下按 null 处理；
     * 其余状态原样返回，交给 {@link #load()} 后面的校验把整表判成不健康 —— 普通记录缺摘要
     * 说明文件已经被写坏，不能悄悄降级成“没有摘要”。
     */
    private static String readDigest(String raw, LocalState state) {
        String digest = clean(raw);
        if (digest == null) {
            return state == LocalState.ORIGINAL || state == LocalState.REMOTE_ONLY ? null : raw;
        }
        return digest;
    }

    private void requireHealthyLocked() throws IOException {
        if (!mHealthy) {
            throw new IOException("original registry is unavailable; uploads are fail-closed");
        }
    }

    private Record findLocked(String target, String rowId, String sha1) {
        Record latest = null;
        for (Record record : mRecords.values()) {
            if (target.equals(record.target) && identityMatches(record, rowId, sha1)) {
                latest = record;
            }
        }
        return latest;
    }

    /**
     * 校验远端原图。
     *
     * <p>优先读远端哈希旁车（{@link RemoteHashes}）：命中就只花一次 {@code storedAs}
     * 的小请求，不再把整份原图 GET 回来重算摘要 —— 那是空间优化前最贵的一步。
     * 旁车过期或不存在时退回原来那条“整份回读 + 重算 SHA-256”的路，并在通过后把旁车补上。
     *
     * <p>失败语义没变：<b>只有确实验证通过才返回 true</b>。异常照旧抛给调用方
     * （{@code SlimHook} 已经按失败处理），绝不 fail-open。
     */
    private boolean verifyRemote(Record record, Config config) throws IOException {
        if (record.sha256 == null || record.sha256.length() != 64) {
            // 接管登记没有摘要：没有可校验的期望值，直接按“没验证过”处理
            return false;
        }
        File tempDir = new File(mFile.getParentFile(), "original_verify");
        if (!tempDir.exists() && !tempDir.mkdirs()) {
            throw new IOException("cannot create original verification directory");
        }
        WebDavClient client = verifyClient(config);
        RemoteHashes hashes = new RemoteHashes(config.getRemoteRoot(),
                RemoteHashes.forClient(client, tempDir));
        RemoteVerifyResult result = verifyRemote(record.remotePath, record.sha256,
                record.originalLength, hashes, new ClientGateway(client, tempDir,
                        record.originalLocalPath == null ? null
                                : new File(record.originalLocalPath)));
        if (!result.verified) {
            return false;
        }
        // 日志留在调用方而不是执行体里：执行体要能在没有 android.util.Log 的 JVM 单测里跑
        if (result.hashHit) {
            Logger.d("event=remote_hash_hit path=" + record.remotePath);
        } else if (result.stale) {
            Logger.d("event=remote_hash_stale path=" + record.remotePath);
        } else {
            Logger.d("event=remote_hash_backfilled path=" + record.remotePath);
        }
        return true;
    }

    /**
     * 取（或建立）校验用共享客户端。配置变了就换新的，旧的关掉。
     *
     * <p>故意不对外暴露关闭：这个客户端活到进程结束，和上传/下载队列的长连接是一个思路。
     */
    private static WebDavClient verifyClient(Config config) {
        String key = config.getBaseUrl() + "\n" + config.getUsername() + "\n"
                + config.getPassword() + "\n" + config.getRemoteRoot();
        WebDavClient cached = sVerifyClient;
        if (cached != null && key.equals(sVerifyClientKey)) {
            return cached;
        }
        synchronized (OriginalStore.class) {
            if (sVerifyClient != null && key.equals(sVerifyClientKey)) {
                return sVerifyClient;
            }
            WebDavClient fresh = new WebDavClient(config.getBaseUrl(), config.getUsername(),
                    config.getPassword());
            WebDavClient stale = sVerifyClient;
            sVerifyClient = fresh;
            sVerifyClientKey = key;
            if (stale != null) {
                try {
                    stale.close();
                } catch (Throwable ignored) {
                    // 换客户端时关不掉旧连接不影响本次校验
                }
            }
            return fresh;
        }
    }

    /** 旁车三态：命中（可直接信）、过期（摘要/长度对不上）、缺失。 */
    enum Decision {
        HIT,
        STALE,
        MISS
    }

    /**
     * 纯决策函数：只看旁车内容与登记值，不做任何 IO，单测直接覆盖三态边界。
     *
     * <p>{@code entry == null} 一律是 MISS —— 空对象绝不能被判成 HIT，否则“没有证据”
     * 会被当成“验证通过”。
     */
    static Decision decideRemoteHash(RemoteHashes.Entry entry, String expectedSha256,
            long expectedLength) {
        if (entry == null) {
            return Decision.MISS;
        }
        return RemoteHashes.matches(entry, expectedSha256, expectedLength)
                ? Decision.HIT : Decision.STALE;
    }

    /** 校验结论：{@code hashHit}/{@code stale} 只用于日志与统计，{@code verified} 才是答案。 */
    static final class RemoteVerifyResult {
        final boolean verified;
        final boolean hashHit;
        final boolean stale;

        RemoteVerifyResult(boolean verified, boolean hashHit, boolean stale) {
            this.verified = verified;
            this.hashHit = hashHit;
            this.stale = stale;
        }

        static RemoteVerifyResult failed() {
            return new RemoteVerifyResult(false, false, false);
        }
    }

    /**
     * 远端动作：抽出来是为了让上面那条主流程能在没有网络的情况下被单测覆盖。
     * 真实实现见 {@link ClientGateway}。
     */
    interface RemoteGateway {
        /** 对象还在、且大小与登记值一致（长度问不到时按通过 —— 见 {@code storedAs}）。 */
        boolean stillStored(String remotePath, long expectedLength) throws IOException;

        /** 核对远端字节（照片按 {@link RemoteByteCheck} 的策略：小的整份、大的取头尾）。 */
        boolean matchesRemoteBytes(String remotePath, String expectedSha256, long expectedLength)
                throws IOException;

        /**
         * 上一步核对通过后，用同一份字节算出的旁车证据（带头尾摘要）。
         *
         * <p>默认返回 null：不认识这个能力的实现（单测里的假 gateway）不写 v2 旁车，
         * 于是行为与以前完全一致。真实实现见 {@link ClientGateway}。
         */
        default RemoteHashes.Entry verifiedEvidence(String remotePath, String expectedSha256,
                long expectedLength) throws IOException {
            return null;
        }
    }

    /**
     * 旁车优先的校验执行体（包级可见，便于单测注入假 Io 与假 gateway）。
     *
     * <p>三条分支：
     * <ul>
     *   <li>HIT：旁车摘要/长度与登记值一致，再确认对象还在、大小没变 → 直接通过，
     *       <b>不做整份 GET</b>；</li>
     *   <li>STALE：旁车过期（换了文件、旁车被手改）→ 走一次整份回读，通过后用真实摘要重写旁车；</li>
     *   <li>MISS：没有旁车 → 走一次整份回读，通过后回填旁车。</li>
     * </ul>
     * 任何一步失败都返回 {@code verified=false}；旁车写入失败不影响结论（它只是缓存）。
     */
    static RemoteVerifyResult verifyRemote(String remotePath, String expectedSha256,
            long expectedLength, RemoteHashes hashes, RemoteGateway gateway) throws IOException {
        RemoteHashes.Entry entry = hashes == null ? null : hashes.readOrNull(remotePath);
        Decision decision = decideRemoteHash(entry, expectedSha256, expectedLength);
        // 旁车命中还不够：旁车描述的是“上次核对过的那一份”，原图可能已经被改名/删除/
        // 被别的工具覆盖。storedAs 用 HEAD 取长度、长度问不到时回退 PROPFIND Depth 0，
        // 它自己的重试逻辑已经够用，这里不再叠一层探测。
        //
        // 而且只认 v2 旁车（带被核对过的头尾摘要）：v1 只记了“上传时算过的整份摘要”，
        // 拿它当证据等于用“未核对过”的判断做放行，这正是这次要修掉的漏洞。
        if (decision == Decision.HIT && entry.hasChunks()
                && stillStored(gateway, remotePath, expectedLength)) {
            return new RemoteVerifyResult(true, true, false);
        }
        if (gateway == null
                || !gateway.matchesRemoteBytes(remotePath, expectedSha256, expectedLength)) {
            return RemoteVerifyResult.failed();
        }
        // 只有真正验证通过才回写旁车：验证失败时写进去的是一条假证据，下一次就会拿它放行。
        // 写的是 v2（带头尾摘要）——回写 v1 没有意义，因为它已经不被信任了。
        if (hashes != null) {
            RemoteHashes.Entry evidence = gateway.verifiedEvidence(remotePath, expectedSha256,
                    expectedLength);
            if (evidence != null) {
                hashes.tryWriteChunked(remotePath, evidence.sha256, evidence.length,
                        evidence.headSha256, evidence.headLength, evidence.tailSha256,
                        evidence.tailLength);
            }
        }
        return new RemoteVerifyResult(true, false, decision == Decision.STALE);
    }

    /** 探测失败不能当成“还在”：一次网络抖动不该被读成“验证通过”。 */
    private static boolean stillStored(RemoteGateway gateway, String remotePath,
            long expectedLength) {
        if (gateway == null) {
            return false;
        }
        try {
            return gateway.stillStored(remotePath, expectedLength);
        } catch (Throwable t) {
            Logger.d("event=remote_hash_probe_failed path=" + remotePath + " reason=" + t);
            return false;
        }
    }

    /**
     * 真实网络实现。整份回读仍然落在原来那个临时目录里（{@code original_verify}），
     * 长度或摘要任一对不上就判失败。
     */
    private static final class ClientGateway implements RemoteGateway {
        private final WebDavClient mClient;
        private final File mTempDir;
        private final File mLocalOriginal;
        private RemoteHashes.Entry mEvidence;

        ClientGateway(WebDavClient client, File tempDir, File localOriginal) {
            mClient = client;
            mTempDir = tempDir;
            mLocalOriginal = localOriginal;
        }

        /**
         * 远端原件是否还在、长度是否还是这个值。
         *
         * <p>先用一次 PROPFIND Depth 0 问大小，问得出来就直接给答案：这台 OpenList 对移动客户端
         * 的 HEAD 会整条挂住十几秒（模块日志里成片的 {@code HEAD ... failed: SocketTimeoutException}），
         * 而空间优化每张照片都要走这一步，HEAD 一卡就变成 20 秒一张。PROPFIND 0.3 秒就能答。
         * 只有 PROPFIND 问不出来（不支持/网络失败/大小未知）才退回原来那条 HEAD+PROPFIND 的路。
         */
        @Override
        public boolean stillStored(String remotePath, long expectedLength) throws IOException {
            // storedAs 现在先走 PROPFIND、问不出来才走 HEAD，这里直接用即可
            return mClient.storedAs(remotePath, expectedLength);
        }

        /**
         * 核对远端字节。本地原图还在时按 {@link RemoteByteCheck} 的策略比（照片小的整份、
         * 大的头尾各 {@link RemoteByteCheck#SAMPLE_CHUNK}），并把这次比过的两段留成旁车证据；
         * 本地文件不在（镜像/已瘦身）时才退回整份 GET。
         */
        @Override
        public boolean matchesRemoteBytes(String remotePath, String expectedSha256,
                long expectedLength) throws IOException {
            File local = mLocalOriginal;
            if (local != null && local.isFile() && local.length() == expectedLength) {
                if (!RemoteByteCheck.verify(mClient, remotePath, local, expectedLength,
                        expectedSha256)) {
                    return false;
                }
                mEvidence = RemoteByteCheck.chunkedEntry(local, expectedSha256);
                return true;
            }
            File remoteCopy = File.createTempFile("verify-", ".tmp", mTempDir);
            try {
                mClient.get(remotePath, remoteCopy);
                if (remoteCopy.length() != expectedLength
                        || !expectedSha256.equals(sha256(remoteCopy))) {
                    return false;
                }
                mEvidence = RemoteByteCheck.chunkedEntry(remoteCopy, expectedSha256);
                return true;
            } finally {
                if (remoteCopy.exists() && !remoteCopy.delete()) {
                    Logger.d("event=original_verify_temp_cleanup_failed path="
                            + remoteCopy.getAbsolutePath());
                }
            }
        }

        @Override
        public RemoteHashes.Entry verifiedEvidence(String remotePath, String expectedSha256,
                long expectedLength) {
            return mEvidence;
        }
    }

    /**
     * 登记闸门：空 / 全 0 / 缺签名的内容绝不进登记表。
     *
     * <p>2026-10 事故：{@code originals.json} 里有 24 条记录的 {@code sha256} 恰好等于
     * SHA256(N 个 0)，也就是"空内容"被当成正常原件登记了。这里在写盘之前拦住。
     */
    private static void requireUsablePayload(File file, String role) {
        if (!MediaPayload.isUsableMedia(file)) {
            throw new IllegalStateException("cannot register invalid payload role=" + role
                    + " path=" + (file == null ? "null" : file.getAbsolutePath())
                    + " size=" + (file == null ? -1L : file.length()));
        }
    }

    /** 摘要等于"全 0 内容"的 SHA-256 时，这条记录描述的不是一张照片。 */
    private static void requireNonZeroDigest(String sha256, long length, String role) {
        if (MediaPayload.isZeroDigest(sha256, length)) {
            throw new IllegalStateException("cannot register invalid payload role=" + role
                    + " reason=zero_digest length=" + length);
        }
    }

    private static boolean containsLocalPath(Record record, String path) {
        return record.localAliases.contains(path) || path.equals(record.originalLocalPath)
                || path.equals(record.previewLocalPath) || record.derivedPaths.contains(path);
    }

    private static Set<String> readStringSet(JSONArray values) {
        Set<String> result = new LinkedHashSet<>();
        if (values != null) {
            for (int i = 0; i < values.length(); i++) {
                String value = clean(values.optString(i, ""));
                if (value != null) result.add(value);
            }
        }
        return result;
    }

    private static String recordKey(Record record) {
        // sha256 可能是 null（接管登记）：用 safe() 让“没有摘要”这条记录在内存与落盘后
        // 拿到同一个键，否则 reload 之后键变了，记录会被当成另一条。
        return record.target + "\n" + record.remotePath + "\n" + safe(record.sha256);
    }

    private static String canonical(File file) throws IOException {
        return file.getCanonicalPath();
    }

    private static String nullable(String value) {
        return value == null ? JSONObject.NULL.toString() : value;
    }

    private static String clean(String value) {
        return value == null || value.trim().isEmpty() || "null".equals(value)
                ? null : value.trim();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String safeBaseUrl(String value) {
        if (value == null) {
            return "";
        }
        try {
            URI uri = new URI(value.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.US);
            String host = uri.getHost() == null ? Config.safeHost(value) : uri.getHost().toLowerCase(Locale.US);
            int port = uri.getPort();
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            while (path.endsWith("/") && !path.isEmpty()) {
                path = path.substring(0, path.length() - 1);
            }
            return scheme + "://" + host + (port < 0 ? "" : ":" + port) + path;
        } catch (Throwable ignored) {
            return Config.safeHost(value);
        }
    }

    private static String normalizeStorageAlias(String value) {
        String path = value.replace('\\', '/');
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        String lower = path.toLowerCase(Locale.US);
        if (lower.equals("/sdcard") || lower.startsWith("/sdcard/")) {
            path = "/storage/emulated/0" + path.substring("/sdcard".length());
        } else if (lower.equals("/mnt/sdcard") || lower.startsWith("/mnt/sdcard/")) {
            path = "/storage/emulated/0" + path.substring("/mnt/sdcard".length());
        } else if (lower.equals("/storage/emulated/legacy")
                || lower.startsWith("/storage/emulated/legacy/")) {
            path = "/storage/emulated/0" + path.substring("/storage/emulated/legacy".length());
        } else if (lower.equals("/storage/self/primary")
                || lower.startsWith("/storage/self/primary/")) {
            path = "/storage/emulated/0" + path.substring("/storage/self/primary".length());
        }
        return path.toLowerCase(Locale.US);
    }

    static String sha256(File file) throws IOException {
        MessageDigest digest = newDigest();
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = in.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
        }
        return hex(digest.digest());
    }

    private static boolean isSha256(String value) {
        if (value == null || value.length() != 64) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F'))) {
                return false;
            }
        }
        return true;
    }

    static String sha256(byte[] value) {
        return hex(newDigest().digest(value));
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by Android", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            out.append(String.format(Locale.US, "%02x", value & 0xff));
        }
        return out.toString();
    }
}
