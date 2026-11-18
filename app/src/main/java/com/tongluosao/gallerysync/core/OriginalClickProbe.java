package com.tongluosao.gallerysync.core;

/**
 * “下载原图”点击时那次远端探测该怎么收场：接管去 WebDAV 还原，还是回退小米云。
 *
 * <p>为什么要有这一步：注册表里登记过（{@code remotePath} + {@code originalLength}）只说明
 * “上传时它在”，不说明“此刻它还在”。远端被删、被改名、被别的设备覆盖成另一个大小，都发生在
 * 登记之后。以前点击直接接管还原，用户只看到“还原失败”，拿不到小米云那份 —— 明明云上有。
 * 所以先问一句“远端这个对象还在不在、大小对不对”，问到了才接管，问不到就放行原生下载。
 *
 * <p>判定的方向只有一条：**只有远端确认存在且大小与登记一致才接管**。不存在 / 问不出来 /
 * 大小不符 / 超时 / 异常，一律回退小米云 —— 回退最坏是多下一份云原图，而误接管是“用户点了
 * 什么都没拿到”。
 *
 * <p>纯函数，便于单测；发请求的那一半在 {@code OriginalRestoreHook} 里（有界、非主线程）。
 */
public final class OriginalClickProbe {

    /** {@code WebDavClient.propfindSize} 的三态约定：明确不存在。 */
    private static final long ABSENT = -1L;

    /** 一次探测看到了什么 */
    public enum Outcome {
        /** 远端有，且大小与登记的原图长度一致（登记长度未知时只要有大小就算） */
        PRESENT,
        /** 远端有，但大小与登记不一致（远端是另一份东西，不能拿它覆盖本地） */
        SIZE_MISMATCH,
        /** 远端明确没有这个对象（被删/改名/目录没了） */
        MISSING,
        /** 问不出来：服务器不支持、响应不可信、存在但没给大小，或执行器忙不过来 */
        UNKNOWN,
        /** 超过硬截止（{@code Future.get} 超时）：不知道有没有 */
        TIMEOUT,
        /** 探测抛异常（鉴权失败、IO、反射……） */
        ERROR
    }

    /** 这次点击该由谁接 */
    public enum Source {
        /** 远端确认在：走 WebDAV 还原，不放行原生下载 */
        WEBDAV,
        /** 其余一律放行原生下载，让小米云接手 */
        XIAOMI
    }

    private OriginalClickProbe() {
    }

    /**
     * 把一次 {@code propfindSize} 的观测翻成结论。
     *
     * @param probeSize      {@code propfindSize} 的返回值：正整数=对象大小；0=存在但大小未知；
     *                       -1=明确不存在；-2=问不出来
     * @param expectedLength 注册表登记的原图长度；{@code <= 0} 表示登记里没有长度（老记录/脏记录），
     *                       这时只能确认“远端有东西”，没法确认“是不是同一份”
     */
    public static Outcome classify(long probeSize, long expectedLength) {
        if (probeSize > 0L) {
            if (expectedLength <= 0L) {
                // 登记长度未知：远端确实有对象，就按“是这一份”处理 —— 这是老记录能还原的唯一出路
                return Outcome.PRESENT;
            }
            return probeSize == expectedLength ? Outcome.PRESENT : Outcome.SIZE_MISMATCH;
        }
        if (probeSize == ABSENT) {
            return Outcome.MISSING;
        }
        // 0（存在但没给长度）、-2（问不出来）、其它负数：都判不了，按问不出来处理
        return Outcome.UNKNOWN;
    }

    /** 探测结果 → 谁来接这次点击 */
    public static Source source(Outcome outcome) {
        return outcome == Outcome.PRESENT ? Source.WEBDAV : Source.XIAOMI;
    }

    /** 是否接管（等价于 {@code source(outcome) == WEBDAV}，给分支读起来更直白） */
    public static boolean usesWebDav(Outcome outcome) {
        return outcome == Outcome.PRESENT;
    }

    /** 日志里 {@code probe=} 的取值：小写下划线，直接当键值对的值 */
    public static String logToken(Outcome outcome) {
        if (outcome == null) {
            return "error";
        }
        switch (outcome) {
            case PRESENT:
                return "present";
            case SIZE_MISMATCH:
                return "size_mismatch";
            case MISSING:
                return "missing";
            case TIMEOUT:
                return "timeout";
            case UNKNOWN:
                return "unknown";
            default:
                return "error";
        }
    }

}
