package com.tongluosao.gallerysync.core;

/**
 * "接管"判定：相册里已经有的云端行，该不该登记进注册表 —— 纯函数，便于单测。
 *
 * <p>为什么需要单独一套判定：老照片在模块出现之前就上传完了，注册表里没有它们。想给它们
 * 补登记，最直接的做法是"按相册行的 {@code size} 和远端 PROPFIND 报的 Content-Length 对一下"，
 * 对得上就认为"远端那份就是这张照片的原图"。这条路**不下载任何字节**，所以必须把"能证明什么"
 * 划清楚，宁可少登记也不能登错：
 * <ul>
 *   <li>没有 {@code sha1} 的行连"这是哪张照片"都说不准，直接跳过；</li>
 *   <li>目录列表里没有这个名字（或列表本身没成功）就是"远端没有"，跳过；</li>
 *   <li>远端大小与相册行登记的 {@code size} 不一致：远端那份可能是别的东西，跳过；</li>
 *   <li>大小一致且本地文件也在、字节数也一致：本地这份就是远端那份，可以按
 *       {@link OriginalStore.LocalState#ORIGINAL} 登记（与"上传成功后登记"同义）；</li>
 *   <li>大小一致但本地没有（或本地那份大小不同）：只能证明远端有这份文件，
 *       本地是什么还不知道 —— 按 {@link OriginalStore.LocalState#REMOTE_ONLY} 登记，
 *       上传/优化侧据此保守处理，直到有人真的把字节核对一遍。</li>
 * </ul>
 *
 * <p>{@link Decision#SKIP_TOO_LARGE_ROW} 不在 {@link #decide} 的规则里：它留给调用方在
 * "读这一行本身就不安全"（例如 size 大得不合理）时使用，规则与日志名字保持一致。
 */
public final class TakeoverPlanner {

    /** 这一行该怎么处理 */
    public enum Decision {
        /** 远端有、大小一致、本地文件也是同样字节数：本地即原图 */
        TAKE_AS_ORIGINAL("take_as_original"),
        /** 远端有、大小一致，但本地没有可用字节：只知道远端有这份原图 */
        TAKE_AS_REMOTE_ONLY("take_as_remote_only"),
        /** 远端大小与相册行登记的 size 对不上：不确定远端那份是不是这张照片 */
        SKIP_SIZE_MISMATCH("skip_size_mismatch"),
        /** 目录列表里没有这个名字（或列表没成功）：远端证据不足 */
        SKIP_REMOTE_MISSING("skip_remote_missing"),
        /** 这一行没有 40 位 sha1：连身份都不完整 */
        SKIP_NO_SHA1("skip_no_sha1"),
        /** 调用方判定这一行的尺寸不可信（不由 {@link #decide} 产生） */
        SKIP_TOO_LARGE_ROW("skip_too_large_row");

        private final String mWire;

        Decision(String wire) {
            mWire = wire;
        }

        /** 日志与结果 reasons 里的写法：小写下划线，跨版本稳定 */
        public String wire() {
            return mWire;
        }
    }

    private TakeoverPlanner() {
    }

    /**
     * 判定一行的去向。
     *
     * <p>顺序即优先级：先证明"身份完整"，再证明"远端有且大小一致"，最后才看本地。
     * 任何一步缺证据都停在那一步的跳过分支，绝不往下猜。
     *
     * @param remoteListed 目录列表成功、且列表里有这个文件名
     * @param remoteSize   远端报的字节数；{@code remoteListed} 为 false 时无意义
     * @param rowSize      相册行登记的 {@code size}（即当年上传的字节数）
     * @param localExists  相册行给的 {@code localFile} 现在还是普通文件
     * @param localLength  该文件当前的字节数；不存在时无意义
     * @param hasSha1      相册行有 40 位十六进制 {@code sha1}
     */
    public static Decision decide(boolean remoteListed, long remoteSize, long rowSize,
            boolean localExists, long localLength, boolean hasSha1) {
        if (!hasSha1) {
            return Decision.SKIP_NO_SHA1;
        }
        if (!remoteListed) {
            return Decision.SKIP_REMOTE_MISSING;
        }
        if (remoteSize != rowSize) {
            return Decision.SKIP_SIZE_MISMATCH;
        }
        if (localExists && localLength == remoteSize) {
            return Decision.TAKE_AS_ORIGINAL;
        }
        return Decision.TAKE_AS_REMOTE_ONLY;
    }

    /** 是不是"要登记"的结论（两种 TAKE_*）；调用方据此决定写不写注册表 */
    public static boolean isTake(Decision decision) {
        return decision == Decision.TAKE_AS_ORIGINAL || decision == Decision.TAKE_AS_REMOTE_ONLY;
    }

    /** 日志用的名字：小写下划线，直接当键值对的值 */
    public static String describe(Decision decision) {
        return decision == null ? "unknown" : decision.mWire;
    }
}
