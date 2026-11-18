package com.tongluosao.gallerysync.core;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 「这个远端对象本身是坏的」台账，避免坏对象被无限重试。
 *
 * <p>要解决的问题（2026-11 真机）：服务端的某些对象<b>长度正确、内容全 0</b>。
 * {@link DownloadedPayloadGate} 能认出这类字节并抛
 * {@link DownloadFailurePolicy.Cause#INVALID_PAYLOAD}（{@code reason=all_zero}），
 * {@link DownloadFailurePolicy} 也给了退避（30min 起、上限 24h），但退避只是"晚一点再试" ——
 * 服务端不会因为等了一小时就把这份字节变好。于是一个永远下不成的对象会周期性地把整份
 * 对象重下一遍、占满下载带宽，还把"这一轮下了几张"的统计搅乱。
 *
 * <p>所以"内容类损坏"必须留下痕迹：发现一次就记进这个账本，计划侧
 * （{@link DownloadQueue#walk} 与 {@link DownloadQueue#planAutoDownloads}）从此跳过这个远端路径。
 * 记的只是<b>内容</b>类损坏（{@link #isContentDamage}）：网络断开、超时、403、5xx 都不记 ——
 * 那些是会自愈的，记进来等于把用户本来能下回来的照片永久挡住。
 *
 * <p>用户手动点"下载原图"是<b>显式意图</b>：那条请求不经过计划侧，因此仍然可以尝试一次；
 * 只有自动排期会被跳过。手动这次要是下成了，{@link #forget} 会把这条记录撤掉
 * （远端修好了，就该恢复正常调度）。
 *
 * <p>与 {@link RemoteSuppress} 同一套写法：独立 JSON 文件、同目录、按
 * {@code OriginalStore.targetKey(配置) + "\n" + 规范化远端路径} 做键（换账号/换根目录是另一个
 * 远端世界，旧账本不该生效）、原子写、有界条数、读写失败绝不抛到主流程。区别只有一处：
 * 这里允许"重复标记"，因为同一份坏字节会被多个不同的下载尝试各撞一次。
 */
public final class DamagedRemotes {

    private static final String DIR = "webdav_sync";
    private static final String FILE_NAME = "damaged-remotes.json";
    private static final String KEY_VERSION = "version";
    private static final String KEY_ENTRIES = "entries";
    private static final String KEY_KEY = "key";
    private static final String KEY_AT = "at";
    private static final String KEY_REASON = "reason";
    private static final String KEY_SIZE = "size";

    /**
     * 台账条目上限：超出后丢最久没被标记的那一条。
     *
     * <p>比 {@link RemoteSuppress#MAX_ENTRIES} 小一个数量级是有意的：那边记的是"用户删过的
     * 垃圾副本"（正常用户一年也删不到十万条），这边记的是"服务端返回坏字节的对象"，
     * 正常情况下是个位数；真出现几千条只能是服务端或中间层整体坏了，那也不该让账本无限长大
     * （每次标记都要全量重写一遍 JSON）。
     */
    static final int MAX_ENTRIES = 20_000;

    private static volatile DamagedRemotes sInstance;

    private final Object mLock = new Object();
    /** null 表示降级实例（账本不可用）：只做内存判断，永不落盘 */
    private final File mFile;
    private final String mFileName;
    private final int mMaxEntries;
    /** 键 = 目标 + "\n" + 规整后的远端路径；值是这条损坏记录的细节。按最近标记时间淘汰。 */
    private final LinkedHashMap<String, Damage> mEntries = new LinkedHashMap<>();
    private boolean mHealthy = true;
    /** 写盘失败过一次：本次进程内不再尝试落盘，只保留内存账本 */
    private boolean mWriteBlocked;

    /**
     * 一条损坏记录：为什么坏、坏的时候远端声明多大、什么时候记的。
     *
     * <p>{@code reason} 只用于日志与排障，判断永远只看"在不在账本里"：服务端下次换个坏法
     * （比如从 {@code all_zero} 变成签名不对）不该让这条记录失效。
     */
    public static final class Damage {
        private final String reason;
        private final long size;
        private final long at;

        Damage(String reason, long size, long at) {
            this.reason = reason == null || reason.trim().isEmpty() ? "unknown" : reason.trim();
            this.size = size;
            this.at = at;
        }

        /** 标记时的原因标签（{@code all_zero} / {@code invalid_payload} 一类稳定标识）。 */
        public String reason() {
            return reason;
        }

        /** 标记时远端声明的字节数；未知为负数。 */
        public long size() {
            return size;
        }

        /** 最近一次标记的时刻（{@code System.currentTimeMillis()}）。 */
        public long markedAt() {
            return at;
        }
    }

    private DamagedRemotes(Context context) {
        this(new File(context.getApplicationContext().getFilesDir(), DIR));
    }

    DamagedRemotes(File directory) {
        this(directory, MAX_ENTRIES);
    }

    /** 上限可调，便于测试用小上限验证淘汰行为。 */
    DamagedRemotes(File directory, int maxEntries) {
        mMaxEntries = Math.max(maxEntries, 1);
        mFileName = FILE_NAME;
        if (directory == null) {
            mFile = null;
            mHealthy = false;
            return;
        }
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            Logger.w("event=damaged_remotes_dir_failed dir=" + directory);
            mFile = null;
            mHealthy = false;
            return;
        }
        mFile = new File(directory, FILE_NAME);
        load();
    }

    /** 降级实例：进程里用，不落盘。 */
    private DamagedRemotes() {
        mFile = null;
        mFileName = FILE_NAME;
        mMaxEntries = MAX_ENTRIES;
        mHealthy = false;
    }

    public static DamagedRemotes get(Context context) {
        DamagedRemotes instance = sInstance;
        if (instance != null) {
            return instance;
        }
        synchronized (DamagedRemotes.class) {
            if (sInstance == null) {
                try {
                    sInstance = new DamagedRemotes(context);
                } catch (Throwable t) {
                    Logger.w("event=damaged_remotes_init_failed reason=" + t);
                    sInstance = new DamagedRemotes();
                }
            }
            return sInstance;
        }
    }

    /**
     * 这个远端对象是不是已经被判定为"内容坏了"。
     *
     * <p>算不出键（没有目标/空路径）、账本读不出来、文件坏了，一律返回 false：
     * 闸门失灵时的行为必须与引入账本之前完全一致（照旧尝试下载）。
     */
    public boolean isDamaged(String target, String remotePath) {
        return find(target, remotePath) != null;
    }

    /** 命中的那条记录；没记过（或算不出键）返回 {@code null}。日志要细节时用它，别查两遍。 */
    public Damage find(String target, String remotePath) {
        String key = keyOf(target, remotePath);
        if (key == null) {
            return null;
        }
        synchronized (mLock) {
            return mEntries.get(key);
        }
    }

    /**
     * 记账：这个远端对象的内容坏了，别再自动排期。
     *
     * <p>幂等：重复标记只刷新时间（并把它挪到"最近标记"那一端，淘汰时先丢最久没被撞到的），
     * 不会多出一条记录 —— 同一个坏对象会被好几次下载尝试各撞一次。
     *
     * <p>账本本身不可用（读坏/写坏）时只记日志、不写：写坏一次可能把已经有效的旧账本一起弄丢，
     * 而"漏记一条"最多回到旧行为（多下一次）。
     */
    public void markDamaged(String target, String remotePath, String reason, long size) {
        String key = keyOf(target, remotePath);
        String path = RemoteSuppress.normalizeRemotePath(remotePath);
        if (key == null) {
            Logger.w("event=damaged_remote_skip reason=invalid_key path=" + path
                    + " size=" + size);
            return;
        }
        String tag = reason == null || reason.trim().isEmpty() ? "unknown" : reason.trim();
        int count;
        boolean duplicate;
        synchronized (mLock) {
            if (!mHealthy) {
                // 账本坏了：不覆盖它，也不把这条记成"任何目标都命中"
                Logger.w("event=damaged_remote_skip reason=ledger_unavailable path=" + path
                        + " size=" + size);
                return;
            }
            // 先摘再放：LinkedHashMap 的插入顺序因此就是"最近被标记/确认"的顺序
            duplicate = mEntries.remove(key) != null;
            mEntries.put(key, new Damage(tag, size, System.currentTimeMillis()));
            trimLocked();
            count = mEntries.size();
            if (mWriteBlocked) {
                // 这次进程里已经降级成"内存账本"：本条照样生效，但别再每条都试一次写盘
                Logger.i("event=damaged_remote_marked path=" + path + " reason=" + tag
                        + " size=" + size + " count=" + count
                        + " target=" + shortTarget(target) + " duplicate=" + duplicate
                        + " persist=memory_only");
                return;
            }
            try {
                saveLocked();
            } catch (Throwable t) {
                // 写不进去（磁盘满、文件坏了）就降级成"只在本次进程里生效"：不回滚这条
                // （下载线程立刻能问到），也不让下载失败路径跟着炸。
                mWriteBlocked = true;
                Logger.e("event=damaged_remote_save_failed path=" + path + " reason=" + tag
                        + " size=" + size, t);
            }
        }
        Logger.i("event=damaged_remote_marked path=" + path + " reason=" + tag
                + " size=" + size + " count=" + count
                + " target=" + shortTarget(target) + " duplicate=" + duplicate);
    }

    /**
     * 撤掉一条记录：用户手动"下载原图"真的下成了，说明远端这份对象已经不坏了。
     *
     * @param reason 只进日志（{@code download_ok} 一类稳定标识）
     * @return 是否真的有这条记录
     */
    public boolean forget(String target, String remotePath, String reason) {
        return remove(target, remotePath, reason == null ? "forgotten" : reason.trim());
    }

    /** 同上，日志里统一记 {@code reason=forgotten}。 */
    public boolean forget(String target, String remotePath) {
        return remove(target, remotePath, "forgotten");
    }

    private boolean remove(String target, String remotePath, String reason) {
        String key = keyOf(target, remotePath);
        String path = RemoteSuppress.normalizeRemotePath(remotePath);
        if (key == null) {
            return false;
        }
        int count;
        long size;
        synchronized (mLock) {
            Damage removed = mEntries.remove(key);
            if (removed == null) {
                return false;
            }
            size = removed.size();
            count = mEntries.size();
            persistQuietlyLocked("removed", path);
        }
        Logger.i("event=damaged_remote_removed path=" + path + " reason=" + reason
                + " size=" + size + " count=" + count + " target=" + shortTarget(target));
        return true;
    }

    /** 记录条数（测试与排障用）。 */
    public int count() {
        synchronized (mLock) {
            return mEntries.size();
        }
    }

    int maxEntries() {
        return mMaxEntries;
    }

    /** 清空整本账（人工排障/测试用）：全部当作"没坏过"，下一轮会重新尝试。 */
    public void clear() {
        int removed;
        synchronized (mLock) {
            removed = mEntries.size();
            mEntries.clear();
            persistQuietlyLocked("clear", "*");
        }
        Logger.i("event=damaged_remote_removed path=* reason=clear size=-1 count=" + removed);
    }

    /**
     * 这次失败算不算"远端内容坏了"，也就是该不该记进账本。
     *
     * <p>只有<b>字节本身</b>不对的两类算：
     * <ul>
     *   <li>{@link DownloadFailurePolicy.Cause#INVALID_PAYLOAD}：下完是全 0 / 已知扩展名但签名
     *       对不上（{@link DownloadedPayloadGate} 的 {@code all_zero}、{@code no_signature}）。</li>
     *   <li>{@link DownloadFailurePolicy.Cause#CONTENT_MISMATCH}：长度对、摘要对不上 ——
     *       中间层把内容改写了，也是同一份坏字节。</li>
     * </ul>
     *
     * <p>其余一律不记：传输/超时/停滞（{@code TRANSPORT}/{@code STALLED}/{@code SERVER}）、
     * 口令与权限（{@code AUTH}，403 就是这一类）、本地磁盘与 IO、长度不符（{@code LENGTH_MISMATCH}，
     * 可能是这次网络截断，下次就完整了）、目的地冲突、注册表拒绝、未知原因 —— 这些都可能是
     * 一次性的，记进来会把本来能下回来的照片永久挡住。
     */
    static boolean isContentDamage(DownloadFailurePolicy.Cause cause) {
        if (cause == null) {
            return false;
        }
        switch (cause) {
            case INVALID_PAYLOAD:
            case CONTENT_MISMATCH:
                return true;
            default:
                return false;
        }
    }

    /**
     * 账本键：目标 + 规整后的远端路径；参数不足时返回 {@code null}（表示"不记也不问"）。
     *
     * <p>目标必须来自 {@link OriginalStore#targetKey(Config)}（主机 + 账号 + 远端根目录）：
     * 换账号/换根目录之后旧账本不该生效，否则新账号里同名的正常对象会被旧账本挡住。
     */
    static String keyOf(String target, String remotePath) {
        if (target == null) {
            return null;
        }
        String normalizedTarget = target.trim();
        if (normalizedTarget.isEmpty()) {
            return null;
        }
        String path = RemoteSuppress.normalizeRemotePath(remotePath);
        if (path.isEmpty()) {
            return null;
        }
        return normalizedTarget + "\n" + path;
    }

    /** 日志里的目标前缀：完整的 targetKey 是 64 位摘要，整行打出来只会淹没真正的字段。 */
    private static String shortTarget(String target) {
        if (target == null) {
            return "";
        }
        String value = target.trim();
        return value.length() <= 8 ? value : value.substring(0, 8);
    }

    /** 内存里已经改过了：落盘失败只降级，不回滚，也不抛给调用方。 */
    private void persistQuietlyLocked(String reason, String path) {
        if (!mHealthy || mWriteBlocked) {
            return;
        }
        try {
            saveLocked();
        } catch (Throwable t) {
            mWriteBlocked = true;
            Logger.e("event=damaged_remote_save_failed path=" + path + " reason=" + reason, t);
        }
    }

    private void trimLocked() {
        Iterator<Map.Entry<String, Damage>> iterator = mEntries.entrySet().iterator();
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
            for (Map.Entry<String, Damage> entry : mEntries.entrySet()) {
                JSONObject item = new JSONObject();
                item.put(KEY_KEY, entry.getKey());
                Damage damage = entry.getValue();
                item.put(KEY_AT, damage.markedAt());
                item.put(KEY_REASON, damage.reason());
                item.put(KEY_SIZE, damage.size());
                entries.put(item);
            }
            root.put(KEY_ENTRIES, entries);
            OriginalStore.writeJsonAtomically(mFile, mFileName, root);
        } catch (JSONException invalid) {
            throw new IOException("cannot encode damaged-remote ledger", invalid);
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
                throw new IOException("invalid damaged-remote ledger entry list");
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
                // 同键保留最早的位置（文件本身是按时间顺序写的），值取最新那份
                if (!mEntries.containsKey(key)) {
                    mEntries.put(key, new Damage(item.optString(KEY_REASON, "unknown"),
                            item.optLong(KEY_SIZE, -1L), item.optLong(KEY_AT, 0L)));
                }
            }
            trimLocked();
        } catch (Throwable t) {
            // 坏账本 = 没有账本：文件不动（留给人工查看），本次运行不判定损坏、也不覆盖它
            mHealthy = false;
            mEntries.clear();
            Logger.e("event=damaged_remote_load_failed file=" + mFile, t);
        }
    }
}
