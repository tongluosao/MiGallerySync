package com.tongluosao.gallerysync.core;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 回收站：本地删掉一张照片时，把远端那一份**搬走**而不是留在原地。
 *
 * <p><b>为什么必须有它</b>：下载补齐（{@link DownloadQueue}）的判据是"远端有、本地没有"。
 * 用户删掉一张照片之后，本地就没有它了 —— 于是下一轮补齐会把它**从远端重新下回来**，
 * 表现成"删了又回来了"，这显然不是用户要的。两难在于：
 * <ul>
 *   <li>什么都不做 → 照片被下回来。</li>
 *   <li>跟着删远端 → 用户手滑删掉本地时，备份也跟着没了，那是不可逆的丢失。</li>
 * </ul>
 * 回收站是第三条路：远端那一份搬到 {@code <root>_trash/} 下，既不再触发补齐，
 * 也没有真的销毁 —— 用户随时能从 WebDAV 上把它捞回来。
 *
 * <p><b>目录位置</b>：与 {@code remote_root} **齐平**，而不是在它里面。
 * 理由有两个：一是回收站不是备份内容，混在备份树里会让"远端有多少张照片"这个数字失真；
 * 二是与上传的路径计算完全解耦 —— 回收站里的东西永远不会被上传侧的"远端同名就跳过"
 * 当成已备份。
 *
 * <p><b>怎么知道用户删了什么</b>：hook 相册的删除方法拿到 cloud 表行号，再回表里读
 * {@code localFile} 反推远端路径。**不监听删除后的结果**，因为"本地文件真的没了"
 * 这件事在删除途中是拿不到的；而行号是删除动作本身，最贴近用户意图。
 *
 * <p><b>什么时候真的搬</b>：不是删的当下。删除是一个可能被撤销/重试的操作，
 * 而且相册会先把 {@code localFlag} 置 2、随后才由同步链路真正提交。
 * 所以这里只把"待搬"记下来，等上传队列空转时（与下载补齐同一个节拍）再动手 ——
 * 那时候删除早已尘埃落定，搬错了也没人看得到。
 */
public final class TrashQueue {

    private static final String STATE_DIR = "webdav_sync";
    private static final String STATE_FILE = "state.json";

    /** 回收到这里：与 remote_root 齐平。后缀固定，用户一眼能认出来 */
    private static final String TRASH_SUFFIX = "_trash";

    /** 一条待搬的记录最多留多久（毫秒）。超过就当"用户又传回来了"，不再搬 */
    private static final long ENTRY_TTL_MS = 30L * 24 * 60 * 60 * 1000;
    /** 待搬队列上限，防止相册批量删除时无限堆积 */
    private static final int MAX_ENTRIES = 5000;
    /** 一轮最多搬几个，别一次把带宽占满 */
    private static final int MAX_MOVES_PER_ROUND = 40;

    private static volatile TrashQueue sInstance;

    private final Context mContext;
    private final Object mLock = new Object();

    /** 待搬：远端路径 → 记下来的时刻 */
    private final LinkedHashMap<String, Long> mPending = new LinkedHashMap<>();
    /** 已经搬完的，避免重复搬（同一路径被删两次） */
    private final LinkedHashMap<String, Long> mMoved = new LinkedHashMap<>();

    private volatile Config mConfig;
    private volatile boolean mRunning = true;
    private volatile WebDavClient mClient;
    private volatile String mClientTarget = "";

    private TrashQueue(Context context, Config config) {
        mContext = context.getApplicationContext();
        mConfig = config;
        load();
    }

    public static TrashQueue get(Context context, Config config) {
        if (sInstance == null) {
            synchronized (TrashQueue.class) {
                if (sInstance == null) {
                    sInstance = new TrashQueue(context, config);
                }
            }
        }
        return sInstance;
    }

    public void setConfig(Config config) {
        mConfig = config;
    }

    /**
     * 回收站目录的绝对远端路径：{@code <root>_trash}。
     *
     * <p>与 {@code remote_root} **齐平**：{@code MiGallerySync} → {@code MiGallerySync_trash}。
     * 根目录为空时退回 {@code _trash}（不会与任何正常目录撞名，因为它以 {@code _} 开头）。
     */
    public static String trashRoot(Config config) {
        String root = config == null ? "" : config.getRemoteRoot();
        if (root == null || root.trim().isEmpty()) {
            return "_trash";
        }
        // 只取最后一段再拼：{@code a/b} → {@code a/b_trash}，与"齐平"的语义一致
        return root + TRASH_SUFFIX;
    }

