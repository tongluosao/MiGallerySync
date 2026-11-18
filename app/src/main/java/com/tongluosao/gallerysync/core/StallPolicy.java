package com.tongluosao.gallerysync.core;

/**
 * 下载停滞的阈值与处置（纯函数，可在 JVM 单测里逐条钉住）。
 *
 * <p>要解决的问题（2026-11 真机，4.5 分钟窗口内 22 条 {@code stalled}）：停滞看门狗原来
 * 只有一条 60 秒的固定阈值，而且<b>从传输开始</b>就算"最后收到字节的时刻" —— 于是
 * "服务端一个字节都不吐"和"600MB 视频在慢链路上间歇性停顿"被同一个 60 秒判死。
 *
 * <p>现在分成两个阶段，各有自己的截止时间：
 *
 * <ul>
 *   <li>{@link Phase#FIRST_BYTE}：连接/请求已经发出，还<b>一个字节都没收到</b>。这时
 *       "慢"没有意义 —— 服务端要是有这个对象，首包早就该到了。默认
 *       {@link #FIRST_BYTE_TIMEOUT_MS}（20s），大对象放宽到 {@link #FIRST_BYTE_TIMEOUT_MAX_MS}。</li>
 *   <li>{@link Phase#IDLE}：已经收到过字节，随后又没动静。这条链路上"大文件慢速但活着"是
 *       常态（真机 1.3GB 视频），阈值必须给足：默认 {@link #IDLE_TIMEOUT_MS}（90s），
 *       大对象放宽到 {@link #IDLE_TIMEOUT_MAX_MS}。</li>
 * </ul>
 *
 * <p>第二个判据是<b>处置</b>：停滞发生在 302 签名直链上时，最省事的修法不是"重下整份"，
 * 而是"作废这条直链、退回原始 WebDAV 路径再试一次"（{@link Action#RETRY_WITHOUT_DIRECT}）——
 * 直链只是省一次握手，坏掉不代表远端没有这个对象。只有走标准路径也停滞，才关连接、
 * 按失败记账（{@link Action#ABORT}）。
 *
 * <p>全部纯计算：时间、字节都由调用方传入，不读时钟、不碰文件。
 */
public final class StallPolicy {

    /** 首字节阶段的基础截止时间：20 秒没有任何响应字节就当这条连接僵死。 */
    public static final long FIRST_BYTE_TIMEOUT_MS = 20_000L;
    /** 大对象的首字节截止时间上限：冷启动/慢链路起手可以更慢，但不会无限等。 */
    public static final long FIRST_BYTE_TIMEOUT_MAX_MS = 45_000L;
    /** 已经收到过字节之后的空闲阈值：90 秒没有新字节才算停滞（大文件慢速但不能误杀）。 */
    public static final long IDLE_TIMEOUT_MS = 90_000L;
    /** 大对象的空闲阈值上限。 */
    public static final long IDLE_TIMEOUT_MAX_MS = 150_000L;
    /**
     * 分界：不超过这个长度一律用基础阈值。
     *
     * <p>10MB 以下的对象在真机上正常秒级完成，"首字节 20s / 空闲 90s"已经非常宽松，
     * 再放大只会让坏连接占着通道更久。
     */
    static final long SMALL_BYTES = 10L * 1024L * 1024L;
    /** 到 500MB 宽容度涨到上限；再往上不再增加（否则一个坏连接能占几分钟）。 */
    static final long LARGE_BYTES = 500L * 1024L * 1024L;

    private StallPolicy() {
    }

    /** 传输处在哪个阶段。日志里直接写成 {@code phase=first_byte|idle}。 */
    public enum Phase {
        FIRST_BYTE("first_byte"),
        IDLE("idle");

        private final String wire;

        Phase(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }
    }

    /** 看门狗这一刻该做什么。 */
    public enum Action {
        /** 还没到截止时间：继续等（大文件慢速传输走这里）。 */
        WAIT,
        /** 到点了、而且这条传输没走签名直链：关连接，按停滞失败记账。 */
        ABORT,
        /** 到点了、但走的是签名直链：先作废直链，退回原始 WebDAV 路径再试一次。 */
        RETRY_WITHOUT_DIRECT
    }

    /** 首字节阶段该等多久（按对象总长自适应）。 */
    public static long firstByteTimeoutMs(long totalBytes) {
        return scaledTimeout(totalBytes, FIRST_BYTE_TIMEOUT_MS, FIRST_BYTE_TIMEOUT_MAX_MS);
    }

    /** 已收到字节之后的空闲阈值（按对象总长自适应）。 */
    public static long idleTimeoutMs(long totalBytes) {
        return scaledTimeout(totalBytes, IDLE_TIMEOUT_MS, IDLE_TIMEOUT_MAX_MS);
    }

    /** 这个阶段适用的截止时间。 */
    public static long timeoutMs(Phase phase, long totalBytes) {
        return phase == Phase.IDLE ? idleTimeoutMs(totalBytes) : firstByteTimeoutMs(totalBytes);
    }

    /** 收到过字节就是空闲阶段；否则还在等首字节。 */
    public static Phase phaseOf(boolean hasBytes) {
        return hasBytes ? Phase.IDLE : Phase.FIRST_BYTE;
    }

    /** 距离"开始等"已经过去多少毫秒就该动它。 */
    public static boolean timedOut(boolean hasBytes, long elapsedMs, long totalBytes) {
        if (elapsedMs < 0L) {
            // 时钟回退（重启后 elapsedRealtime 归零）：当作刚开始，不误杀
            return false;
        }
        return elapsedMs >= timeoutMs(phaseOf(hasBytes), totalBytes);
    }

    /**
     * 停滞处置。
     *
     * @param hasBytes       这一轮是否已经收到过字节（决定阶段）
     * @param elapsedMs      首字节阶段 = 距传输开始；空闲阶段 = 距最后一次收到字节
     * @param totalBytes     对象总长，未知传 0 或负数
     * @param usedDirectUrl  这次传输是否正在用缓存的 302 签名直链
     */
    public static Action stallAction(boolean hasBytes, long elapsedMs, long totalBytes,
            boolean usedDirectUrl) {
        if (!timedOut(hasBytes, elapsedMs, totalBytes)) {
            return Action.WAIT;
        }
        return usedDirectUrl ? Action.RETRY_WITHOUT_DIRECT : Action.ABORT;
    }

    /**
     * 长度自适应：{@code <=SMALL_BYTES} 取基础值，{@code >=LARGE_BYTES} 取上限，
     * 中间线性过渡。负数（未知长度）按基础值处理 —— 未知长度不能成为"多等"的理由。
     */
    static long scaledTimeout(long totalBytes, long base, long max) {
        if (base <= 0L) {
            return 0L;
        }
        long cap = Math.max(base, max);
        if (totalBytes <= SMALL_BYTES) {
            return base;
        }
        if (totalBytes >= LARGE_BYTES) {
            return cap;
        }
        double fraction = (double) (totalBytes - SMALL_BYTES) / (double) (LARGE_BYTES - SMALL_BYTES);
        long extra = (long) ((cap - base) * fraction);
        return Math.max(base, Math.min(cap, base + extra));
    }
}
