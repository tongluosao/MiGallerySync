package com.tongluosao.gallerysync.hook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * reconcile_inplace（原地迁移后收拾“扫描器为同一路径又插了一行”的重复行）的判定测试。
 *
 * <p>判定层 {@link MetadataRestoreJob.Reconcile#decision} 是不碰 Android 的纯函数，用例直接
 * 构造 {@link MetadataRestoreJob.ReconcileFacts}：返回空串才允许删幽灵行，返回的字符串就是
 * 结果里上报的 reason。删除动作本身在设备上走 {@code CloudUtils.deleteById}，单测 JVM 里没有
 * 那段实现，因此这里钉的是“删之前必须成立的证据链”。
 *
 * <p>解析用例走生产代码的 {@code parseRequest(File)}（与既有 hook 测试同一约定）：单测 JVM 里的
 * org.json 来自 android.jar 的打桩实现，不可用时这些用例整体跳过；纯逻辑用例永远会跑。
 */
public class MetadataRestoreJobReconcileTest {

    private static final String TARGET =
            "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
    /** 被扫描器插进来的幽灵行（_id 更大，记着旧原图大小）。 */
    private static final String GHOST_ROW = "4402";
    /** 留下并接住这份文件的那一行（size 就是预览大小）。 */
    private static final String KEEPER_ROW = "4403";
    private static final String KEEPER_SHA1 = "0123456789abcdef0123456789abcdef01234567";
    private static final String PHOTO = "/storage/emulated/0/DCIM/Camera/IMG_20240101_120000.heic";
    /** 预览（也是磁盘上那份文件的真实大小）。 */
    private static final long PREVIEW_LENGTH = 260_000L;
    /** 幽灵行登记的旧原图大小。 */
    private static final long ORIGINAL_LENGTH = 4_000_000L;

    // ------------------------------------------------------------------
    // 判定：b–e 四条证据链，每条对应一个稳定的 reason
    // ------------------------------------------------------------------

    /** b. 行取不到（已被删/被改 _id）：什么都不做，必须先报 row_missing。 */
    @Test
    public void missingRowRejectedFirst() {
        assertEquals("reconcile_row_missing", decision(null));
        MetadataRestoreJob.ReconcileFacts facts = ghostFacts();
        facts.rowExists = false;
        assertEquals("reconcile_row_missing", decision(facts));
    }

    /** b 的优先级最高：其它条件全都不成立时，仍然先报“行不存在”。 */
    @Test
    public void missingRowWinsOverEveryOtherReason() {
        MetadataRestoreJob.ReconcileFacts facts = ghostFacts();
        facts.rowExists = false;
        facts.localFileMatches = false;
        facts.thumbnailEmpty = false;
        facts.serverIdEmpty = false;
        facts.rowSize = PREVIEW_LENGTH;
        facts.keeperCount = 0;
        assertEquals("reconcile_row_missing", decision(facts));
    }

    /** c. localFile 与请求的 path 不逐字相符：这一行不归本项管。 */
    @Test
    public void localFileMismatchIsNotGhost() {
        MetadataRestoreJob.ReconcileFacts facts = ghostFacts();
        facts.localFileMatches = false;
        assertEquals("reconcile_row_not_ghost", decision(facts));
    }

    /** c. thumbnailFile 非空：这行还有侧车预览，不是迁移后留下的幽灵行。 */
    @Test
    public void rowWithThumbnailIsNotGhost() {
        MetadataRestoreJob.ReconcileFacts facts = ghostFacts();
        facts.thumbnailEmpty = false;
        assertEquals("reconcile_row_not_ghost", decision(facts));
    }

    /** c. serverId 非空：这是小米云的行，删它会动云端 —— 绝不删。 */
    @Test
    public void rowWithServerIdIsNotGhost() {
        MetadataRestoreJob.ReconcileFacts facts = ghostFacts();
        facts.serverIdEmpty = false;
        assertEquals("reconcile_row_not_ghost", decision(facts));
    }

    /** not_ghost 先于 size 判定：形状不对时连磁盘长度都不该成为放行理由。 */
    @Test
    public void notGhostWinsOverSizeMatch() {
        MetadataRestoreJob.ReconcileFacts facts = ghostFacts();
        facts.serverIdEmpty = false;
        facts.fileLength = ORIGINAL_LENGTH;
        assertEquals("reconcile_row_not_ghost", decision(facts));
    }

    /** d. 行 size 等于文件真实大小：这行记的就是现在这份文件，不能删。 */
    @Test
    public void sizeMatchRejected() {
        MetadataRestoreJob.ReconcileFacts facts = ghostFacts();
        facts.rowSize = PREVIEW_LENGTH;
        assertEquals("reconcile_size_matches", decision(facts));
    }

    /** size 相符先于 keeper 判定：keeper 缺失也不能给“可删”的错觉。 */
    @Test
    public void sizeMatchWinsOverMissingKeeper() {
        MetadataRestoreJob.ReconcileFacts facts = ghostFacts();
        facts.rowSize = PREVIEW_LENGTH;
        facts.keeperCount = 0;
        assertEquals("reconcile_size_matches", decision(facts));
    }

    /** e. 同路径没有其它行：删了幽灵行，这份文件就没有任何登记了。 */
    @Test
    public void keeperMissingWhenNoOtherRow() {
        MetadataRestoreJob.ReconcileFacts facts = ghostFacts();
        facts.keeperCount = 0;
        assertEquals("reconcile_keeper_missing", decision(facts));
    }

    /** e. 同路径有多行：分不清哪一行该接住这份文件，宁可不删。 */
    @Test
    public void keeperAmbiguousWhenSeveralRows() {
        MetadataRestoreJob.ReconcileFacts facts = ghostFacts();
        facts.keeperCount = 2;
        assertEquals("reconcile_keeper_ambiguous", decision(facts));
    }

    /** e. 唯一的同路径行 size 与磁盘不符：它不是那张照片的 keeper。 */
    @Test
    public void keeperWithWrongSizeRejected() {
        MetadataRestoreJob.ReconcileFacts facts = ghostFacts();
        facts.keeperSize = ORIGINAL_LENGTH;
        facts.keeperSizeMatches = false;
        assertEquals("reconcile_keeper_missing", decision(facts));
    }

    /** e. keeper 有 serverId：那是云端行，不能把它当成“本地这份文件的主人”。 */
    @Test
    public void keeperWithServerIdRejected() {
        MetadataRestoreJob.ReconcileFacts facts = ghostFacts();
        facts.keeperServerIdEmpty = false;
        assertEquals("reconcile_keeper_missing", decision(facts));
    }

    /** e. keeper 还挂着缩略图：形状不对，说明它也不是迁移后的那一行。 */
    @Test
    public void keeperWithThumbnailRejected() {
        MetadataRestoreJob.ReconcileFacts facts = ghostFacts();
        facts.keeperThumbnailEmpty = false;
        assertEquals("reconcile_keeper_missing", decision(facts));
    }

    /** 成功路径：幽灵形状 + 旧原图 size + 唯一的 keeper 行 → 空串（允许删并改绑）。 */
    @Test
    public void wellFormedGhostIsAccepted() {
        assertEquals("", decision(ghostFacts()));
    }

    // ------------------------------------------------------------------
    // 路径闸门（复用 safeStoragePath 的那一层）
    // ------------------------------------------------------------------

    /** 路径闸门：null/相对路径/根目录之外一律回 null；根内但不是普通文件也回 null。 */
    @Test
    public void reconcileTargetOnlyAcceptsRegularFilesUnderStorageRoot() {
        assertNull(MetadataRestoreJob.reconcileTarget(null));
        assertNull(MetadataRestoreJob.reconcileTarget(""));
        // 相对路径会按进程工作目录解析，规范化后不在存储根内。
        assertNull(MetadataRestoreJob.reconcileTarget("DCIM/Camera/IMG_1.heic"));
        // 存储根之外（开发机的 /etc 与 /tmp 都不在 /storage/emulated/0 下）。
        assertNull(MetadataRestoreJob.reconcileTarget("/etc/passwd"));
        assertNull(MetadataRestoreJob.reconcileTarget("/tmp/gallerysync-reconcile.heic"));
        // 根内但文件不存在：不是普通文件，必须拒绝（reconcile_path_unsafe）。
        assertNull(MetadataRestoreJob.reconcileTarget(
                "/storage/emulated/0/DCIM/Camera/gallerysync-no-such-file-4402.heic"));
    }

    // ------------------------------------------------------------------
    // 请求解析（org.json 不可用时跳过，与既有 hook 测试同一约定）
    // ------------------------------------------------------------------

    /** reconcile_inplace 的条目形状：row + path + length，sha1/sha256 都可以不给。 */
    @Test
    public void reconcileInplaceRequestParses() throws Exception {
        Assume.assumeTrue("单测 JVM 里的 org.json 不可用（android.jar 打桩）", jsonUsable());
        File request = writeRequest("reconcile-ok", reconcileItem(
                "\"row\":\"" + GHOST_ROW + "\",\"path\":\"" + PHOTO + "\",\"length\":"
                        + ORIGINAL_LENGTH + ",\"previewLength\":" + PREVIEW_LENGTH));
        assertNull(parseFailure(request));
    }

    /** path 缺失：整单 invalid_item_mode（没有路径就无从判断要收拾哪张照片）。 */
    @Test
    public void missingPathRejected() throws Exception {
        Assume.assumeTrue("单测 JVM 里的 org.json 不可用（android.jar 打桩）", jsonUsable());
        File request = writeRequest("reconcile-no-path", reconcileItem(
                "\"row\":\"" + GHOST_ROW + "\",\"length\":" + ORIGINAL_LENGTH));
        assertEquals("invalid_item_mode", parseFailure(request));
    }

    /** 相对路径 shape 不合法：请求里只接受绝对路径。 */
    @Test
    public void relativePathRejected() throws Exception {
        Assume.assumeTrue("单测 JVM 里的 org.json 不可用（android.jar 打桩）", jsonUsable());
        File request = writeRequest("reconcile-relative", reconcileItem(
                "\"row\":\"" + GHOST_ROW + "\",\"path\":\"DCIM/Camera/IMG_1.heic\",\"length\":"
                        + ORIGINAL_LENGTH));
        assertEquals("invalid_item_mode", parseFailure(request));
    }

    /** length 缺失：幽灵行的当前 size 是核心证据，必须给出。 */
    @Test
    public void missingLengthRejected() throws Exception {
        Assume.assumeTrue("单测 JVM 里的 org.json 不可用（android.jar 打桩）", jsonUsable());
        File request = writeRequest("reconcile-no-length", reconcileItem(
                "\"row\":\"" + GHOST_ROW + "\",\"path\":\"" + PHOTO + "\""));
        assertEquals("invalid_item_length", parseFailure(request));
    }

    /** length 不是正整数（JSON 字符串/负数）：整单拒绝。 */
    @Test
    public void badLengthRejected() throws Exception {
        Assume.assumeTrue("单测 JVM 里的 org.json 不可用（android.jar 打桩）", jsonUsable());
        File wrongType = writeRequest("reconcile-bad-length-type", reconcileItem(
                "\"row\":\"" + GHOST_ROW + "\",\"path\":\"" + PHOTO + "\",\"length\":\"4000\""));
        assertEquals("invalid_item_metadata", parseFailure(wrongType));
        File negative = writeRequest("reconcile-negative-length", reconcileItem(
                "\"row\":\"" + GHOST_ROW + "\",\"path\":\"" + PHOTO + "\",\"length\":-1"));
        assertEquals("invalid_item_length", parseFailure(negative));
    }

    /** previewLength（可选）给了就必须是正整数。 */
    @Test
    public void badPreviewLengthRejected() throws Exception {
        Assume.assumeTrue("单测 JVM 里的 org.json 不可用（android.jar 打桩）", jsonUsable());
        File zero = writeRequest("reconcile-zero-preview", reconcileItem(
                "\"row\":\"" + GHOST_ROW + "\",\"path\":\"" + PHOTO + "\",\"length\":"
                        + ORIGINAL_LENGTH + ",\"previewLength\":0"));
        assertEquals("invalid_item_mode", parseFailure(zero));
        File wrongType = writeRequest("reconcile-bad-preview", reconcileItem(
                "\"row\":\"" + GHOST_ROW + "\",\"path\":\"" + PHOTO + "\",\"length\":"
                        + ORIGINAL_LENGTH + ",\"previewLength\":\"260000\""));
        assertEquals("invalid_item_mode", parseFailure(wrongType));
    }

    /** row 缺失：稳定 reason 而不是 JSONException 驱动的 internal_error。 */
    @Test
    public void missingRowRejected() throws Exception {
        Assume.assumeTrue("单测 JVM 里的 org.json 不可用（android.jar 打桩）", jsonUsable());
        File request = writeRequest("reconcile-no-row", reconcileItem(
                "\"path\":\"" + PHOTO + "\",\"length\":" + ORIGINAL_LENGTH));
        assertEquals("missing_item_row", parseFailure(request));
    }

    /** baseRow 是 legacy_copies/stale_rows 的形状：reconcile 不接受见证行，整单拒绝。 */
    @Test
    public void baseRowRejectedForReconcile() throws Exception {
        Assume.assumeTrue("单测 JVM 里的 org.json 不可用（android.jar 打桩）", jsonUsable());
        File request = writeRequest("reconcile-base-row", reconcileItem(
                "\"row\":\"" + GHOST_ROW + "\",\"path\":\"" + PHOTO + "\",\"length\":"
                        + ORIGINAL_LENGTH + ",\"baseRow\":\"15000\""));
        assertEquals("invalid_item_mode", parseFailure(request));
    }

    /** 解析出来的 path/previewLength 必须原样落到 RequestItem 上。 */
    @Test
    public void parsedReconcileFieldsSurvive() throws Exception {
        Assume.assumeTrue("单测 JVM 里的 org.json 不可用（android.jar 打桩）", jsonUsable());
        File request = writeRequest("reconcile-fields", reconcileItem(
                "\"row\":\"" + GHOST_ROW + "\",\"path\":\"" + PHOTO + "\",\"length\":"
                        + ORIGINAL_LENGTH + ",\"previewLength\":" + PREVIEW_LENGTH));
        Object item = firstItem(parseRequest(request));
        assertEquals(GHOST_ROW, field(item, "row"));
        assertEquals(PHOTO, field(item, "path"));
        assertEquals(Long.valueOf(ORIGINAL_LENGTH), field(item, "length"));
        assertEquals(Long.valueOf(PREVIEW_LENGTH), field(item, "previewLength"));
        assertEquals("reconcile_inplace", field(item, "mode"));
    }

    /** 模式白名单与“不是迁移模式”一并钉住：reconcile 不需要 sha1。 */
    @Test
    public void modeWhitelistIncludesReconcile() {
        assertTrue(MetadataRestoreJob.isSupportedItemMode("reconcile_inplace"));
        assertFalse(MetadataRestoreJob.isSupportedItemMode("reconcile"));
        assertFalse(MetadataRestoreJob.isMigrationMode("reconcile_inplace"));
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    /** 一份“证据链全成立”的事实：幽灵形状 + 旧原图 size + 唯一的 keeper 行。 */
    private static MetadataRestoreJob.ReconcileFacts ghostFacts() {
        MetadataRestoreJob.ReconcileFacts facts = new MetadataRestoreJob.ReconcileFacts();
        facts.rowExists = true;
        facts.localFile = PHOTO;
        facts.localFileMatches = true;
        facts.thumbnailEmpty = true;
        facts.serverIdEmpty = true;
        facts.fileLength = PREVIEW_LENGTH;
        facts.rowSize = ORIGINAL_LENGTH;
        facts.keeperCount = 1;
        facts.keeperRowId = KEEPER_ROW;
        facts.keeperSha1 = KEEPER_SHA1;
        facts.keeperSize = PREVIEW_LENGTH;
        facts.keeperSizeMatches = true;
        facts.keeperServerIdEmpty = true;
        facts.keeperThumbnailEmpty = true;
        return facts;
    }

    private static String decision(MetadataRestoreJob.ReconcileFacts facts) {
        return MetadataRestoreJob.Reconcile.decision(facts);
    }

    private static String reconcileItem(String fields) {
        return "{\"mode\":\"reconcile_inplace\"," + fields + "}";
    }

    private static File writeRequest(String name, String item) throws Exception {
        File request = File.createTempFile("gallerysync-" + name, ".json");
        request.deleteOnExit();
        String body = "{\"jobId\":\"" + name + "\",\"target\":\"" + TARGET + "\",\"items\":["
                + item + "]}";
        Files.write(request.toPath(), body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    /** 调用生产代码的 private static parseRequest(File)：返回拒绝理由，接受时返回 null。 */
    private static String parseFailure(File request) throws Exception {
        try {
            parseRequest(request);
            return null;
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            String message = cause == null ? null : cause.getMessage();
            return message == null ? cause.getClass().getSimpleName() : message;
        }
    }

    private static Object parseRequest(File request) throws Exception {
        Method parse = MetadataRestoreJob.class.getDeclaredMethod("parseRequest", File.class);
        parse.setAccessible(true);
        return parse.invoke(null, request);
    }

    private static Object firstItem(Object parsed) throws Exception {
        Field field = parsed.getClass().getDeclaredField("items");
        field.setAccessible(true);
        return ((java.util.List<?>) field.get(parsed)).get(0);
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static boolean jsonUsable() {
        try {
            return new org.json.JSONObject("{\"probe\":1}").getInt("probe") == 1;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