    /**
     * 记下"这些远端路径该被搬到回收站"。
     *
     * <p>由删除 hook 调用，参数是已经算好的远端路径。已经在回收站里的一律忽略 ——
     * 那说明它上一次已经被搬走了，再搬一次就是搬进回收站里的回收站。
     */
    public void schedule(List<String> remotePaths) {
        if (remotePaths == null || remotePaths.isEmpty()) {
            return;
        }
        String trash = trashRoot(mConfig);
        int added = 0;
        long now = System.currentTimeMillis();
        synchronized (mLock) {
            for (String path : remotePaths) {
                if (path == null || path.isEmpty()) {
                    continue;
                }
                if (path.startsWith(trash + "/") || path.equals(trash)) {
                    continue;
                }
                if (mMoved.containsKey(path) || mPending.containsKey(path)) {
                    continue;
                }
                if (mPending.size() >= MAX_ENTRIES) {
                    // 满了就先清最老的：最老的那些多半已经过期了
                    Iterator<String> it = mPending.keySet().iterator();
                    if (it.hasNext()) {
                        it.next();
                        it.remove();
                    }
                }
                mPending.put(path, now);
                added++;
            }
            if (added > 0) {
                saveLocked();
            }
        }
        if (added > 0) {
            Logger.i("event=trash_scheduled added=" + added + " pending=" + mPending.size());
        }
    }

    /**
     * 上传队列空转时叫一趟：把待搬的搬掉。
     *
     * <p>刻意与下载补齐共用这个节拍（都由 {@code UploadQueue} 的 idle 回调驱动）：
     * 只有"上传没在忙"的时候才动手，用户正在传照片时不去抢带宽。
     *
     * @return 这一轮搬走了几个
     */
    public int drain() {
        Config config = mConfig;
        if (config == null || !config.isReady() || config.isOff()) {
            return 0;
        }
        List<String> batch = new ArrayList<>();
        synchronized (mLock) {
            long now = System.currentTimeMillis();
            Iterator<java.util.Map.Entry<String, Long>> it = mPending.entrySet().iterator();
            while (it.hasNext() && batch.size() < MAX_MOVES_PER_ROUND) {
                java.util.Map.Entry<String, Long> entry = it.next();
                if (now - entry.getValue() > ENTRY_TTL_MS) {
                    // 太久没搬成的：多半是远端本来就没有这一份（用户早就删过，
                    // 或者它从来就没传上去过）。再挂着只会一直重试
                    it.remove();
                    Logger.d("event=trash_expired remote=" + entry.getKey());
                    continue;
                }
                batch.add(entry.getKey());
                it.remove();
            }
        }
        if (batch.isEmpty()) {
            return 0;
        }
        int moved = 0;
        for (String remote : batch) {
            boolean ok = moveOne(remote, config);
            if (ok) {
                moved++;
                synchronized (mLock) {
                    mMoved.put(remote, System.currentTimeMillis());
                }
            } else {
                // 搬不成的放回队列，下一轮再试（远端文件可能只是暂时读不到）
                synchronized (mLock) {
                    mPending.put(remote, System.currentTimeMillis());
                }
            }
        }
        synchronized (mLock) {
            trimMovedLocked();
            saveLocked();
        }
        if (moved > 0) {
            Logger.i("event=trash_drained moved=" + moved + " left=" + mPending.size());
        }
        return moved;
    }

    /**
     * 搬一个文件到回收站。
     *
     * <p>先在回收站里**原样重建**它在上传树里的相对路径（{@code DCIM/Camera/x.jpg} →
     * {@code MiGallerySync_trash/DCIM/Camera/x.jpg}），这样用户翻回收站时还能看出
     * 这张照片原本属于哪个相册 —— 全平铺到回收站根目录就再也分不清了。
     *
     * @return true 表示已经搬走（或源本来就不存在）
     */
    private boolean moveOne(String remote, Config config) {
        String trash = trashRoot(config);
        String relative = relativeTo(remote, config.getRemoteRoot());
        String dest = relative.isEmpty() ? trash + "/" + leaf(remote) : trash + "/" + relative;
        try {
            WebDavClient client = clientFor(config);
            String canonicalSource = client.resolveExistingFilePath(remote);
            if (canonicalSource == null) {
                Logger.w("event=trash_source_dir_unresolved remote=" + remote);
                return false;
            }
            remote = canonicalSource;
            // MOVE 的父目录必须已经存在，否则服务器回 409
            String destDir = dest.contains("/") ? dest.substring(0, dest.lastIndexOf('/')) : "";
            if (!destDir.isEmpty()) {
                String canonicalDestDir = client.resolveCollectionPath(destDir);
                if (canonicalDestDir == null) {
                    Logger.w("event=trash_dest_dir_unresolved dir=" + destDir);
                    return false;
                }
                dest = WebDavClient.joinPath(canonicalDestDir, leaf(dest));
            }
            String attempt = dest;
            boolean ok = client.move(remote, attempt);
            if (!ok) {
                // 多半是回收站里已有同名（Overwrite: F 会让服务器回 412）。
                // 加时间戳后缀再试一次 —— 回收站里同名意味着"这个名字删过两次"，
                // 覆盖等于悄悄丢掉一份，加后缀才是对的
                String suffix = String.valueOf(System.currentTimeMillis());
                int dot = attempt.lastIndexOf('.');
                attempt = dot > attempt.lastIndexOf('/')
                        ? attempt.substring(0, dot) + "." + suffix + attempt.substring(dot)
                        : attempt + "." + suffix;
                ok = client.move(remote, attempt);
            }
            if (ok) {
                Logger.i("event=trash_moved remote=" + remote + " to=" + attempt);
            } else {
                Logger.w("event=trash_move_failed remote=" + remote + " to=" + dest);
            }
            return ok;
        } catch (Throwable t) {
            Logger.w("event=trash_move_error remote=" + remote + " detail=" + t);
            return false;
        }
    }

