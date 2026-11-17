package com.tongluosao.gallerysync.hook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.tongluosao.gallerysync.core.InplaceSlimPolicy;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 第 5 波（原地覆盖迁移）的纯逻辑测试：老侧车预览怎么迁移
 * （{@link InplaceSlimPolicy#migrationAction} 在迁移上下文里的四种结论 + 目标占用判定）、
 * {@code repair_state} 的判定顺序，以及 {@code migrate_inplace}/{@code repair_state}
 * 两个新请求模式的解析形状。
 *
 * <p>判定用例直接构造 {@link MetadataRestoreJob.MigrationFacts} /
 * {@link MetadataRestoreJob.RepairFacts}，不启动 Android、不访问磁盘、不建真文件：
 * 这正是把判定抽成 static 纯函数的目的 —— 迁移会真搬文件，判定层必须先被钉死。
 *
 * <p>解析用例走生产代码的 {@code parseRequest(File)}（与既有 hook 测试同一约定）。
 * 单测 JVM 里的 org.json 来自 android.jar 的打桩实现，不可用时这些用例整体跳过；
 * 纯逻辑用例不依赖 org.json，因此永远会跑。
 */
public class MetadataRestoreJobMigrateTest {

    private static final String SHA1 = "0123456789abcdef0123456789abcdef01234567";
    private static final String SHA256 =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String OTHER_SHA256 =
            "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210";
    private static final String TARGET =
            "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
    private static final String ROW = "3321";
    private static final String ALBUM = "/storage/emulated/0/DCIM/Camera";
    private static final String SLIM = ALBUM + "/.gallerysync-slim";
    private static final String PREVIEW = SLIM + "/a1b2c3d4e5f6.heic";
    private static final String NAME = "IMG_20240101_120000.heic";
    /** 原图字节数；预览比它小，因此策略不会给 SKIP。 */
    private static final long ROW_SIZE = 4_000_000L;
    private static final long PREVIEW_LENGTH = 260_000L;

    // ------------------------------------------------------------------
    // migrationAction 的四类结论在迁移上下文里的用法
    // ------------------------------------------------------------------

    /** 同扩展名：策略给 RENAME，判定层放行，且不要求目标已存在。 */
    @Test
    public void renameWhenSameExtensionFamily() {
        assertEquals(InplaceSlimPolicy.MigrationAction.RENAME,
                InplaceSlimPolicy.migrationAction(NAME, PREVIEW, "heic", true, true));
        MetadataRestoreJob.MigrationDecision decision =
                MetadataRestoreJob.Migration.decision(sidecarFacts());
        assertEquals("", decision.reason);
        assertEquals(InplaceSlimPolicy.MigrationAction.RENAME, decision.action);
        assertFalse(decision.repairOnly);
    }

    /** jpg 预览 + heic 原名：策略给 TRANSCODE，本波不转码 → 只跳过，不算失败。 */
    @Test
    public void transcodeDeferredWhenPreviewIsJpeg() {
        String jpegPreview = SLIM + "/a1b2c3d4e5f6.jpg";
        assertEquals(InplaceSlimPolicy.MigrationAction.TRANSCODE,
                InplaceSlimPolicy.migrationAction(NAME, jpegPreview, "jpg", true, true));
        MetadataRestoreJob.MigrationFacts facts = sidecarFacts();
        facts.thumbnailFile = jpegPreview;
        facts.previewFormat = "jpg";
        MetadataRestoreJob.MigrationDecision decision =
                MetadataRestoreJob.Migration.decision(facts);
        assertEquals("migrate_transcode_deferred", decision.reason);
        assertEquals(InplaceSlimPolicy.MigrationAction.TRANSCODE, decision.action);
        assertTrue(MetadataRestoreJob.Migration.isSkipReason(decision.reason));
    }

    /** png 原名 + webp 预览：容器不能原地覆盖 → 留在侧车目录，跳过而非失败。 */
    @Test
    public void sidecarKeptForUnsupportedContainer() {
        String pngName = "IMG_20240101_120000.png";
        String webpPreview = SLIM + "/a1b2c3d4e5f6.webp";
        assertEquals(InplaceSlimPolicy.MigrationAction.SIDECAR,
                InplaceSlimPolicy.migrationAction(pngName, webpPreview, "webp", true, true));
        MetadataRestoreJob.MigrationFacts facts = sidecarFacts();
        facts.fileName = pngName;
        facts.thumbnailFile = webpPreview;
        facts.previewFormat = "webp";
        MetadataRestoreJob.MigrationDecision decision =
                MetadataRestoreJob.Migration.decision(facts);
        assertEquals("migrate_sidecar_kept", decision.reason);
        assertEquals(InplaceSlimPolicy.MigrationAction.SIDECAR, decision.action);
        assertTrue(MetadataRestoreJob.Migration.isSkipReason(decision.reason));
    }

    /** 预览不比原图小（或根本不在）：策略给 SKIP → migrate_skipped。 */
    @Test
    public void skippedWhenPreviewNotSmaller() {
        assertEquals(InplaceSlimPolicy.MigrationAction.SKIP,
                InplaceSlimPolicy.migrationAction(NAME, PREVIEW, "heic", true, false));
        MetadataRestoreJob.MigrationFacts facts = sidecarFacts();
        facts.previewLength = ROW_SIZE;
        MetadataRestoreJob.MigrationDecision decision =
                MetadataRestoreJob.Migration.decision(facts);
        assertEquals("migrate_skipped", decision.reason);
        assertTrue(MetadataRestoreJob.Migration.isSkipReason(decision.reason));
    }

    /** 预览文件不存在：migrationAction 的 previewExists=false 分支 → SKIP。 */
    @Test
    public void skippedWhenPreviewMissing() {
        MetadataRestoreJob.MigrationFacts facts = sidecarFacts();
        facts.previewExists = false;
        facts.previewLength = 0L;
        MetadataRestoreJob.MigrationDecision decision =
                MetadataRestoreJob.Migration.decision(facts);
        assertEquals("migrate_skipped", decision.reason);
    }

    /** 行 size 未知（0）时无法证明预览更小：保守跳过，绝不把预览写到原路径。 */
    @Test
    public void skippedWhenRowSizeUnknown() {
        MetadataRestoreJob.MigrationFacts facts = sidecarFacts();
        facts.rowSize = 0L;
        MetadataRestoreJob.MigrationDecision decision =
                MetadataRestoreJob.Migration.decision(facts);
        assertEquals("migrate_skipped", decision.reason);
    }

    // ------------------------------------------------------------------
    // 同一判定里的目标占用分支（5a）
    // ------------------------------------------------------------------

    /** 目标上已经是登记过的完整原图：只修数据库，不搬文件（repairOnly）。 */
    @Test
    public void repairOnlyWhenTargetIsRegisteredOriginal() {
        MetadataRestoreJob.MigrationFacts facts = sidecarFacts();
        facts.targetExists = true;
        facts.targetLength = ROW_SIZE;
        facts.targetSha256 = SHA256;
        facts.originalSha256 = SHA256;
        MetadataRestoreJob.MigrationDecision decision =
                MetadataRestoreJob.Migration.decision(facts);
        assertEquals("", decision.reason);
        assertTrue(decision.repairOnly);
        assertEquals(InplaceSlimPolicy.MigrationAction.RENAME, decision.action);
    }

    /** 目标被占但不是原图（长度或摘要对不上）：migrate_target_exists，失败而非跳过。 */
    @Test
    public void targetExistsRejectedWhenNotOriginal() {
        MetadataRestoreJob.MigrationFacts wrongLength = sidecarFacts();
        wrongLength.targetExists = true;
        wrongLength.targetLength = 123L;
        wrongLength.targetSha256 = SHA256;
        wrongLength.originalSha256 = SHA256;
        assertEquals("migrate_target_exists",
                MetadataRestoreJob.Migration.decision(wrongLength).reason);

        MetadataRestoreJob.MigrationFacts wrongDigest = sidecarFacts();
        wrongDigest.targetExists = true;
        wrongDigest.targetLength = ROW_SIZE;
        wrongDigest.targetSha256 = OTHER_SHA256;
        wrongDigest.originalSha256 = SHA256;
        MetadataRestoreJob.MigrationDecision decision =
                MetadataRestoreJob.Migration.decision(wrongDigest);
        assertEquals("migrate_target_exists", decision.reason);
        assertFalse(MetadataRestoreJob.Migration.isSkipReason(decision.reason));
    }

    // ------------------------------------------------------------------
    // 行形状 / 路径还原
    // ------------------------------------------------------------------

    @Test
    public void sidecarRowDetection() {
        assertTrue(MetadataRestoreJob.Migration.isSidecarRow("", PREVIEW));
        assertTrue(MetadataRestoreJob.Migration.isSidecarRow(null, PREVIEW));
        // localFile 非空：这是原地行，不是侧车行。
        assertFalse(MetadataRestoreJob.Migration.isSidecarRow(PREVIEW, PREVIEW));
        // 缩略图不在 .gallerysync-slim 下：不是侧车行。
        assertFalse(MetadataRestoreJob.Migration.isSidecarRow("", ALBUM + "/x.heic"));
        assertFalse(MetadataRestoreJob.Migration.isSidecarRow("", null));
    }

    @Test
    public void albumDirectoryRestoredFromPreviewPath() {
        assertEquals(ALBUM, MetadataRestoreJob.Migration.slimAlbumDirectory(PREVIEW));
        // 反斜杠写法与重复分隔符按同一段识别。
        assertEquals(ALBUM, MetadataRestoreJob.Migration.slimAlbumDirectory(
                ALBUM + "\\.gallerysync-slim\\x.heic"));
        // 路径里没有这一段、或这段就在根上：认不出来。
        assertNull(MetadataRestoreJob.Migration.slimAlbumDirectory(ALBUM + "/x.heic"));
        assertNull(MetadataRestoreJob.Migration.slimAlbumDirectory("/.gallerysync-slim/x.heic"));
        assertNull(MetadataRestoreJob.Migration.slimAlbumDirectory(""));
    }

    @Test
    public void targetPathIsAlbumPlusFileName() {
        assertEquals(ALBUM + "/" + NAME,
                MetadataRestoreJob.Migration.migrationTargetPath(PREVIEW, NAME));
        // 带分隔符或 .. 的名字可能指到相册外面：一律拒绝。
        assertNull(MetadataRestoreJob.Migration.migrationTargetPath(PREVIEW, "../../etc/passwd"));
        assertNull(MetadataRestoreJob.Migration.migrationTargetPath(PREVIEW, ".."));
        assertNull(MetadataRestoreJob.Migration.migrationTargetPath(PREVIEW, ""));
        assertNull(MetadataRestoreJob.Migration.migrationTargetPath(ALBUM + "/x.heic", NAME));
    }

    @Test
    public void previewFormatFromPath() {
        assertEquals("heic", MetadataRestoreJob.Migration.previewFormatOf(PREVIEW));
        assertEquals("jpg", MetadataRestoreJob.Migration.previewFormatOf(SLIM + "/x.JPG"));
        assertNull(MetadataRestoreJob.Migration.previewFormatOf(SLIM + "/noext"));
        assertNull(MetadataRestoreJob.Migration.previewFormatOf(null));
    }

    @Test
    public void rowOnlyReasonOrder() {
        MetadataRestoreJob.MigrationFacts missing = sidecarFacts();
        missing.rowExists = false;
        assertEquals("migrate_row_missing",
                MetadataRestoreJob.Migration.rowOnlyReason(missing));

        MetadataRestoreJob.MigrationFacts notSidecar = sidecarFacts();
        notSidecar.localFile = PREVIEW;
        assertEquals("migrate_not_sidecar",
                MetadataRestoreJob.Migration.rowOnlyReason(notSidecar));
        assertTrue(MetadataRestoreJob.Migration.isSkipReason("migrate_not_sidecar"));

        MetadataRestoreJob.MigrationFacts noName = sidecarFacts();
        noName.fileName = "";
        assertEquals("migrate_name_missing",
                MetadataRestoreJob.Migration.rowOnlyReason(noName));
        // 没有原名是数据问题，不是“不需要迁移”：必须计入失败。
        assertFalse(MetadataRestoreJob.Migration.isSkipReason("migrate_name_missing"));
        assertEquals("", MetadataRestoreJob.Migration.rowOnlyReason(sidecarFacts()));
    }

    // ------------------------------------------------------------------
    // repair_state 的判定顺序
    // ------------------------------------------------------------------

    /** 顺序第一条：没有注册表记录时不动磁盘、不动状态。 */
    @Test
    public void repairNeedsRegistryRecord() {
        MetadataRestoreJob.RepairFacts facts = repairFacts();
        facts.recordExists = false;
        assertEquals("no_registry_record", MetadataRestoreJob.RepairState.decision(facts).reason);
        assertEquals("no_registry_record",
                MetadataRestoreJob.RepairState.decision(null).reason);
    }

    /** 已经是 ORIGINAL：幂等返回，即使目标文件不在也不报 repair_missing。 */
    @Test
    public void repairAlreadyOriginalWins() {
        MetadataRestoreJob.RepairFacts facts = repairFacts();
        facts.alreadyOriginal = true;
        facts.targetPresent = false;
        assertEquals("repair_already_original",
                MetadataRestoreJob.RepairState.decision(facts).reason);
    }

    /** 目标不存在：保留状态等下载。 */
    @Test
    public void repairMissingKeepsState() {
        MetadataRestoreJob.RepairFacts facts = repairFacts();
        facts.targetPresent = false;
        assertEquals("repair_missing", MetadataRestoreJob.RepairState.decision(facts).reason);
    }

    /** 长度等于原图长度、摘要一致或登记摘要为空 → 只回落状态。 */
    @Test
    public void repairOriginalRestoredLocally() {
        MetadataRestoreJob.RepairFacts facts = repairFacts();
        facts.originalLength = 4_000_000L;
        facts.targetLength = 4_000_000L;
        facts.originalSha256 = SHA256;
        facts.targetSha256 = SHA256;
        assertEquals(MetadataRestoreJob.RepairState.ACTION_RESTORE_LOCAL,
                MetadataRestoreJob.RepairState.decision(facts).action);

        // 登记里没有摘要：长度对上就够（这是 fail-open，因为原图摘要确实可能没登记）。
        MetadataRestoreJob.RepairFacts noDigest = repairFacts();
        noDigest.originalLength = 4_000_000L;
        noDigest.targetLength = 4_000_000L;
        noDigest.originalSha256 = null;
        assertEquals(MetadataRestoreJob.RepairState.ACTION_RESTORE_LOCAL,
                MetadataRestoreJob.RepairState.decision(noDigest).action);
    }

    /** 长度与预览摘要都对上 → 记成原地预览。 */
    @Test
    public void repairPreviewConfirmed() {
        MetadataRestoreJob.RepairFacts facts = repairFacts();
        facts.originalLength = 4_000_000L;
        facts.targetLength = PREVIEW_LENGTH;
        facts.originalSha256 = SHA256;
        facts.targetSha256 = OTHER_SHA256;
        facts.previewLength = PREVIEW_LENGTH;
        facts.previewSha256 = OTHER_SHA256;
        assertEquals(MetadataRestoreJob.RepairState.ACTION_CONFIRM_PREVIEW,
                MetadataRestoreJob.RepairState.decision(facts).action);
    }

    /** 两种字节都不认识 → 重新从远端取原图。 */
    @Test
    public void repairFallsBackToRemote() {
        MetadataRestoreJob.RepairFacts facts = repairFacts();
        facts.originalLength = 4_000_000L;
        facts.targetLength = 999L;
        facts.originalSha256 = SHA256;
        facts.targetSha256 = SHA256;
        facts.previewLength = PREVIEW_LENGTH;
        facts.previewSha256 = OTHER_SHA256;
        assertEquals(MetadataRestoreJob.RepairState.ACTION_RESTORE_REMOTE,
                MetadataRestoreJob.RepairState.decision(facts).action);
    }

    /** 预览摘要为空时不能“确认预览”（否则会把未知字节登记成预览），退回远端回填。 */
    @Test
    public void repairPreviewNeedsDigest() {
        MetadataRestoreJob.RepairFacts facts = repairFacts();
        facts.originalLength = 4_000_000L;
        facts.targetLength = PREVIEW_LENGTH;
        facts.originalSha256 = SHA256;
        facts.targetSha256 = SHA256;
        facts.previewLength = PREVIEW_LENGTH;
        facts.previewSha256 = null;
        assertEquals(MetadataRestoreJob.RepairState.ACTION_RESTORE_REMOTE,
                MetadataRestoreJob.RepairState.decision(facts).action);
    }

    // ------------------------------------------------------------------
    // 请求解析的纯部分（不依赖 org.json）
    // ------------------------------------------------------------------

    @Test
    public void dryRunParsesOnlyBooleans() throws Exception {
        assertFalse(MetadataRestoreJob.parseDryRun(null));
        assertTrue(MetadataRestoreJob.parseDryRun(Boolean.TRUE));
        assertFalse(MetadataRestoreJob.parseDryRun(Boolean.FALSE));
        // 字符串 "true" 不算布尔：演练开关写错类型时必须整单拒绝，不能猜。
        assertInvalidDryRun("true");
        assertInvalidDryRun(Integer.valueOf(1));
    }

    @Test
    public void supportedModesIncludeNewOnes() {
        for (String mode : new String[]{"restore", "unslim", "legacy_copies", "stale_rows",
                "purge_items", "broken_payload", "poster_rewire", "orphan_rows",
                "migrate_inplace", "repair_state"}) {
            assertTrue(mode, MetadataRestoreJob.isSupportedItemMode(mode));
        }
        assertFalse(MetadataRestoreJob.isSupportedItemMode("frobnicate"));
        assertFalse(MetadataRestoreJob.isSupportedItemMode(null));
        assertTrue(MetadataRestoreJob.isMigrationMode("migrate_inplace"));
        assertTrue(MetadataRestoreJob.isMigrationMode("repair_state"));
        assertFalse(MetadataRestoreJob.isMigrationMode("restore"));
    }

    @Test
    public void newModeItemsRequireRowAndSha1() {
        assertEquals("missing_item_row",
                MetadataRestoreJob.newModeItemInvalidReason("migrate_inplace", false, true));
        assertEquals("missing_item_sha1",
                MetadataRestoreJob.newModeItemInvalidReason("migrate_inplace", true, false));
        assertEquals("missing_item_row",
                MetadataRestoreJob.newModeItemInvalidReason("repair_state", false, false));
        assertEquals("",
                MetadataRestoreJob.newModeItemInvalidReason("migrate_inplace", true, true));
        // 老模式不走这条校验，行为不变。
        assertEquals("",
                MetadataRestoreJob.newModeItemInvalidReason("restore", false, false));
    }

    @Test
    public void migrationOutcomeNames() {
        assertEquals("rename", MetadataRestoreJob.migrationOutcomeName(
                InplaceSlimPolicy.MigrationAction.RENAME));
        assertEquals("transcode", MetadataRestoreJob.migrationOutcomeName(
                InplaceSlimPolicy.MigrationAction.TRANSCODE));
        assertEquals("sidecar", MetadataRestoreJob.migrationOutcomeName(
                InplaceSlimPolicy.MigrationAction.SIDECAR));
        assertEquals("skip", MetadataRestoreJob.migrationOutcomeName(
                InplaceSlimPolicy.MigrationAction.SKIP));
        assertEquals("", MetadataRestoreJob.migrationOutcomeName(null));
    }

    // ------------------------------------------------------------------
    // 请求解析（org.json 不可用时跳过，与既有 hook 测试同一约定）
    // ------------------------------------------------------------------

    @Test
    public void migrateInplaceRequestParses() throws Exception {
        Assume.assumeTrue("单测 JVM 里的 org.json 不可用（android.jar 打桩）", jsonUsable());
        File request = writeRequest("migrate-ok", false,
                "{\"mode\":\"migrate_inplace\",\"row\":\"" + ROW + "\",\"sha1\":\"" + SHA1 + "\"}");
        assertNull(parseFailure(request));
    }

    @Test
    public void repairStateRequestParses() throws Exception {
        Assume.assumeTrue("单测 JVM 里的 org.json 不可用（android.jar 打桩）", jsonUsable());
        File request = writeRequest("repair-ok", false,
                "{\"mode\":\"repair_state\",\"row\":\"" + ROW + "\",\"sha1\":\"" + SHA1 + "\"}");
        assertNull(parseFailure(request));
    }

    @Test
    public void rootDryRunFlagIsRead() throws Exception {
        Assume.assumeTrue("单测 JVM 里的 org.json 不可用（android.jar 打桩）", jsonUsable());
        File dry = writeRequest("migrate-dry", true,
                "{\"mode\":\"migrate_inplace\",\"row\":\"" + ROW + "\",\"sha1\":\"" + SHA1 + "\"}");
        assertTrue(parsedDryRun(dry));
        File real = writeRequest("migrate-real", false,
                "{\"mode\":\"migrate_inplace\",\"row\":\"" + ROW + "\",\"sha1\":\"" + SHA1 + "\"}");
        assertFalse(parsedDryRun(real));
    }

    /** 根上的 dryRun 类型写错：整单 invalid_dry_run（不能悄悄当成 false 真跑）。 */
    @Test
    public void badDryRunTypeRejected() throws Exception {
        Assume.assumeTrue("单测 JVM 里的 org.json 不可用（android.jar 打桩）", jsonUsable());
        File request = writeRequestRaw("migrate-bad-dry",
                "\"dryRun\":\"true\",\"items\":[{\"mode\":\"migrate_inplace\",\"row\":\"" + ROW
                        + "\",\"sha1\":\"" + SHA1 + "\"}]");
        assertEquals("invalid_dry_run", parseFailure(request));
    }

    @Test
    public void missingRowOrSha1Rejected() throws Exception {
        Assume.assumeTrue("单测 JVM 里的 org.json 不可用（android.jar 打桩）", jsonUsable());
        File noRow = writeRequest("migrate-no-row", false,
                "{\"mode\":\"migrate_inplace\",\"sha1\":\"" + SHA1 + "\"}");
        assertEquals("missing_item_row", parseFailure(noRow));
        File noSha1 = writeRequest("repair-no-sha1", false,
                "{\"mode\":\"repair_state\",\"row\":\"" + ROW + "\"}");
        assertEquals("missing_item_sha1", parseFailure(noSha1));
    }

    @Test
    public void unknownModeRejected() throws Exception {
        Assume.assumeTrue("单测 JVM 里的 org.json 不可用（android.jar 打桩）", jsonUsable());
        File request = writeRequest("migrate-bad-mode", false,
                "{\"mode\":\"frobnicate\",\"row\":\"" + ROW + "\",\"sha1\":\"" + SHA1 + "\"}");
        assertEquals("invalid_item_mode", parseFailure(request));
    }

    /** 新模式的条目只有 row/sha1：出现 baseRow 说明按老形状造错了，整单拒绝。 */
    @Test
    public void baseRowRejectedForNewModes() throws Exception {
        Assume.assumeTrue("单测 JVM 里的 org.json 不可用（android.jar 打桩）", jsonUsable());
        File request = writeRequest("migrate-base-row", false,
                "{\"mode\":\"migrate_inplace\",\"row\":\"" + ROW + "\",\"sha1\":\"" + SHA1
                        + "\",\"baseRow\":\"15000\"}");
        assertEquals("invalid_item_mode", parseFailure(request));
    }

    /** 老模式与新模式混排也是合法请求：模式逐条判。 */
    @Test
    public void mixedOldAndNewItemsParse() throws Exception {
        Assume.assumeTrue("单测 JVM 里的 org.json 不可用（android.jar 打桩）", jsonUsable());
        File request = writeRequestRaw("migrate-mixed", "\"items\":["
                + "{\"mode\":\"migrate_inplace\",\"row\":\"" + ROW + "\",\"sha1\":\"" + SHA1 + "\"},"
                + "{\"mode\":\"repair_state\",\"row\":\"" + ROW + "\",\"sha1\":\"" + SHA1 + "\"}]");
        assertNull(parseFailure(request));
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    /** 一条形状正确的侧车行：预览在隐藏目录里、比原图小、目标还没被占。 */
    private static MetadataRestoreJob.MigrationFacts sidecarFacts() {
        MetadataRestoreJob.MigrationFacts facts = new MetadataRestoreJob.MigrationFacts();
        facts.rowExists = true;
        facts.localFile = "";
        facts.thumbnailFile = PREVIEW;
        facts.fileName = NAME;
        facts.rowSize = ROW_SIZE;
        facts.previewExists = true;
        facts.previewLength = PREVIEW_LENGTH;
        facts.previewFormat = "heic";
        facts.targetExists = false;
        facts.originalSha256 = SHA256;
        return facts;
    }

    /** 一条 PENDING_INPLACE 记录：目标在、长度与摘要待判。 */
    private static MetadataRestoreJob.RepairFacts repairFacts() {
        MetadataRestoreJob.RepairFacts facts = new MetadataRestoreJob.RepairFacts();
        facts.recordExists = true;
        facts.targetPresent = true;
        facts.targetLength = 4_000_000L;
        facts.targetSha256 = SHA256;
        facts.originalLength = 4_000_000L;
        facts.originalSha256 = SHA256;
        return facts;
    }

    private static void assertInvalidDryRun(Object raw) throws Exception {
        try {
            boolean value = MetadataRestoreJob.parseDryRun(raw);
            throw new AssertionError("expected invalid_dry_run, got " + value);
        } catch (IOException expected) {
            assertEquals("invalid_dry_run", expected.getMessage());
        }
    }

    private static File writeRequest(String name, boolean dryRun, String item) throws Exception {
        return writeRequestRaw(name, (dryRun ? "\"dryRun\":true," : "") + "\"items\":[" + item + "]");
    }

    private static File writeRequestRaw(String name, String tail) throws Exception {
        File request = File.createTempFile("gallerysync-" + name, ".json");
        request.deleteOnExit();
        String body = "{\"jobId\":\"" + name + "\",\"target\":\"" + TARGET + "\"," + tail + "}";
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

    /** 读回 Request.dryRun：解析结果本身也是被测形状的一部分。 */
    private static boolean parsedDryRun(File request) throws Exception {
        Object parsed = parseRequest(request);
        Field field = parsed.getClass().getDeclaredField("dryRun");
        field.setAccessible(true);
        return field.getBoolean(parsed);
    }

    private static Object parseRequest(File request) throws Exception {
        Method parse = MetadataRestoreJob.class.getDeclaredMethod("parseRequest", File.class);
        parse.setAccessible(true);
        return parse.invoke(null, request);
    }

    private static boolean jsonUsable() {
        try {
            return new org.json.JSONObject("{\"probe\":1}").getInt("probe") == 1;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
