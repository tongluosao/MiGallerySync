package com.tongluosao.gallerysync.core;

/**
 * 账本（{@code state.json} 的 {@code done}）命中之后，这一轮到底要不要排远端自检。
 *
 * <p>背景：done 只是"本模块记过账"，不是"远端一定还在"。曾经出现过远端 404、账本仍是
 * done，于是这一项再也不会重传。现在的做法是入队时对 done 命中项排一次 PROPFIND 自检
 * （见 {@code UploadQueue.checkLedgerEntry}），但自检要花请求、且有每进程额度
 * （{@code LEDGER_CHECK_BUDGET}），所以"排不排"必须是个确定的、可解释的判断，而不是
 * 散在入队循环里的几个短路条件。这里把它抽成纯函数，便于单测钉住方向：
 *
 * <ul>
 *   <li>能验就验（{@link Action#VERIFY}）—— 只有"本进程已经确认/正在确认"、
 *       "目标还没配好"、"算不出远端路径"、"额度用完"这四种情况才跳过；</li>
 *   <li>跳过是**保留账本**的跳过，不动 pending 也不动 done，失败绝不丢数据；</li>
 *   <li>额度用完只是本轮不验，不是永久拉黑：下个进程仍有满额 {@code LEDGER_CHECK_BUDGET}。</li>
 * </ul>
 */
public final class LedgerCheckPolicy {

    /** 入队时对一条 done 命中项的处置。 */
    public enum Action {
        /** 排进自检队列，由 ledgerCheckLoop 问远端。 */
        VERIFY,
        /** 本进程已确认过（key 在 mLedgerChecking 里），不再重复问。 */
        SKIP_TRACKED,
        /** 目标还没配好（config 为空 / off / 缺 baseUrl），算不出远端路径。 */
        SKIP_NOT_READY,
        /** 目标配好了但路径算不出来（理论上不该发生），按跳过兜底。 */
        SKIP_NO_PATH,
        /** 本进程自检额度用完。 */
        SKIP_NO_BUDGET
    }

    private LedgerCheckPolicy() {
    }

    /**
     * @param alreadyTracked 该 key 是否已在 {@code mLedgerChecking}（排队中或本进程已确认）
     * @param budgetLeft     本进程剩余自检次数
     * @param configReady    config 非空、未关闭、baseUrl 非空
     * @param hasRemotePath  已经算出非空的远端路径
     */
    public static Action decide(boolean alreadyTracked, int budgetLeft,
                                boolean configReady, boolean hasRemotePath) {
        // 先看"已经问过/正在问"：这是最便宜的短路，也避免同一 key 在队列里排两份
        if (alreadyTracked) {
            return Action.SKIP_TRACKED;
        }
        if (!configReady) {
            return Action.SKIP_NOT_READY;
        }
        if (!hasRemotePath) {
            return Action.SKIP_NO_PATH;
        }
        if (budgetLeft <= 0) {
            return Action.SKIP_NO_BUDGET;
        }
        return Action.VERIFY;
    }

    /** 日志里用的 reason，保证每个分支可区分（不打印凭据）。 */
    public static String reason(Action action) {
        if (action == null) {
            return "unknown";
        }
        switch (action) {
            case VERIFY:
                return "verify";
            case SKIP_TRACKED:
                return "already_queued_or_verified";
            case SKIP_NOT_READY:
                return "config_not_ready";
            case SKIP_NO_PATH:
                return "remote_path_unknown";
            case SKIP_NO_BUDGET:
                return "budget_exhausted";
            default:
                return "unknown";
        }
    }

    /**
     * 账本自检结论对应的 reason（给 {@code event=upload_ledger_requeue} 用）。
     *
     * <p>只读长度、不做字节回读，所以 {@link EnqueueLedgerDecision.RemoteState#PRESENT_SAME_SIZE}
     * 只说明"远端有同长度对象"，不能证明内容相同 —— reason 的字面意思要按这个理解。
     */
    public static String reason(EnqueueLedgerDecision.RemoteState state) {
        if (state == null) {
            return "unknown_state";
        }
        switch (state) {
            case MISSING:
                return "remote_missing";
            case PRESENT_DIFFERENT_SIZE:
                return "size_mismatch";
            case PRESENT_SAME_SIZE:
                return "present_same_size";
            case UNKNOWN:
                return "unverifiable";
            default:
                return "unknown_state";
        }
    }
}