    /** 去掉 {@code remote_root/} 前缀；不在它下面时返回空串（调用方退回用文件名） */
    static String relativeTo(String remote, String root) {
        if (root == null || root.isEmpty()) {
            return remote;
        }
        String prefix = root + "/";
        return remote.startsWith(prefix) ? remote.substring(prefix.length()) : "";
    }

    private static String leaf(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 && slash + 1 < path.length() ? path.substring(slash + 1) : path;
    }

    private WebDavClient clientFor(Config config) {
        // 密码进签名：用户改了密码之后旧连接上的凭据就是错的，必须重建
        String signature = Config.safeHost(config.getBaseUrl()) + "|" + config.getUsername()
                + "|" + config.getPassword();
        WebDavClient client = mClient;
        if (client == null || !signature.equals(mClientTarget)) {
            if (client != null) {
                client.close();
            }
            client = new WebDavClient(config.getBaseUrl(), config.getUsername(),
                    config.getPassword());
            mClient = client;
            mClientTarget = signature;
        }
        return client;
    }

    public void shutdown() {
        synchronized (TrashQueue.class) {
            sInstance = null;
        }
        mRunning = false;
        WebDavClient client = mClient;
        if (client != null) {
            client.close();
        }
    }

    private void trimMovedLocked() {
        // 搬完的记录只是用来防重复搬，留太多没意义；与 done 表同一量级即可
        while (mMoved.size() > 20000) {
            Iterator<String> it = mMoved.keySet().iterator();
            if (!it.hasNext()) {
                break;
            }
            it.next();
            it.remove();
        }
    }

    private File stateFile() {
        File dir = new File(mContext.getFilesDir(), STATE_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            Logger.w("event=state_dir_failed dir=" + dir.getAbsolutePath());
        }
        return new File(dir, STATE_FILE);
    }

