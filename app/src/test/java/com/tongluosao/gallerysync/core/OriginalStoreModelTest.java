package com.tongluosao.gallerysync.core;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * 注册表模型升级（{@code originals.json} v2）的兼容性。
 *
 * <p>两条底线：
 * <ul>
 *   <li>旧 v1 JSON 没有 {@code local_state} / {@code preview_*} 这些键，读出来必须是
 *       ORIGINAL / 0 / null，<b>而且不能抛</b> —— 解析失败会把整张注册表打成不健康，
 *       上传随即全部停摆；</li>
 *   <li>新字段必须在保存/重开后原样保留，{@link OriginalStore.Record#withLocalState} /
 *       {@link OriginalStore.Record#withPreview} 只动自己那几个字段。</li>
 * </ul>
 *
 * <p>落盘解析需要真实的 org.json：单测 JVM 里它来自 android.jar 的打桩实现，调用即抛
 * "not mocked"（与 {@code MetadataRestoreJobStaleRowsRequestTest} 同一套探针），
 * 因此那几条用例按仓库既有约定跳过；状态解析/派生这两块纯逻辑照常执行。
 */
public class OriginalStoreModelTest {

    private static final String SHA =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String PREVIEW_SHA =
            "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210";

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private static boolean jsonUsable() {
        try {
            return new org.json.JSONObject("{\"probe\":1}").getInt("probe") == 1;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 一条“上传成功后登记”的普通记录：本地就是完整原图，没有任何预览字段 */
    private static OriginalStore.Record sample() {
        return new OriginalStore.Record("target-key", "MiGallerySync/DCIM/Camera/IMG_1.jpg",
                4096L, SHA, "/storage/emulated/0/DCIM/Camera/IMG_1.jpg", null, "row-1",
                "sha1-0001", 1_700_000_000_000L, Collections.emptySet());
    }

    // ---------------------------------------------------------------- 状态线名

    /** 五个状态的线名是跨版本契约，改一个字就会把旧的 v2 文件读错 */
    @Test
    public void localStateWireNamesAreStable() {
        assertEquals("original", OriginalStore.LocalState.ORIGINAL.wire());
        assertEquals("inplace_preview", OriginalStore.LocalState.INPLACE_PREVIEW.wire());
        assertEquals("pending_inplace", OriginalStore.LocalState.PENDING_INPLACE.wire());
        assertEquals("sidecar_preview", OriginalStore.LocalState.SIDECAR_PREVIEW.wire());
        assertEquals("damaged", OriginalStore.LocalState.DAMAGED.wire());
    }

    /** 线名能原样解析回来，并容忍大小写/前后空白/连字符 */
    @Test
    public void localStateFromWireRoundTrips() {
        for (OriginalStore.LocalState state : OriginalStore.LocalState.values()) {
            assertSame(state, OriginalStore.LocalState.fromWire(state.wire()));
        }
        assertSame(OriginalStore.LocalState.INPLACE_PREVIEW,
                OriginalStore.LocalState.fromWire("  INPLACE_PREVIEW "));
        assertSame(OriginalStore.LocalState.INPLACE_PREVIEW,
                OriginalStore.LocalState.fromWire("inplace-preview"));
        assertSame(OriginalStore.LocalState.SIDECAR_PREVIEW,
                OriginalStore.LocalState.fromWire("Sidecar_Preview"));
    }

    /** 缺键 / 空串 / 字面量 null / 认不出来的值：一律 ORIGINAL，绝不抛异常 */
    @Test
    public void localStateFromWireFallsBackToOriginal() {
        assertSame(OriginalStore.LocalState.ORIGINAL, OriginalStore.LocalState.fromWire(null));
        assertSame(OriginalStore.LocalState.ORIGINAL, OriginalStore.LocalState.fromWire(""));
        assertSame(OriginalStore.LocalState.ORIGINAL, OriginalStore.LocalState.fromWire("   "));
        assertSame(OriginalStore.LocalState.ORIGINAL, OriginalStore.LocalState.fromWire("null"));
        assertSame(OriginalStore.LocalState.ORIGINAL, OriginalStore.LocalState.fromWire("banana"));
    }

    // ---------------------------------------------------------------- 记录缺省值

    /** 新登记出来的记录默认就是“本地即原图”，预览字段为空 */
    @Test
    public void newRecordDefaultsToOriginalWithoutPreview() {
        OriginalStore.Record record = sample();
        assertSame(OriginalStore.LocalState.ORIGINAL, record.localState);
        assertEquals(0L, record.previewLength);
        assertNull(record.previewSha256);
        assertNull(record.previewFormat);
        // 原图真相仍然记在 originalLength/sha256 上
        assertEquals(4096L, record.originalLength);
        assertEquals(SHA, record.sha256);
        assertTrue(record.derivedPaths.isEmpty());
        assertTrue(record.derivedHashes.isEmpty());
    }

    // ---------------------------------------------------------------- 不可变派生

    /** withLocalState 只改状态，其余字段逐个保持 */
    @Test
    public void withLocalStateKeepsEveryOtherField() {
        OriginalStore.Record base = sample().withPreview(2048L, PREVIEW_SHA, "heic");
        OriginalStore.Record next =
                base.withLocalState(OriginalStore.LocalState.INPLACE_PREVIEW);

        assertSame(OriginalStore.LocalState.INPLACE_PREVIEW, next.localState);
        assertSame(OriginalStore.LocalState.INPLACE_PREVIEW,
                base.withLocalState(OriginalStore.LocalState.INPLACE_PREVIEW).localState);
        // 原对象不变（不可变）
        assertSame(OriginalStore.LocalState.ORIGINAL, base.localState);

        assertEquals(base.target, next.target);
        assertEquals(base.remotePath, next.remotePath);
        assertEquals(base.originalLength, next.originalLength);
        assertEquals(base.sha256, next.sha256);
        assertEquals(base.originalLocalPath, next.originalLocalPath);
        assertEquals(base.previewLocalPath, next.previewLocalPath);
        assertEquals(base.rowId, next.rowId);
        assertEquals(base.sha1, next.sha1);
        assertEquals(base.originalLocalMtime, next.originalLocalMtime);
        assertEquals(base.derivedPaths, next.derivedPaths);
        assertEquals(base.derivedHashes, next.derivedHashes);
        // 预览三件套也原样带过去
        assertEquals(2048L, next.previewLength);
        assertEquals(PREVIEW_SHA, next.previewSha256);
        assertEquals("heic", next.previewFormat);
    }

    /** withPreview 只改预览字段，状态与其余字段逐个保持 */
    @Test
    public void withPreviewKeepsEveryOtherField() {
        OriginalStore.Record base =
                sample().withLocalState(OriginalStore.LocalState.PENDING_INPLACE);
        OriginalStore.Record next = base.withPreview(1024L, PREVIEW_SHA, "jpg");

        assertEquals(1024L, next.previewLength);
        assertEquals(PREVIEW_SHA, next.previewSha256);
        assertEquals("jpg", next.previewFormat);
        assertSame(OriginalStore.LocalState.PENDING_INPLACE, next.localState);
        assertEquals(0L, base.previewLength);

        assertEquals(base.target, next.target);
        assertEquals(base.remotePath, next.remotePath);
        assertEquals(base.originalLength, next.originalLength);
        assertEquals(base.sha256, next.sha256);
        assertEquals(base.originalLocalPath, next.originalLocalPath);
        assertEquals(base.previewLocalPath, next.previewLocalPath);
        assertEquals(base.rowId, next.rowId);
        assertEquals(base.sha1, next.sha1);
        assertEquals(base.originalLocalMtime, next.originalLocalMtime);
        assertEquals(base.derivedPaths, next.derivedPaths);
    }

    /** 空值兜底：null 状态按 ORIGINAL；负长度按 0；空串摘要/格式按 null */
    @Test
    public void derivationsNormalizeBadValues() {
        assertSame(OriginalStore.LocalState.ORIGINAL,
                sample().withLocalState(OriginalStore.LocalState.DAMAGED)
                        .withLocalState(null).localState);

        OriginalStore.Record empty = sample().withPreview(-1L, "  ", "");
        assertEquals(0L, empty.previewLength);
        assertNull(empty.previewSha256);
        assertNull(empty.previewFormat);

        OriginalStore.Record literalNull = sample().withPreview(10L, "null", "null");
        assertNull(literalNull.previewSha256);
        assertNull(literalNull.previewFormat);
        assertEquals(10L, literalNull.previewLength);
    }

    /** 派生后仍带别名：原地状态下原路径仍能被 findByLocalPath 找到（下一波要靠它定位） */
    @Test
    public void derivedRecordStillCarriesLocalAlias() {
        OriginalStore.Record record = sample()
                .withLocalState(OriginalStore.LocalState.INPLACE_PREVIEW)
                .withPreview(2048L, PREVIEW_SHA, "heic");
        // 明细字段只能从注册表里查；这里只保证路径没被派生方法抹掉
        assertEquals("/storage/emulated/0/DCIM/Camera/IMG_1.jpg", record.originalLocalPath);
        assertNull(record.previewLocalPath);
    }

    // ---------------------------------------------------------------- v1 JSON 向后兼容

    /** 旧 v1 JSON（没有新键）解析成 ORIGINAL / 0 / null，且不抛异常 */
    @Test
    public void v1JsonWithoutNewKeysLoadsAsOriginal() throws Exception {
        Assume.assumeTrue("单测 JVM 里的 org.json 不可用（android.jar 打桩）", jsonUsable());
        File directory = temporaryFolder.newFolder("v1");
        File photo = new File(directory, "IMG_1.jpg");
        writeJson(directory, version1Json(photo.getCanonicalPath()));

        OriginalStore store = new OriginalStore(directory);
        OriginalStore.Record record = store.findByLocalPath(photo);
        assertNotNull(record);
        assertSame(OriginalStore.LocalState.ORIGINAL, record.localState);
        assertEquals(0L, record.previewLength);
        assertNull(record.previewSha256);
        assertNull(record.previewFormat);
        // 老字段一个不少
        assertEquals(4096L, record.originalLength);
        assertEquals(SHA, record.sha256);
        assertEquals("row-1", record.rowId);
        assertEquals("sha1-0001", record.sha1);
    }

    /** v2 JSON 里的状态与预览描述原样读回来 */
    @Test
    public void v2JsonRestoresStateAndPreview() throws Exception {
        Assume.assumeTrue("单测 JVM 里的 org.json 不可用（android.jar 打桩）", jsonUsable());
        File directory = temporaryFolder.newFolder("v2");
        File photo = new File(directory, "IMG_1.heic");
        JSONObject root = root(2);
        JSONObject item = item(photo.getCanonicalPath());
        item.put("local_state", "inplace_preview");
        item.put("preview_length", 2048L);
        item.put("preview_sha256", PREVIEW_SHA);
        item.put("preview_format", "heic");
        root.put("records", new JSONArray().put(item));
        writeJson(directory, root);

        OriginalStore.Record record = new OriginalStore(directory).findByLocalPath(photo);
        assertNotNull(record);
        assertSame(OriginalStore.LocalState.INPLACE_PREVIEW, record.localState);
        assertEquals(2048L, record.previewLength);
        assertEquals(PREVIEW_SHA, record.previewSha256);
        assertEquals("heic", record.previewFormat);
        // 原地模式下预览就写在归属路径上，侧车路径必须为空
        assertNull(record.previewLocalPath);
    }

    /** 保存时写成 v2，并把状态带过去；重开注册表后仍然是原地预览 */
    @Test
    public void saveUpgradesToVersionTwoAndKeepsState() throws Exception {
        Assume.assumeTrue("单测 JVM 里的 org.json 不可用（android.jar 打桩）", jsonUsable());
        File directory = temporaryFolder.newFolder("roundtrip");
        File photo = new File(directory, "IMG_1.heic");
        JSONObject root = root(2);
        JSONObject item = item(photo.getCanonicalPath());
        item.put("local_state", "inplace_preview");
        item.put("preview_length", 2048L);
        item.put("preview_sha256", PREVIEW_SHA);
        item.put("preview_format", "heic");
        // 还没有相册行：这条记录的身份要靠下面的 bindIdentity 补上
        item.put("row", JSONObject.NULL);
        root.put("records", new JSONArray().put(item));
        writeJson(directory, root);

        OriginalStore store = new OriginalStore(directory);
        // 触发一次落盘（相册建行后补登记 identity 是真实会发生的写路径）
        assertTrue(store.bindIdentity(photo, "row-99", null));

        JSONObject saved = new JSONObject(new String(
                Files.readAllBytes(new File(directory, "originals.json").toPath()),
                StandardCharsets.UTF_8));
        assertEquals(2, saved.getInt("version"));
        JSONObject savedItem = saved.getJSONArray("records").getJSONObject(0);
        assertEquals("inplace_preview", savedItem.getString("local_state"));
        assertEquals(2048L, savedItem.getLong("preview_length"));
        assertEquals(PREVIEW_SHA, savedItem.getString("preview_sha256"));
        assertEquals("heic", savedItem.getString("preview_format"));

        OriginalStore.Record reloaded = new OriginalStore(directory).findByLocalPath(photo);
        assertNotNull(reloaded);
        assertSame(OriginalStore.LocalState.INPLACE_PREVIEW, reloaded.localState);
        assertEquals(2048L, reloaded.previewLength);
        assertEquals(PREVIEW_SHA, reloaded.previewSha256);
        assertEquals("heic", reloaded.previewFormat);
        assertEquals("row-99", reloaded.rowId);
    }

    /** 手改坏的状态/负数长度/字面量 null：解析全部兜底，注册表仍然健康 */
    @Test
    public void garbageValuesInJsonFallBackSafely() throws Exception {
        Assume.assumeTrue("单测 JVM 里的 org.json 不可用（android.jar 打桩）", jsonUsable());
        File directory = temporaryFolder.newFolder("garbage");
        File photo = new File(directory, "IMG_1.jpg");
        JSONObject root = root(1);
        JSONObject item = item(photo.getCanonicalPath());
        item.put("local_state", "BANANA");
        item.put("preview_length", -5L);
        item.put("preview_sha256", "null");
        item.put("preview_format", "");
        root.put("records", new JSONArray().put(item));
        writeJson(directory, root);

        OriginalStore store = new OriginalStore(directory);
        OriginalStore.Record record = store.findByLocalPath(photo);
        assertNotNull(record);
        assertSame(OriginalStore.LocalState.ORIGINAL, record.localState);
        assertEquals(0L, record.previewLength);
        assertNull(record.previewSha256);
        assertNull(record.previewFormat);
        // 注册表健康：还能继续写入（不健康时这里会抛）
        assertTrue(store.bindIdentity(photo, "row-7", null));
        assertTrue(new File(directory, "originals.json").length() > 0L);
    }

    // ---------------------------------------------------------------- 测试数据

    private static JSONObject root(int version) throws Exception {
        JSONObject root = new JSONObject();
        root.put("version", version);
        return root;
    }

    /** 一条只含老字段的 v1 记录 */
    private static JSONObject item(String originalPath) throws Exception {
        JSONObject item = new JSONObject();
        item.put("target", "target-key");
        item.put("remote", "MiGallerySync/DCIM/Camera/IMG_1.jpg");
        item.put("length", 4096L);
        item.put("sha256", SHA);
        item.put("original", originalPath);
        item.put("preview", JSONObject.NULL);
        item.put("row", "row-1");
        item.put("sha1", "sha1-0001");
        item.put("mtime", 1_700_000_000_000L);
        item.put("derived", new JSONArray());
        item.put("aliases", new JSONArray());
        item.put("identities", new JSONArray());
        item.put("derived_hashes", new JSONArray());
        return item;
    }

    private static JSONObject version1Json(String originalPath) throws Exception {
        JSONObject root = root(1);
        root.put("records", new JSONArray().put(item(originalPath)));
        return root;
    }

    private static void writeJson(File directory, JSONObject root) throws Exception {
        Files.write(new File(directory, "originals.json").toPath(),
                root.toString().getBytes(StandardCharsets.UTF_8));
    }
}
