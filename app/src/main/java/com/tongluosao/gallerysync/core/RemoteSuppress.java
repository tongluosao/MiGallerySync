package com.tongluosao.gallerysync.core;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 「这个远端对象是用户主动删掉的」账本。
 *
 * <p>要解决的问题（2026-10-19 20:24-20:37）：{@code MetadataRestoreJob} 删掉一条
 * {@code legacy_copies} 垃圾副本后，远端那份 {@code MiGallerySync/DCIM/Camera/*.jpg} 还在，
 * {@link DownloadQueue#walk} 看到「远端有、本地没有」就按补齐判据把它下了回来，
 * 扫描器又给这条落盘文件新建一行 cloud 记录，下一轮又出现同样的形态 —— 493 个文件就这样
 * 被反复下回来，用户删一次，模块抢一次。
 *
 * <p>所以删除动作必须留下痕迹：删掉的那份垃圾副本对应的远端路径记进这个账本，
 * 下载侧（{@link DownloadQueue}）与上传侧（{@link UploadQueue}）都先问一句，
 * 命中就不再碰这个远端对象。用户自己点「下载原图」走的是相册原生请求链路，不经过这里，
 * 所以按需取回原图的能力不受影响。
 *
 * <p>账本与 {@link OriginalStore} 同目录、同 JSON 写法、同样按目标（主机+账号+远端根目录）分区：
 * 换账号/换根目录是另一个远端世界，旧账本不该生效。文件读不出来时一律<b>当作没有抑制</b>
 * 并停止写入 —— 账本坏了顶多回到旧行为，绝不能让相册因为账本崩掉。
 */
public final class RemoteSuppress {

    private static final String DIR = "webdav_sync";
    private static final String FILE_NAME = "suppressed.json";
    private static final String KEY_VERSION = "version";
    private static final String KEY_ENTRIES = "entries";
    private static final String KEY_KEY = "key";
    private static final String KEY_AT = "at";

    /** 账本条目上限：超出后丢最老的。正常用户一年也删不到这个量级。 */
    static final int MAX_ENTRIES = 100_000;
    /** album 布局下，存储在根目录之后的相对路径就是远端路径 */
    private static final String STORAGE_ROOT = "/storage/emulated/0";
    /** 模块自己生成的瘦身副本目录：里面的文件从来没有对应的远端对象 */
    private static final String SLIM_SEGMENT = "/.gallerysync-slim/";

    private static volatile RemoteSuppress sInstance;

    private final Object mLock = new Object();
    /** null 表示降级实例（账本不可用）：只做内存判断，永不落盘 */
    private final File mFile;
    private final String mFileName;
    private final int mMaxEntries;
    /** 键 = 目标 + "\n" + 规整后的远端路径；值 = 抑制时间（用于淘汰最老的） */
    private final LinkedHashMap<String, Long> mEntries = new LinkedHashMap<>();
    private boolean mHealthy = true;
    /** 写盘失败过一次：本次进程内不再尝试落盘，只保留内存账本 */
    private boolean mWriteBlocked;

    private RemoteSuppress(Context context) {
        this(new File(context.getApplicationContext().getFilesDir(), DIR));
    }

    RemoteSuppress(File directory) {
        this(directory, MAX_ENTRIES);
    }

    /** 上限可调，便于测试用小上限验证淘汰行为。 */
    RemoteSuppress(File directory, int maxEntries) {
        mMaxEntries = Math.max(maxEntries, 1);
        mFileName = FILE_NAME;
        if (directory == null) {
            mFile = null;
            mHealthy = false;
            return;
        }
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            Logger.w("event=remote_suppress_dir_failed dir=" + directory);
            mFile = null;
            mHealthy = false;
            return;
        }
        mFile = new File(directory, FILE_NAME);
        load();
    }

    /** 降级实例：进程里用，不落盘。 */
    private RemoteSuppress() {
        mFile = null;
        mFileName = FILE_NAME;
        mMaxEntries = MAX_ENTRIES;
        mHealthy = false;
    }

    public static RemoteSuppress get(Context context) {
        RemoteSuppress instance = sInstance;
        if (instance != null) {
            return instance;
        }
        synchronized (RemoteSuppress.class) {
            if (sInstance == null) {
                try {
                    sInstance = new RemoteSuppress(context);
                } catch (Throwable t) {
                    Logger.w("event=remote_suppress_init_failed reason=" + t);
                    sInstance = new RemoteSuppress();
                }
            }
            return sInstance;
        }
    }

    /**
     * 这条远端路径是不是被用户主动删过。
     *
     * <p>算不出键（没有配置/空路径）、账本读不出来、文件坏了，一律返回 false：
     * 抑制闸失灵时的行为必须与引入账本之前完全一致。
     */
    public boolean isSuppressed(Config config, String remotePath) {
        String key = entryKey(config, remotePath);
        if (key == null) {
            return false;
        }
        synchronized (mLock) {
            return mEntries.containsKey(key);
        }
    }

    /**
     * 记账：这条远端路径以后不要再自动下载/上传回来。
     *
     * <p>幂等：已经在账本里的不再写盘（{@code MetadataRestoreJob} 一轮可能删几百条，
     * 重复的那部分不该产生几百次全量 JSON 重写）。账本本身不可用时只记日志、不写：
     * 写坏一次可能把已经有效的旧账本一起弄丢。
     */
    public void suppress(Config config, String remotePath) {
        String key = entryKey(config, remotePath);
        if (key == null) {
            return;
        }
        synchronized (mLock) {
            if (mEntries.containsKey(key)) {
                return;
            }
            if (!mHealthy) {
                Logger.w("event=remote_suppress_write_skipped remote=" + remotePath
                        + " reason=ledger_unavailable");
                return;
            }
            mEntries.put(key, System.currentTimeMillis());
            trimLocked();
            if (mWriteBlocked) {
                // 这次进程里已经降级成"内存账本"：本条照样生效，但别再每个条目都试一次写盘
                Logger.d("event=remote_suppress_memory_only entries=" + mEntries.size());
                return;
            }
            try {
                saveLocked();
                Logger.i("event=remote_suppressed remote=" + normalizeRemotePath(remotePath)
                        + " entries=" + mEntries.size());
            } catch (Throwable t) {
                // 写不进去（磁盘满、文件坏了）就降级成"只在本次进程里生效"：既不回滚这次抑制
                // （本条已在内存里生效，下载线程立刻能问到），也不让删除任务跟着失败。
                mWriteBlocked = true;
                Logger.e("event=remote_suppress_save_failed remote=" + remotePath, t);
            }
        }
    }

    /** 账本条目条数（测试与排障用）。 */
    int size() {
        synchronized (mLock) {
            return mEntries.size();
        }
    }

    int maxEntries() {
        return mMaxEntries;
    }

    /**
     * 被删掉的本地垃圾副本对应的远端路径。
     *
     * <p>注册表里登记过就用登记的那一条 —— 那是唯一权威来源（按时间/全平铺布局也能拿到正确路径）。
     * 没登记时只在 album 布局下反推：{@code 远端根 + "/" + 存储根之后的相对路径}，
     * 因为该布局下远端目录就是相册目录。其余情况一律返回 null：宁可这条不抑制，
     * 也不要猜错路径把别的照片挡在门外。
     */
    public static String remotePathFor(Config config, String registeredRemote, String localPath) {
        String registered = normalizeRemotePath(registeredRemote);
        if (!registered.isEmpty()) {
            return registered;
        }
        if (config == null || localPath == null) {
            return null;
        }
        if (!Config.LAYOUT_ALBUM.equals(config.getRemoteLayout())) {
            return null;
        }
        String path = localPath.trim().replace('\\', '/');
        String lower = path.toLowerCase(Locale.ROOT);
        // 瘦身副本目录里的文件从来没有远端对象，反推只会编出一条不存在的路径
        if (lower.contains(SLIM_SEGMENT) || lower.endsWith("/.gallerysync-slim")) {
            return null;
        }
        if (!path.startsWith(STORAGE_ROOT + "/")) {
            return null;
        }
        String relative = path.substring(STORAGE_ROOT.length() + 1);
        if (relative.isEmpty()) {
            return null;
        }
        String remote = normalizeRemotePath(WebDavClient.joinPath(config.getRemoteRoot(), relative));
        return remote.isEmpty() ? null : remote;
    }

    /** 账本键：目标 + 规整后的远端路径；参数不足时返回 null（表示"不记也不问"）。 */
    static String entryKey(Config config, String remotePath) {
        if (config == null) {
            return null;
        }
        String path = normalizeRemotePath(remotePath);
        if (path.isEmpty()) {
            return null;
        }
        String target = OriginalStore.targetKey(config);
        if (target.isEmpty()) {
            return null;
        }
        return target + "\n" + path;
    }

    /**
     * 规整远端路径：统一斜杠方向、压掉重复斜杠、去掉首尾斜杠。
     *
     * <p>同一对象在注册表里可能带前导斜杠、在 walk 里不带，两种写法必须命中同一条记录。
     */
    static String normalizeRemotePath(String remotePath) {
        if (remotePath == null) {
            return "";
        }
        String path = remotePath.trim().replace('\\', '/');
        StringBuilder out = new StringBuilder(path.length());
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '/' && out.length() > 0 && out.charAt(out.length() - 1) == '/') {
                continue;
            }
            out.append(c);
        }
        String result = out.toString();
        while (result.startsWith("/")) {
            result = result.substring(1);
        }
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private void trimLocked() {
        Iterator<Map.Entry<String, Long>> iterator = mEntries.entrySet().iterator();
        while (mEntries.size() > mMaxEntries && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }

    private void saveLocked() throws IOException {
        try {
            JSONObject root = new JSONObject();
            root.put(KEY_VERSION, 1);
            JSONArray entries = new JSONArray();
            for (Map.Entry<String, Long> entry : mEntries.entrySet()) {
                JSONObject item = new JSONObject();
                item.put(KEY_KEY, entry.getKey());
                item.put(KEY_AT, entry.getValue());
                entries.put(item);
            }
            root.put(KEY_ENTRIES, entries);
            OriginalStore.writeJsonAtomically(mFile, mFileName, root);
        } catch (JSONException invalid) {
            throw new IOException("cannot encode suppression ledger", invalid);
        }
    }

    private void load() {
        if (mFile == null || !mFile.exists()) {
            return;
        }
        try {
            JSONObject root = OriginalStore.readJsonFile(mFile);
            JSONArray entries = root.optJSONArray(KEY_ENTRIES);
            if (entries == null) {
                throw new IOException("invalid suppression ledger entry list");
            }
            for (int i = 0; i < entries.length(); i++) {
                JSONObject item = entries.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                String key = item.optString(KEY_KEY, "").trim();
                if (key.isEmpty()) {
                    continue;
                }
                if (!mEntries.containsKey(key)) {
                    mEntries.put(key, item.optLong(KEY_AT, 0L));
                }
            }
            trimLocked();
        } catch (Throwable t) {
            // 坏账本 = 没有账本：文件不动（留给人工查看），本次运行不抑制、也不覆盖它
            mHealthy = false;
            mEntries.clear();
            Logger.e("event=remote_suppress_load_failed file=" + mFile, t);
        }
    }
}
