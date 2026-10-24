package com.tongluosao.gallerysync.hook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * poster_rewire 的纯逻辑测试：证明"把封面引用搬到视频行、再删掉那条只挂封面的图片行"
 * 之前必经的那道 gate（{@link MetadataRestoreJob.PosterRewire#decision}）、路径安全判定，
 * 以及请求解析的形状。
 *
 * <p>判定用例直接构造 {@link MetadataRestoreJob.PosterRewireFacts}，不启动 Android、不访问
 * 磁盘：返回空串才允许动手，返回的字符串就是结果 JSON 里上报的 reason。通过条件共十二条
 * （图片行五条 → 封面路径/文件三条 → 唯一引用一条 → 视频行三条），用例按规格的处理顺序
 * 逐条打断一条、其余保持成立，因此每个拒绝理由都只由它自己那一条触发，也顺带钉住了
 * "判定顺序不可换"这一点。
 *
 * <p>解析用例走生产代码的 {@code parseRequest(File)}：poster_rewire 必须接受
 * {@code {"mode","row","sha1","length","posterPath","videoRow"}}，缺 length、缺
 * posterPath/videoRow 或出现 baseRow/baseSha1 时整单拒绝；但 posterPath 这个"含 path 的键"
 * 只对 poster_rewire 放行，其它模式带上它仍然 request_path_not_allowed。
 * 单测 JVM 里的 org.json 来自 android.jar 的打桩实现，不可用时这些用例跳过（与既有
 * parseRequest 用例同一约定）。
 */
public class MetadataRestoreJobPosterRewireTest {

    private static final String SHA1 = "0123456789abcdef0123456789abcdef01234567";
    private static final String OTHER_SHA1 = "fedcba9876543210fedcba9876543210fedcba98";
    private static final String TARGET =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    /** 封面的字节数：视频行的 jpg 通常几十 KB，这里取一个具体值即可。 */
    private static final long LENGTH = 41280L;
    private static final String POSTER =
            "/storage/emulated/0/DCIM/Camera/.gallerysync-slim/VID_20240101_120000.jpg";
    private static final String ROW = "15563";
    private static final String VIDEO_ROW = "16001";

    /** 十二条通过条件全成立：搬引用 + 删图片行可以动手。 */
    private static MetadataRestoreJob.PosterRewireFacts validFacts() {
        MetadataRestoreJob.PosterRewireFacts facts = new MetadataRestoreJob.PosterRewireFacts();
        facts.posterRowId = ROW;
        facts.videoRowId = VIDEO_ROW;
        facts.posterPath = POSTER;
        facts.requestedLength = LENGTH;
        facts.posterRowExists = true;
        facts.posterRowLocalFileBlank = true;
        facts.posterRowThumbnailMatches = true;
        facts.posterRowSha1Matches = true;
        facts.posterRowLengthMatches = true;
        facts.posterPathSafe = true;
        facts.posterFilePresent = true;
        facts.posterFileLengthMatches = true;
        facts.posterReferenceQueryFailed = false;
        facts.otherReferencingRows = 0;
        facts.videoRowExists = true;
        facts.videoNameIsMp4 = true;
        facts.videoThumbnailBlank = true;
        return facts;
    }

    private static String decision(MetadataRestoreJob.PosterRewireFacts facts) {
        return MetadataRestoreJob.PosterRewire.decision(facts);
    }

    // ------------------------------------------------------------ 通过条件

    /** 十二条全成立：封面挂在唯一一条只挂封面的图片行上，视频行的封面位空着 → 允许动手。 */
    @Test
    public void validItemPasses() {
        assertEquals("", decision(validFacts()));
    }

    /** 判定是幂等的纯函数：同样的输入两次同结果（它不该消耗或改写任何事实字段）。 */
    @Test
    public void decisionIsSideEffectFree() {
        MetadataRestoreJob.PosterRewireFacts facts = validFacts();
        assertEquals("", decision(facts));
        assertEquals("", decision(facts));
        assertEquals(ROW, facts.posterRowId);
        assertEquals(VIDEO_ROW, facts.videoRowId);
        assertEquals(POSTER, facts.posterPath);
        assertEquals(LENGTH, facts.requestedLength);
    }

    /** 视频行还没被写坏：只挂封面的图片行是"先删它"的对象，不是视频行。 */
    @Test
    public void missingFactsRejected() {
        assertEquals("poster_row_missing", decision(null));
    }

    // ------------------------------------------------------------ 图片行

    /** 图片行已经不在了（或许上一轮已经处理过）→ 不再动手。 */
    @Test
    public void missingPosterRowRejected() {
        MetadataRestoreJob.PosterRewireFacts facts = validFacts();
        facts.posterRowExists = false;
        assertEquals("poster_row_missing", decision(facts));
    }

    /** 图片行还有 localFile：它是一张真照片，不是"只挂封面"的重复行。 */
    @Test
    public void posterRowWithLocalFileRejected() {
        MetadataRestoreJob.PosterRewireFacts facts = validFacts();
        facts.posterRowLocalFileBlank = false;
        assertEquals("poster_row_local_file_not_empty", decision(facts));
    }

    /** 图片行的 thumbnailFile 与请求里的 posterPath 不是逐字相等 → 请求描述的不是这一行。 */
    @Test
    public void posterThumbnailMismatchRejected() {
        MetadataRestoreJob.PosterRewireFacts facts = validFacts();
        facts.posterRowThumbnailMatches = false;
        assertEquals("poster_row_thumbnail_mismatch", decision(facts));
    }

    /**
     * 图片行记录的 sha1 与请求不一致时**不再**拦：那一列是登记封面时的快照，封面重新生成过就
     * 永远对不上，真机上老封面整批都是这样。放行后仍由路径相等 + 文件长度 + 唯一引用托底。
     */
    @Test
    public void staleRowSha1DoesNotBlock() {
        MetadataRestoreJob.PosterRewireFacts facts = validFacts();
        facts.posterRowSha1Matches = false;
        assertEquals("", decision(facts));
    }

    /** 同理：图片行登记的 size 与请求的 length 不一致也不拦，length 只用来量封面文件本身。 */
    @Test
    public void staleRowLengthDoesNotBlock() {
        MetadataRestoreJob.PosterRewireFacts facts = validFacts();
        facts.posterRowLengthMatches = false;
        assertEquals("", decision(facts));
    }

    // ------------------------------------------------------------ 封面路径与文件

    /** 路径不在存储根目录下：这条 gate 在碰磁盘之前就要拦住。 */
    @Test
    public void unsafePosterPathRejected() {
        MetadataRestoreJob.PosterRewireFacts facts = validFacts();
        facts.posterPathSafe = false;
        assertEquals("poster_path_unsafe", decision(facts));
    }

    /**
     * 封面文件已经不在了：仍然放行。这一行本来就没有自己的本地文件，要删的只是这条指向空气的
     * 挂载记录；真机上早年那批封面被清过，把它们当拒绝理由会让这些行永远删不掉。
     */
    @Test
    public void missingPosterFileStillAllowed() {
        MetadataRestoreJob.PosterRewireFacts facts = validFacts();
        facts.posterFilePresent = false;
        facts.posterFileLengthMatches = false;
        assertEquals("", decision(facts));
    }

    /**
     * 文件在但字节数对不上也放行：本模式不删文件、不改文件，只在"这一行没有本地文件且路径逐字
     * 相等"的前提下删掉这条挂载行，字节数多少都不影响结论。
     */
    @Test
    public void posterFileLengthMismatchStillAllowed() {
        MetadataRestoreJob.PosterRewireFacts facts = validFacts();
        facts.posterFileLengthMatches = false;
        assertEquals("", decision(facts));
    }

    /** 别的行（视频行）也指着这张封面时更该放行：删掉多余的挂载行，封面继续被视频行用着。 */
    @Test
    public void sharedPosterStillAllowed() {
        MetadataRestoreJob.PosterRewireFacts facts = validFacts();
        facts.otherReferencingRows = 1;
        assertEquals("", decision(facts));
    }

    /** 引用查询失败同样不拦：本模式从不删文件，查不到引用关系也不会丢字节。 */
    @Test
    public void referenceQueryFailureStillAllowed() {
        MetadataRestoreJob.PosterRewireFacts facts = validFacts();
        facts.posterReferenceQueryFailed = true;
        assertEquals("", decision(facts));
    }

    // ------------------------------------------------------------ 视频行（可选）

    /** 视频行不可见（被 CloudProvider 按空 localFile 过滤）不影响：只是挂不上去而已。 */
    @Test
    public void missingVideoRowStillAllowed() {
        MetadataRestoreJob.PosterRewireFacts facts = validFacts();
        facts.videoRowExists = false;
        assertEquals("", decision(facts));
    }

    /** 目标行不是视频也不拦：挂不上去就跳过这一步，删行照做。 */
    @Test
    public void nonVideoRowStillAllowed() {
        MetadataRestoreJob.PosterRewireFacts facts = validFacts();
        facts.videoNameIsMp4 = false;
        assertEquals("", decision(facts));
    }

    /** 视频行封面位已被占着也不拦：不覆盖别人的引用，只删这一条挂载行。 */
    @Test
    public void occupiedVideoThumbnailStillAllowed() {
        MetadataRestoreJob.PosterRewireFacts facts = validFacts();
        facts.videoThumbnailBlank = false;
        assertEquals("", decision(facts));
    }

    // ------------------------------------------------------------ 判定顺序

    /** 剩下的四条判定按顺序生效：行在 → 无本地文件 → 封面路径相等 → 路径安全。 */
    @Test
    public void decisionFollowsRemainingOrder() {
        MetadataRestoreJob.PosterRewireFacts allBroken = new MetadataRestoreJob.PosterRewireFacts();
        assertEquals("poster_row_missing", decision(allBroken));

        MetadataRestoreJob.PosterRewireFacts facts = validFacts();
        facts.posterRowLocalFileBlank = false;
        facts.posterRowThumbnailMatches = false;
        facts.posterPathSafe = false;
        assertEquals("poster_row_local_file_not_empty", decision(facts));

        facts = validFacts();
        facts.posterRowThumbnailMatches = false;
        facts.posterPathSafe = false;
        assertEquals("poster_row_thumbnail_mismatch", decision(facts));

        facts = validFacts();
        facts.posterPathSafe = false;
        assertEquals("poster_path_unsafe", decision(facts));
    }

    // ------------------------------------------------------------ 路径安全（纯字符串）

    /** 隐藏目录就是这个模式的日常对象：合法。 */
    @Test
    public void safeSlimPathAccepted() {
        assertTrue(MetadataRestoreJob.PosterRewire.isSafePath(POSTER));
        assertTrue(MetadataRestoreJob.PosterRewire.isSafePath(
                "/storage/emulated/0/DCIM/Camera/IMG_20240101_120000.jpg"));
    }

    /** 相对路径不是"根目录下的文件"，一律不安全。 */
    @Test
    public void relativePathRejected() {
        assertFalse(MetadataRestoreJob.PosterRewire.isSafePath(
                "DCIM/Camera/.gallerysync-slim/VID_20240101_120000.jpg"));
        assertFalse(MetadataRestoreJob.PosterRewire.isSafePath("./VID_20240101_120000.jpg"));
    }

    /** 只有根目录、没有文件名：不安全。 */
    @Test
    public void bareStorageRootRejected() {
        assertFalse(MetadataRestoreJob.PosterRewire.isSafePath("/storage/emulated/0"));
        assertFalse(MetadataRestoreJob.PosterRewire.isSafePath("/storage/emulated/0/"));
    }

    /** {@code ..} 会逃出存储根目录，冒充根目录下的文件。 */
    @Test
    public void dotDotEscapeRejected() {
        assertFalse(MetadataRestoreJob.PosterRewire.isSafePath("/storage/emulated/0/../etc/passwd"));
        assertFalse(MetadataRestoreJob.PosterRewire.isSafePath(
                "/storage/emulated/0/DCIM/../../../sdcard/DCIM/x.jpg"));
    }

    /** 单段 "." 与空段（连续斜杠）同样不安全。 */
    @Test
    public void dotAndEmptySegmentRejected() {
        assertFalse(MetadataRestoreJob.PosterRewire.isSafePath("/storage/emulated/0/./x.jpg"));
        assertFalse(MetadataRestoreJob.PosterRewire.isSafePath(
                "/storage/emulated/0/DCIM//x.jpg"));
        assertFalse(MetadataRestoreJob.PosterRewire.isSafePath(
                "/storage/emulated/0/DCIM/x.jpg/"));
    }

    /** 别的根（/sdcard、/data）都不是存储根目录下的绝对路径。 */
    @Test
    public void otherRootRejected() {
        assertFalse(MetadataRestoreJob.PosterRewire.isSafePath("/sdcard/DCIM/Camera/x.jpg"));
        assertFalse(MetadataRestoreJob.PosterRewire.isSafePath("/data/local/tmp/x.jpg"));
        assertFalse(MetadataRestoreJob.PosterRewire.isSafePath(""));
        assertFalse(MetadataRestoreJob.PosterRewire.isSafePath(null));
    }

    /** 超长路径不可能是真实文件，同时把解析字符串的代价钉住。 */
    @Test
    public void overlongPathRejected() {
        StringBuilder huge = new StringBuilder("/storage/emulated/0/");
        for (int i = 0; i < 4096; i++) huge.append('a');
        assertFalse(MetadataRestoreJob.PosterRewire.isSafePath(huge.toString()));
    }

    // ------------------------------------------------------------ 请求解析

    /** poster_rewire 的合法形状：mode/row/sha1/length/posterPath/videoRow，没有 sha256。 */
    @Test
    public void posterRewireParses() throws Exception {
        Assume.assumeTrue("单测 JVM 里的 org.json 不可用（android.jar 打桩）", jsonUsable());
        File request = writeRequest("poster-rewire-valid",
                posterItem(POSTER, VIDEO_ROW, LENGTH, ""));
        assertNull(parseFailure(request));
    }

    /** 两条混排（poster_rewire + purge_items）是合法请求：模式是逐条看的。 */
    @Test
    public void mixedItemsParse() throws Exception {
        Assume.assumeTrue("单测 JVM 里的 org.json 不可用（android.jar 打桩）", jsonUsable());
        String body = posterItem(POSTER, VIDEO_ROW, LENGTH, "") + ","
                + "{\"mode\":\"purge_items\",\"row\":\"17001\",\"sha1\":\"" + SHA1
                + "\",\"length\":1103701}";
        assertNull(parseFailure(writeRequest("poster-rewire-mixed", body)));
    }

    /** 缺 posterPath → 整单拒绝；缺 videoRow 同理，但它报的是元数据形状问题。 */
    @Test
    public void missingRequiredFieldsRejected() throws Exception {
        Assume.assumeTrue("单测 JVM 里的 org.json 不可用（android.jar 打桩）", jsonUsable());
        File noPoster = writeRequest("poster-rewire-no-poster",
                "{\"mode\":\"poster_rewire\",\"row\":\"" + ROW + "\",\"sha1\":\"" + SHA1
                        + "\",\"length\":" + LENGTH + ",\"videoRow\":\"" + VIDEO_ROW + "\"}");
        assertEquals("invalid_item_mode", parseFailure(noPoster));

        File noVideo = writeRequest("poster-rewire-no-video",
                "{\"mode\":\"poster_rewire\",\"row\":\"" + ROW + "\",\"sha1\":\"" + SHA1
                        + "\",\"length\":" + LENGTH + ",\"posterPath\":\"" + POSTER + "\"}");
        assertEquals("invalid_item_metadata", parseFailure(noVideo));
    }

    /** 非字符串/空串的 posterPath、videoRow 都拒绝；相对路径的 posterPath 也拒绝。 */
    @Test
    public void malformedFieldsRejected() throws Exception {
        Assume.assumeTrue("单测 JVM 里的 org.json 不可用（android.jar 打桩）", jsonUsable());
        File numericPoster = writeRequest("poster-rewire-numeric-poster",
                "{\"mode\":\"poster_rewire\",\"row\":\"" + ROW + "\",\"sha1\":\"" + SHA1
                        + "\",\"length\":" + LENGTH + ",\"posterPath\":42,\"videoRow\":\""
                        + VIDEO_ROW + "\"}");
        assertEquals("invalid_item_mode", parseFailure(numericPoster));

        File relative = writeRequest("poster-rewire-relative",
                posterItem("x.jpg", VIDEO_ROW, LENGTH, ""));
        assertEquals("invalid_item_mode", parseFailure(relative));

        File emptyVideo = writeRequest("poster-rewire-empty-video",
                posterItem(POSTER, "", LENGTH, ""));
        assertEquals("invalid_item_metadata", parseFailure(emptyVideo));
    }

    /** 含 .. 的绝对路径解析阶段放行（形状合法），由运行期判定它不安全。 */
    @Test
    public void dotDotPosterPathParsesButIsUnsafe() throws Exception {
        Assume.assumeTrue("单测 JVM 里的 org.json 不可用（android.jar 打桩）", jsonUsable());
        String escaping = "/storage/emulated/0/../sdcard/x.jpg";
        assertNull(parseFailure(writeRequest("poster-rewire-dotdot",
                posterItem(escaping, VIDEO_ROW, LENGTH, ""))));
        assertFalse(MetadataRestoreJob.PosterRewire.isSafePath(escaping));
    }

    /** 出现 baseRow/baseSha1 说明是按别的模式造的请求：整单 invalid_item_mode。 */
    @Test
    public void baseRowRejected() throws Exception {
        Assume.assumeTrue("单测 JVM 里的 org.json 不可用（android.jar 打桩）", jsonUsable());
        File baseRow = writeRequest("poster-rewire-base-row",
                posterItem(POSTER, VIDEO_ROW, LENGTH, ",\"baseRow\":\"15000\""));
        assertEquals("invalid_item_mode", parseFailure(baseRow));

        File baseSha1 = writeRequest("poster-rewire-base-sha1",
                posterItem(POSTER, VIDEO_ROW, LENGTH,
                        ",\"BASESHA1\":\"" + OTHER_SHA1 + "\""));
        assertEquals("invalid_item_mode", parseFailure(baseSha1));
    }

    /** length 必填且必须为正：它既是图片行的 size，也是封面文件的字节数。 */
    @Test
    public void invalidLengthRejected() throws Exception {
        Assume.assumeTrue("单测 JVM 里的 org.json 不可用（android.jar 打桩）", jsonUsable());
        File missing = writeRequest("poster-rewire-no-length",
                posterItem(POSTER, VIDEO_ROW, -1L, ""));
        assertEquals("invalid_item_length", parseFailure(missing));

        File zero = writeRequest("poster-rewire-zero-length",
                posterItem(POSTER, VIDEO_ROW, 0L, ""));
        assertEquals("invalid_item_length", parseFailure(zero));
    }

    /** 含 path 的键只对 poster_rewire 放行；其它模式带上 posterPath 仍然整单拒绝。 */
    @Test
    public void posterPathKeyRejectedForOtherModes() throws Exception {
        Assume.assumeTrue("单测 JVM 里的 org.json 不可用（android.jar 打桩）", jsonUsable());
        File purgeWithPoster = writeRequest("poster-rewire-foreign-mode",
                "{\"mode\":\"purge_items\",\"row\":\"" + ROW + "\",\"sha1\":\"" + SHA1
                        + "\",\"length\":1103701,\"posterPath\":\"" + POSTER + "\"}");
        assertEquals("request_path_not_allowed", parseFailure(purgeWithPoster));

        // 同一条规则不得因为"mode 是 poster_rewire"就给别的路径键开口子。
        File destination = writeRequest("poster-rewire-destination",
                posterItem(POSTER, VIDEO_ROW, LENGTH,
                        ",\"thumbDestination\":\"/storage/emulated/0/x.jpg\""));
        assertEquals("request_path_not_allowed", parseFailure(destination));
    }

    // ------------------------------------------------------------ 工具

    /**
     * @param posterPath 传 null 表示不带 posterPath 键。
     * @param videoRow 传 null 表示不带 videoRow 键（空串表示"给了但是空的"）。
     * @param length 传 -1 表示不带 length 键。
     * @param extra 额外拼进对象里的原始 JSON 片段（用于 baseRow 等键注入）。
     */
    private static String posterItem(String posterPath, String videoRow, long length, String extra) {
        StringBuilder item = new StringBuilder("{\"mode\":\"poster_rewire\",\"row\":\"")
                .append(ROW).append("\",\"sha1\":\"").append(SHA1).append('"');
        if (length >= 0L) item.append(",\"length\":").append(length);
        if (posterPath != null) {
            item.append(",\"posterPath\":\"").append(posterPath).append('"');
        }
        if (videoRow != null) {
            item.append(",\"videoRow\":\"").append(videoRow).append('"');
        }
        item.append(extra).append('}');
        return item.toString();
    }

    private static File writeRequest(String name, String item) throws Exception {
        File request = File.createTempFile("gallerysync-" + name, ".json");
        request.deleteOnExit();
        String body = "{\"jobId\":\"" + name + "\",\"target\":\"" + TARGET
                + "\",\"items\":[" + item + "]}";
        Files.write(request.toPath(), body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    /** 调用生产代码的 private static parseRequest(File)：返回拒绝理由，接受时返回 null。 */
    private static String parseFailure(File request) throws Exception {
        Method parse = MetadataRestoreJob.class.getDeclaredMethod("parseRequest", File.class);
        parse.setAccessible(true);
        try {
            parse.invoke(null, request);
            return null;
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            String message = cause == null ? null : cause.getMessage();
            return message == null ? cause.getClass().getSimpleName() : message;
        }
    }

    private static boolean jsonUsable() {
        try {
            return new org.json.JSONObject("{\"probe\":1}").getInt("probe") == 1;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
