package com.tongluosao.gallerysync.core;

/**
 * "上传前那次远端探测"该怎么收场：跳过还是照传 —— 纯函数，便于单测。
 *
 * <p>为什么需要一条硬截止（2026-11-17 真机，平板 + OpenList/S3）：探测本身只是"省一次上传"
 * 的优化，但它要回答的是"这个陌生文件在不在这目录里"。大目录下这个问题的代价远超预期 ——
 * 服务器对一个**不存在的对象**做 Depth 0 PROPFIND，PC 冷启动就要 6.8 秒，平板上更慢。
 * 真机日志停在 {@code upload_phase name=probe_start}：90 秒里没有 probe_done、没有 put_start，
 * 远端一个字节都没多。**优化把主链路拖死了**。
 *
 * <p>所以探测有了硬截止（{@link Outcome#TIMEOUT}）：截止一到就按"问不出来"处理，继续走原来的
 * PUT —— 同路径覆盖写是幂等的，多传一次最坏只是白传一份；而干等下去是"一个字节都不发"，
 * 两者代价差着数量级。
 *
 * <p>判定的方向只有一条：**只有"远端确认有、且字节经比对一致"才允许跳过**，其余
 * （不存在 / 超时 / 异常 / 大小对不上）一律照传。
 */
public final class ProbeDecision {

    /** 探测的一次观测结果 */
    public enum Outcome {
        /** 探测正常返回"远端有同名同大小对象" —— 还差字节比对 */
        PRESENT,
        /** 探测正常返回"远端没有这个对象" */
        ABSENT,
        /** 超过硬截止（{@code Future.get} 超时）：不知道有没有 */
        TIMEOUT,
        /** 探测抛异常 / 没提交成功 / 量不出等待时长：不知道有没有 */
        ERROR
    }

    /** 这一项该跳过还是照传 */
    public enum Action {
        /** 远端确认有且字节一致：跳过，一个字节都不发 */
        SKIP,
        /** 其余一律照传（PUT 覆盖写、幂等） */
        PROCEED
    }

    private ProbeDecision() {
    }

    /**
     * 判定：只有 {@link Outcome#PRESENT} 且字节比对通过才 {@link Action#SKIP}。
     *
     * @param outcome   探测的观测结果；null 按 {@link Outcome#ERROR} 处理
     * @param sameBytes 远端那份已回读比对、与本地逐字节一致（只有 PRESENT 时才会去比）
     * @param waitedMs  这一项在探测上等了多久。负数说明调用方连时长都没量出来
     *                  （提交就失败之类），按 {@link Outcome#ERROR} 处理。
     *                  正常的时间不影响跳过与否 —— 那是**字节层面**的事实，与等了多久无关。
     */
    public static Action decide(Outcome outcome, boolean sameBytes, long waitedMs) {
        if (outcome == null || waitedMs < 0L) {
            return Action.PROCEED;
        }
        if (outcome != Outcome.PRESENT) {
            return Action.PROCEED;
        }
        return sameBytes ? Action.SKIP : Action.PROCEED;
    }

    /** 是不是"探测没答上来，所以照传"（这一支要单独留一条 proceed 日志，便于事后算占比） */
    public static boolean isUnknown(Outcome outcome, long waitedMs) {
        return outcome == null || outcome == Outcome.TIMEOUT || outcome == Outcome.ERROR
                || waitedMs < 0L;
    }

    /** 日志用的名字：小写下划线，直接当键值对的值 */
    public static String describe(Outcome outcome) {
        if (outcome == null) {
            return "error";
        }
        switch (outcome) {
            case PRESENT:
                return "present";
            case ABSENT:
                return "absent";
            case TIMEOUT:
                return "timeout";
            default:
                return "error";
        }
    }
}
