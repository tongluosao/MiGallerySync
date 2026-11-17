package com.tongluosao.gallerysync.core;

/**
 * "入队时去重键命中 done 账本"之后该 skip 还是重新入队 —— 纯函数，便于单测。
 *
 * <p>为什么要单独抽出来：{@link UploadQueue#enqueue} 里的 done 表（{@code mDone}）只记着
 * "这个 key 曾经被记成传完了"，它本身**不是远端存在性的证据** —— 远端对象可能被用户清掉、
 * 被服务端重建、或者账本自己写脏。原来的实现直接按 key 命中就 {@code skipped++}，
 * 于是"远端 404、账本说完成"的那一项永远不会再被发送（静默丢数据）。
 *
 * <p>现在的规则只有一条：**只有远端确认存在、且大小与本地一致，才允许跳过**。
 * 判错的方向必须选清楚：多传一次是覆盖写（幂等、不产生第二个对象），少传一次就是丢文件。
 */
public final class EnqueueLedgerDecision {

    /** 账本自检对远端的一次观测结果 */
    public enum RemoteState {
        /** 远端有同名对象，且大小与本地一致：这条账可信，跳过 */
        PRESENT_SAME_SIZE,
        /** 远端有同名对象，但大小不同：远端那份不是这一份，必须覆盖写 */
        PRESENT_DIFFERENT_SIZE,
        /** 远端明确没有这个对象：账本脏了 */
        MISSING,
        /** 问不出来（不支持 PROPFIND / 超时 / 鉴权失败 / 大小未知）：不能当成"有" */
        UNKNOWN
    }

    /** 这一项该跳过还是重新入队 */
    public enum Action {
        /** 远端确认有：按老行为跳过，一个字节都不发 */
        SKIP,
        /** 远端没有或问不出来：清掉脏账，重新入队（PUT 覆盖写、幂等） */
        REQUEUE
    }

    private EnqueueLedgerDecision() {
    }

    /**
     * 把远端查询的返回值映射成观测状态。
     *
     * <p>取值口径与 {@code WebDavClient.propfindSize} 一致：
     * 正数 = 对象字节数，{@code 0} = 存在但大小问不出来，{@code -1} = 明确不存在，
     * 其它负数（如 {@code -2}）= 问不出来。
     *
     * <p>{@code 0} 与"问不出来"都必须算 {@link RemoteState#UNKNOWN}，不能算"有"：
     * 一旦把它们当成"远端已有"，账本里那条脏记录就会被永久确认成真的。
     */
    public static RemoteState fromPropfind(long reportedSize, long localSize) {
        if (localSize <= 0L) {
            // 本地大小为 0 的项本来就不该入队；真走到了也只能判"不知道"，不能跳过
            return RemoteState.UNKNOWN;
        }
        if (reportedSize == -1L) {
            return RemoteState.MISSING;
        }
        if (reportedSize <= 0L) {
            // 0 = 服务器说"有"但没给长度；负数是"问不出来"
            return RemoteState.UNKNOWN;
        }
        return reportedSize == localSize
                ? RemoteState.PRESENT_SAME_SIZE
                : RemoteState.PRESENT_DIFFERENT_SIZE;
    }

    /**
     * 判定规则：只有远端确认"同名同大小"才跳过，其余一律重新入队。
     *
     * <p>大小不同也要重传：远端那份可能是同名但内容不同（相册重命名、别的客户端写过），
     * "大小对不上"恰恰说明它不是这一份。
     */
    public static Action decide(RemoteState state) {
        return state == RemoteState.PRESENT_SAME_SIZE ? Action.SKIP : Action.REQUEUE;
    }

    /** 日志用的状态名：小写下划线，可以直接当键值对的值写进结构化日志 */
    public static String describe(RemoteState state) {
        if (state == null) {
            return "unknown";
        }
        switch (state) {
            case PRESENT_SAME_SIZE:
                return "remote_present";
            case PRESENT_DIFFERENT_SIZE:
                return "remote_size_mismatch";
            case MISSING:
                return "remote_missing";
            default:
                return "remote_unknown";
        }
    }
}
