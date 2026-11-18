package com.tongluosao.gallerysync.core;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 自动下载这一轮"远端对象到底在不在"的已知答案，用来省掉逐条的 {@code propfindSize} 握手。
 *
 * <p>为什么需要它：自动下载按注册表一条条排 {@code REMOTE_ONLY} 行，每条都要花一次
 * 对象级 {@code PROPFIND Depth 0}（或一次 GET）才能知道远端还在不在；而注册表动辄
 * 上千条、绝大多数落在几十个相册目录里。同一轮里这些目录往往已经被枚举过一次
 * （{@code DownloadQueue.walk} 的 {@code client.list}），那份名单里名字与字节数俱全，
 * 完全可以代替逐条探测 —— 一个目录一次往返，替掉目录里每个文件各一次。
 *
 * <p>三态，绝不可合并：
 * <ul>
 *   <li>{@link Presence#PRESENT}：确定在（目录名单里逐字同名，可能带长度）</li>
 *   <li>{@link Presence#MISSING}：确定不在（同一份**完整**名单里没有这个名字，或 5 分钟内
 *       已经收到过 404）</li>
 *   <li>{@link Presence#UNKNOWN}：不知道 —— 必须照旧探测。所有"问不出来"的情况都归到这里，
 *       因为把"问不出来"当成"没有"会让注册表里好好的原图被记成失败。</li>
 * </ul>
 *
 * <p>两类知识分开存：目录名单按轮次生效（{@link #putListing} / {@link #clearListings}），
 * 逐条探测结果按 {@code (remotePath, size)} 记 5 分钟（{@link #DEFAULT_TTL_MS}）。
 * 为什么键里必须带 size：同名不同长度是真实存在的（相册重命名、同一张图被两个条目引用），
 * 只按路径命中等于把上一版对象的结论套到这一版上，那会直接毁掉一次下载。
 *
 * <p>线程模型：自动下载的多路 worker 与目录枚举线程并行读写，所以内部只用
 * {@link ConcurrentHashMap} 与 {@link AtomicInteger}，不加锁。纯 JVM 可测
 * （无 Android 依赖），时间由调用方传进来，便于钉死"命中/过期"。
 */
public final class DownloadProbeCache {

    /** "探测过没有"的结论 */
    public enum Presence {
        /** 存在 */
        PRESENT,
        /** 确定不存在 */
        MISSING,
        /** 不知道，需要调用方自己探测 */
        UNKNOWN
    }

    /** 探测结果的默认有效期：与"这一轮"同量级，短到不会把远端删除的旧结论一直用下去 */
    public static final long DEFAULT_TTL_MS = 5L * 60L * 1000L;

    /** 条目上限：与下载队列的 done 表同一量级，超了整表清空（结论都可以重算，不值得 LRU） */
    static final int MAX_ENTRIES = 20000;

    private final long mTtlMs;
    private final Map<String, Entry> mEntries = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Long>> mListings = new ConcurrentHashMap<>();
    private final AtomicInteger mHits = new AtomicInteger();
    private final AtomicInteger mMisses = new AtomicInteger();

    public DownloadProbeCache() {
        this(DEFAULT_TTL_MS);
    }

    DownloadProbeCache(long ttlMs) {
        mTtlMs = ttlMs <= 0L ? DEFAULT_TTL_MS : ttlMs;
    }

    /** 一条"某路径 + 某长度"的结论，带到期时刻 */
    static final class Entry {
        final Presence presence;
        final long expiresAt;

        Entry(Presence presence, long expiresAt) {
            this.presence = presence;
            this.expiresAt = expiresAt;
        }
    }

    /** 键 = 路径 + 长度：同名不同长度必须是两条不同的结论 */
    static String key(String remotePath, long size) {
        return (remotePath == null ? "" : normalizePath(remotePath)) + "|" + size;
    }

    /**
     * 统一路径写法：反斜杠换正斜杠、去首尾斜杠。
     *
     * <p>首尾都要去：{@code list()} 给的目录名没有末尾斜杠，而用户/日志里手拼的路径经常带一个，
     * 不去掉就会让"同一个目录"存成两个键，名单永远命中不了。
     */
    static String normalizePath(String remotePath) {
        String path = remotePath == null ? "" : remotePath.trim().replace('\\', '/');
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        while (path.endsWith("/") && !path.isEmpty()) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    /**
     * 记下一份**完整的**目录名单（调用方必须确认过 {@code list()} 没被截断）。
     *
     * <p>同一个目录重复记录时以最后一次为准：远端目录在轮内变化过的话，新的那份更接近真相。
     *
     * @param dir   远端目录路径（{@code MiGallerySync/DCIM/Camera} 这种，无首尾斜杠）
     * @param files 文件名 → 字节数（服务器没给长度时放 {@code -1}）
     */
    public void putListing(String dir, Map<String, Long> files) {
        String key = normalizePath(dir);
        if (key.isEmpty() || files == null) {
            return;
        }
        Map<String, Long> copy = new HashMap<>(files.size());
        for (Map.Entry<String, Long> entry : files.entrySet()) {
            if (entry.getKey() != null && !entry.getKey().isEmpty()) {
                copy.put(entry.getKey(), entry.getValue() == null ? -1L : entry.getValue());
            }
        }
        mListings.put(key, copy);
    }

    /** 一轮计划结束就清掉名单：下一轮远端可能已经变了，目录知识不能跨轮复用 */
    public void clearListings() {
        mListings.clear();
    }

    int listingCount() {
        return mListings.size();
    }

    /** 目录名单里这个文件的大小；不知道或服务器没给长度时返回 {@code -1} */
    public long listedSize(String remotePath) {
        PathSplit split = split(remotePath);
        if (split == null) {
            return -1L;
        }
        Map<String, Long> files = mListings.get(split.dir);
        if (files == null) {
            return -1L;
        }
        Long size = files.get(split.name);
        return size == null ? -1L : size;
    }

    /**
     * 判断本轮是否已经知道这个对象在不在（不吃命中统计，给纯查询用）。
     *
     * <p>{@code expectedSize <= 0} 时只认目录名单：逐条探测的结论必须带长度，
     * 长度未知的结论对不上任何一个对象。
     */
    public Presence lookup(String remotePath, long expectedSize) {
        return lookup(remotePath, expectedSize, System.currentTimeMillis());
    }

    /**
     * 同 {@link #lookup(String, long)}，但顺带记 hit/miss（日志里的
     * {@code event=auto_download_probe_cache hit=… miss=…} 就来自这两个计数）。
     */
    public Presence probe(String remotePath, long expectedSize) {
        return probe(remotePath, expectedSize, System.currentTimeMillis());
    }

    public Presence lookup(String remotePath, long expectedSize, long now) {
        Presence presence = resolve(remotePath, expectedSize, now);
        return presence == null ? Presence.UNKNOWN : presence;
    }

    public Presence probe(String remotePath, long expectedSize, long now) {
        Presence presence = resolve(remotePath, expectedSize, now);
        if (presence == null) {
            mMisses.incrementAndGet();
            return Presence.UNKNOWN;
        }
        mHits.incrementAndGet();
        return presence;
    }

    /** @return 命中的结论；没有可用知识时返回 null（由 probe 翻译成 UNKNOWN + miss） */
    private Presence resolve(String remotePath, long expectedSize, long now) {
        if (remotePath == null || remotePath.isEmpty()) {
            return null;
        }
        // 目录名单优先：它比"几十秒前问过的单条结论"更新，也一定比 TTL 里的旧条目新
        PathSplit split = split(remotePath);
        if (split != null) {
            Map<String, Long> files = mListings.get(split.dir);
            if (files != null) {
                return files.containsKey(split.name) ? Presence.PRESENT : Presence.MISSING;
            }
        }
        if (expectedSize <= 0L) {
            return null;
        }
        Entry entry = mEntries.get(key(remotePath, expectedSize));
        if (entry == null) {
            return null;
        }
        if (now >= entry.expiresAt) {
            mEntries.remove(key(remotePath, expectedSize));
            return null;
        }
        return entry.presence;
    }

    /** 记一次探测结论（以当前时刻为准） */
    public void recordProbe(String remotePath, long size, boolean present) {
        recordProbe(remotePath, size, present, System.currentTimeMillis());
    }

    /**
     * 记一次探测结论。
     *
     * @param size    这条结论对应的远端字节数；{@code <= 0} 时直接丢弃 —— 没有长度就
     *                无法确认下一次遇到的还是同一个对象
     * @param present true = 远端有这个对象；false = 明确不存在（404 一类）
     */
    public void recordProbe(String remotePath, long size, boolean present, long now) {
        if (remotePath == null || remotePath.isEmpty() || size <= 0L) {
            return;
        }
        if (mEntries.size() >= MAX_ENTRIES) {
            // 结论全都可以重算，清空比 LRU 简单得多，也不会漏掉"最新的那条"
            mEntries.clear();
        }
        mEntries.put(key(remotePath, size),
                new Entry(present ? Presence.PRESENT : Presence.MISSING, now + mTtlMs));
    }

    /** 删掉一条结论（成功下载、远端已变之后调用，别让旧结论压住新事实） */
    public void forget(String remotePath, long size) {
        if (remotePath == null || remotePath.isEmpty()) {
            return;
        }
        if (size > 0L) {
            mEntries.remove(key(remotePath, size));
        } else {
            String prefix = normalizePath(remotePath) + "|";
            mEntries.keySet().removeIf(key -> key.startsWith(prefix));
        }
    }

    /** 当前生效的结论条数（单测用，不参与调度） */
    public int size() {
        return mEntries.size();
    }

    public void clear() {
        mEntries.clear();
        mListings.clear();
        resetStats();
    }

    public int hitCount() {
        return mHits.get();
    }

    public int missCount() {
        return mMisses.get();
    }

    /** 取走命中/未命中计数并清零：日志每轮只打一行，不能累加成一整天的数字 */
    public String takeStatsLine() {
        return "hit=" + mHits.getAndSet(0) + " miss=" + mMisses.getAndSet(0);
    }

    public void resetStats() {
        mHits.set(0);
        mMisses.set(0);
    }

    /** 目录 / 文件名拆分；路径里没有分隔符、或末尾就是分隔符时返回 null */
    static PathSplit split(String remotePath) {
        String path = normalizePath(remotePath);
        int slash = path.lastIndexOf('/');
        if (slash <= 0 || slash == path.length() - 1) {
            return null;
        }
        return new PathSplit(path.substring(0, slash), path.substring(slash + 1));
    }

    static final class PathSplit {
        final String dir;
        final String name;

        PathSplit(String dir, String name) {
            this.dir = dir;
            this.name = name;
        }
    }

    /** 只读视图，给日志/调试用 */
    Map<String, Map<String, Long>> listings() {
        return Collections.unmodifiableMap(mListings);
    }
}