    /**
     * 写进与上传/下载共用的 state.json。
     *
     * <p>三个队列各写各的键，读的时候各读各的，互不覆盖 —— 读改写合并，
     * 否则这里会把上传的 pending 与下载的 dl_done 整个抹掉。
     */
    private void saveLocked() {
        if (!mRunning) {
            return;
        }
        File file = stateFile();
        File tmp = new File(file.getParentFile(), STATE_FILE + ".trash.tmp");
        try {
            JSONObject root = new JSONObject();
            if (file.exists()) {
                root = new JSONObject(new String(
                        Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            }
            JSONObject pending = new JSONObject();
            for (java.util.Map.Entry<String, Long> entry : mPending.entrySet()) {
                pending.put(entry.getKey(), entry.getValue());
            }
            JSONObject moved = new JSONObject();
            for (java.util.Map.Entry<String, Long> entry : mMoved.entrySet()) {
                moved.put(entry.getKey(), entry.getValue());
            }
            root.put("trash_pending", pending);
            root.put("trash_moved", moved);
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(root.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(file)) {
                Files.deleteIfExists(tmp.toPath());
            }
        } catch (Throwable t) {
            Logger.w("event=trash_state_save_failed", t);
            try {
                Files.deleteIfExists(tmp.toPath());
            } catch (Throwable ignored) {
                // 清不掉就算了，下次覆盖写
            }
        }
    }

    private void load() {
        File file = stateFile();
        if (!file.exists()) {
            return;
        }
        try {
            JSONObject root = new JSONObject(new String(
                    Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            synchronized (mLock) {
                readMap(root.optJSONObject("trash_pending"), mPending);
                readMap(root.optJSONObject("trash_moved"), mMoved);
            }
            if (!mPending.isEmpty() || !mMoved.isEmpty()) {
                Logger.i("event=trash_state_loaded pending=" + mPending.size()
                        + " moved=" + mMoved.size());
            }
        } catch (Throwable t) {
            Logger.w("event=trash_state_load_failed", t);
        }
    }

    private static void readMap(JSONObject source, LinkedHashMap<String, Long> into) {
        if (source == null) {
            return;
        }
        Iterator<String> keys = source.keys();
        List<String> list = new ArrayList<>();
        while (keys.hasNext()) {
            list.add(keys.next());
        }
        for (String key : list) {
            into.put(key, source.optLong(key, 0L));
        }
    }

    // ------------------------------------------------------------------ 与相册的桥

    /**
     * 从 cloud 表把 cloudIds 反推成远端路径。
     *
     * <p>为什么按 `localFile` 反推而不是从删除 hook 里直接拿路径：删除方法拿到的是**行号**，
     * 行号到文件路径只有一张表能给出答案。反推走的是与上传**完全同一套**的
     * {@link UploadQueue#remotePathFor} 逻辑，所以算出来的路径与上传时落盘的那个一定一致。
     *
     * <p>查询按 id 分批：一次删几百上千张时，一条 `IN (...)` 里的参数太多会被 SQLite
     * 拒掉（默认变量上限 999）。分批之后每批都在安全范围内。
     */
    public static List<String> remotePathsOf(Context context, Config config, long[] cloudIds) {
        List<String> out = new ArrayList<>();
        if (cloudIds == null || cloudIds.length == 0 || config == null) {
            return out;
        }
        Set<String> wanted = new LinkedHashSet<>();
        for (long id : cloudIds) {
            if (id > 0) {
                wanted.add(String.valueOf(id));
            }
        }
        if (wanted.isEmpty()) {
            return out;
        }
        List<String> ids = new ArrayList<>(wanted);
        final int batch = 400;
        for (int start = 0; start < ids.size(); start += batch) {
            List<String> slice = ids.subList(start, Math.min(start + batch, ids.size()));
            Cursor cursor = null;
            try {
                StringBuilder selection = new StringBuilder("_id IN (");
                for (int i = 0; i < slice.size(); i++) {
                    selection.append(i == 0 ? "?" : ",?");
                }
                selection.append(')');
                cursor = context.getContentResolver().query(CLOUD_URI,
                        new String[]{"localFile"}, selection.toString(),
                        slice.toArray(new String[0]), null);
                if (cursor == null) {
                    continue;
                }
                while (cursor.moveToNext()) {
                    String local = cursor.getString(0);
                    String remote = remotePathFor(local, config);
                    if (remote != null && !remote.isEmpty()) {
                        out.add(remote);
                    }
                }
            } catch (Throwable t) {
                Logger.w("event=trash_query_failed detail=" + t);
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }
        return out;
    }

    private static final Uri CLOUD_URI =
            Uri.parse("content://com.miui.gallery.cloud.provider/cloud");

    /**
     * 本地绝对路径 → 远端路径。与上传侧共用一套规则，保证"搬的正是上传时写的那一个"。
     *
     * <p>三种分目录方式各自对齐 {@code UploadQueue.remoteDirFor}：
     * <ul>
     *   <li>{@code flat} → {@code <root>/<文件名>}</li>
     *   <li>{@code album} → {@code <root>/<相册目录>/<文件名>}；
     *       **相册目录推不出来时返回 null**，而不是自己编一个年/月 ——
     *       上传侧那种情况退回按时间分，而这里只有路径、没有拍摄时间，
     *       编出来的日期目录几乎必然与真正落盘的那个不同，MOVE 会回 404 然后被当成
     *       "远端没有这一份"而放弃。返回 null 让调用方干脆不排这一项，语义更干净。</li>
     *   <li>{@code date} → 同上，需要拍摄时间才能算，这里拿不到，返回 null
     *       （按时间分时回收站会漏掉本地删除的项；这是已知取舍，见下面的日志）。</li>
     * </ul>
     */
    static String remotePathFor(String localPath, Config config) {
        if (localPath == null || localPath.trim().isEmpty()) {
            return null;
        }
        String path = localPath.trim();
        if (!path.startsWith("/")) {
            path = "/storage/emulated/0/" + path;
        }
        String layout = config.getRemoteLayout();
        String root = config.getRemoteRoot();
        if (Config.LAYOUT_FLAT.equals(layout)) {
            return WebDavClient.joinPath(root, leaf(path));
        }
        // album 与 date 都需要额外信息：album 靠路径反推，date 靠拍摄时间。
        // 前者推不出来、后者根本无从得知时，一律不排 —— 宁可漏搬，不要搬错。
        String album = WebDavClient.albumDirOf(path);
        if (album.isEmpty()) {
            return null;
        }
        return WebDavClient.joinPath(WebDavClient.joinPath(root, album), leaf(path));
    }

    /** 仅诊断用：看一眼待搬队列里有什么 */
    Set<String> pendingSnapshot() {
        synchronized (mLock) {
            return new HashSet<>(mPending.keySet());
        }
    }
}
