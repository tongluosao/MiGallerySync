package com.tongluosao.gallerysync.core;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * "同一个远端路径同时只允许一条 PUT 在飞"的登记表。
 *
 * <p>为什么单独抽出来：这条约束是服务器侧硬约束（OpenList 用进程内内存锁，读完整个请求体
 * 之后才判锁，第二个重叠的 PUT 会吃 423，而代价是白烧一整份上行）。它出错的方式又特别安静 ——
 * 重复领取只会让两份请求撞锁，双重释放在原来的 {@code HashSet.remove()} 里根本看不出来，
 * 漏放则让那条路径永远没人再传。把"领取/释放"做成返回布尔值的纯函数，就能用单测把
 * 重复领取、双重释放、释放后再领取这几种边界钉住。
 *
 * <p><b>非线程安全</b>：调用方必须持有 {@code UploadQueue.mLock}（现有唯一调用方就是这么做的）。
 * 这样它才能在 {@code mLock} 里与 {@code mInflight} 一起原子地"登记路径 + 占任务键"。
 */
public final class InflightRegistry {

    private final Set<String> mPaths = new HashSet<>();

    /** 只查，不登记。 */
    public boolean contains(String remotePath) {
        return remotePath != null && !remotePath.isEmpty() && mPaths.contains(remotePath);
    }

    /**
     * 登记一条"在飞"的远端路径。
     *
     * @return {@code true} 首次登记；{@code false} 同路径已经在飞（重复领取，调用方必须跳过）
     */
    public boolean claim(String remotePath) {
        if (remotePath == null || remotePath.isEmpty()) {
            // 空路径永远不登记：否则所有算不出路径的任务会互相挡住，队列直接卡死
            return false;
        }
        return mPaths.add(remotePath);
    }

    /**
     * 释放在飞登记。
     *
     * @return {@code true} 确实释放了一条；{@code false} 本来就不在（双重释放 / 没登记过）
     */
    public boolean release(String remotePath) {
        if (remotePath == null || remotePath.isEmpty()) {
            return false;
        }
        return mPaths.remove(remotePath);
    }

    /** 停机时整体清空；返回清空前的条数，便于日志。 */
    public int clear() {
        int size = mPaths.size();
        mPaths.clear();
        return size;
    }

    public int size() {
        return mPaths.size();
    }

    public boolean isEmpty() {
        return mPaths.isEmpty();
    }

    /** 只读快照，给日志/诊断用。 */
    public Set<String> snapshot() {
        return new HashSet<>(mPaths);
    }

    /** 诊断用：仅当集合非空时返回内容。 */
    public Collection<String> pathsOrEmpty() {
        return isEmpty() ? Collections.<String>emptySet() : snapshot();
    }
}
