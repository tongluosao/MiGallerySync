package com.tongluosao.gallerysync.hook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * stale_rows 的纯逻辑判定测试。
 *
 * <p>这些用例直接构造 {@link MetadataRestoreJob.StaleRowFacts}，不启动 Android、不访问磁盘，
 * 断言的就是运行时删除任何一行之前必经的那道 gate（{@code StaleRows.decision}）：
 * 返回空串才允许删，返回的字符串就是结果里上报的 reason。
 */
public class MetadataRestoreJobStaleRowsTest {

    private static String decision(MetadataRestoreJob.StaleRowFacts facts) {
        return MetadataRestoreJob.StaleRows.decision(facts);
    }

    /**
     * 第 1 类：.gallerysync-slim/ 目录内的误入库行，baseRow 证明真照片还在盘上。
     *
     * <p>{@code onlyRowForSha1} 为 false 不是随手写的：真机上这两条瘦身行的 sha1 在本库中唯一，
     * 是 base 行（同一张照片的另一份文件、同 dateTaken、不同 sha1）校验通过后
     * {@code collectStaleRowFacts} 才清掉唯一性标记，否则 {@code stale_row_would_lose_file} 会把
     * 任务给出的两条样本全部拒掉。
     */
    private static MetadataRestoreJob.StaleRowFacts classOneFacts() {
        MetadataRestoreJob.StaleRowFacts facts = new MetadataRestoreJob.StaleRowFacts();
        facts.rowId = "15989";
        facts.rowExists = true;
        facts.sha1Matches = true;
        facts.hasServerId = false;
        facts.localFlag = 7;
        facts.localFileResolved = true;
        facts.slimDirectory = true;
        facts.referencingRowIds = new ArrayList<>(Arrays.asList("15989"));
        facts.onlyRowForSha1 = false;
        facts.classOne = true;
        facts.baseRowValid = true;
        return facts;
    }

    /** 第 2 类：与 twin 同 sha1/localFile 的重复登记行，twin 的 size 才是磁盘字节数。 */
    private static MetadataRestoreJob.StaleRowFacts classTwoFacts() {
        MetadataRestoreJob.StaleRowFacts facts = new MetadataRestoreJob.StaleRowFacts();
        facts.rowId = "14";
        facts.rowExists = true;
        facts.sha1Matches = true;
        facts.hasServerId = false;
        facts.localFlag = 8;
        facts.localFileResolved = true;
        facts.slimDirectory = false;
        // 同一份文件必然由 twin 引用；twin 之外没有别的行。
        facts.referencingRowIds = new ArrayList<>(Arrays.asList("14", "15563"));
        facts.onlyRowForSha1 = false;
        facts.classTwo = true;
        facts.diskLengthPositive = true;
        facts.twinFound = true;
        facts.twinRowId = "15563";
        facts.rowSizeStale = true;
        facts.diskLengthMatchesFile = true;
        return facts;
    }

    @Test
    public void classOneRowIsDeletable() {
        assertEquals("", decision(classOneFacts()));
    }

    @Test
    public void classOneRowWithFlagEightIsDeletable() {
        MetadataRestoreJob.StaleRowFacts facts = classOneFacts();
        facts.localFlag = 8;
        assertEquals("", decision(facts));
    }

    @Test
    public void classTwoRowIsDeletableEvenThoughItsTwinReferencesTheSameFile() {
        assertEquals("", decision(classTwoFacts()));
    }

    @Test
    public void missingRowIsRejectedFirst() {
        MetadataRestoreJob.StaleRowFacts facts = classOneFacts();
        facts.rowExists = false;
        facts.sha1Matches = false;
        facts.hasServerId = true;
        facts.localFlag = 0;
        assertEquals("stale_row_missing", decision(facts));
    }

    @Test
    public void sha1MismatchIsRejected() {
        MetadataRestoreJob.StaleRowFacts facts = classOneFacts();
        facts.sha1Matches = false;
        assertEquals("stale_row_sha1_mismatch", decision(facts));
    }

    @Test
    public void uploadedRowIsRejected() {
        MetadataRestoreJob.StaleRowFacts facts = classOneFacts();
        facts.hasServerId = true;
        assertEquals("stale_row_has_server_id", decision(facts));
    }

    @Test
    public void rowWithoutLocalFlagSevenOrEightIsRejected() {
        MetadataRestoreJob.StaleRowFacts facts = classOneFacts();
        facts.localFlag = 5;
        assertEquals("stale_row_state_unsafe", decision(facts));
    }

    @Test
    public void rowWithoutResolvableLocalFileIsRejected() {
        MetadataRestoreJob.StaleRowFacts facts = classOneFacts();
        facts.localFileResolved = false;
        assertEquals("stale_row_state_unsafe", decision(facts));
    }

    @Test
    public void rowWhoseFileIsStillReferencedByAnotherRowIsRejected() {
        MetadataRestoreJob.StaleRowFacts facts = classOneFacts();
        facts.referencingRowIds = new ArrayList<>(Arrays.asList("15989", "16001"));
        assertEquals("stale_row_file_shared", decision(facts));
    }

    @Test
    public void classTwoRowStillReferencedByAThirdRowIsRejected() {
        MetadataRestoreJob.StaleRowFacts facts = classTwoFacts();
        facts.referencingRowIds = new ArrayList<>(Arrays.asList("14", "15563", "16001"));
        assertEquals("stale_row_file_shared", decision(facts));
    }

    @Test
    public void referenceQueryFailureIsTreatedAsShared() {
        MetadataRestoreJob.StaleRowFacts facts = classOneFacts();
        facts.referenceQueryFailed = true;
        facts.referencingRowIds = new ArrayList<>();
        assertEquals("stale_row_file_shared", decision(facts));
    }

    @Test
    public void lastRowForTheSha1IsRejected() {
        MetadataRestoreJob.StaleRowFacts facts = classOneFacts();
        facts.onlyRowForSha1 = true;
        assertEquals("stale_row_would_lose_file", decision(facts));
    }

    @Test
    public void itemWithBothProofsIsRejected() {
        MetadataRestoreJob.StaleRowFacts facts = classTwoFacts();
        facts.classOne = true;
        assertEquals("invalid_item_mode", decision(facts));
    }

    @Test
    public void itemWithNoProofIsRejected() {
        MetadataRestoreJob.StaleRowFacts facts = classOneFacts();
        facts.classOne = false;
        facts.classTwo = false;
        assertEquals("invalid_item_mode", decision(facts));
    }

    @Test
    public void classOneRowOutsideTheSlimDirectoryIsRejected() {
        MetadataRestoreJob.StaleRowFacts facts = classOneFacts();
        facts.slimDirectory = false;
        assertEquals("invalid_item_mode", decision(facts));
    }

    @Test
    public void classOneRowWithoutAValidBaseRowIsRejected() {
        MetadataRestoreJob.StaleRowFacts facts = classOneFacts();
        facts.baseRowValid = false;
        assertEquals("invalid_base_row", decision(facts));
    }

    @Test
    public void classTwoRowWithNonPositiveDiskLengthIsRejected() {
        MetadataRestoreJob.StaleRowFacts facts = classTwoFacts();
        facts.diskLengthPositive = false;
        assertEquals("invalid_item_mode", decision(facts));
    }

    @Test
    public void classTwoRowWithoutATwinIsRejected() {
        MetadataRestoreJob.StaleRowFacts facts = classTwoFacts();
        facts.twinFound = false;
        facts.twinRowId = null;
        // twin 找不到时它也不再被排除，引用列表里只应剩本行。
        facts.referencingRowIds = new ArrayList<>(Arrays.asList("14"));
        assertEquals("stale_row_length_mismatch", decision(facts));
    }

    @Test
    public void classTwoRowIsRejectedWhenItIsTheOnlyRowForItsSha1() {
        MetadataRestoreJob.StaleRowFacts facts = classTwoFacts();
        facts.onlyRowForSha1 = true;
        assertEquals("stale_row_would_lose_file", decision(facts));
    }

    @Test
    public void classTwoRowWhoseSizeAlreadyMatchesTheDiskIsRejected() {
        MetadataRestoreJob.StaleRowFacts facts = classTwoFacts();
        facts.rowSizeStale = false;
        assertEquals("stale_row_length_mismatch", decision(facts));
    }

    @Test
    public void classTwoRowWhoseDiskLengthDisagreesWithTheFileIsRejected() {
        MetadataRestoreJob.StaleRowFacts facts = classTwoFacts();
        facts.diskLengthMatchesFile = false;
        assertEquals("stale_row_length_mismatch", decision(facts));
    }

    @Test
    public void slimDirectoryPathsAreDetected() {
        assertTrue(MetadataRestoreJob.StaleRows.isSlimDirectoryPath(
                "/storage/emulated/0/DCIM/Camera/.gallerysync-slim/IMG_20261019_120142.heic"));
        assertTrue(MetadataRestoreJob.StaleRows.isSlimDirectoryPath(
                "/sdcard/Pictures/.gallerysync-slim/sub/dir/pic.jpg"));
        assertTrue(MetadataRestoreJob.StaleRows.isSlimDirectoryPath(
                "/sdcard/Pictures/.Gallerysync-Slim/pic.jpg"));
        assertFalse(MetadataRestoreJob.StaleRows.isSlimDirectoryPath(
                "/storage/emulated/0/DCIM/Camera/IMG_20261019_120142.heic"));
        assertFalse(MetadataRestoreJob.StaleRows.isSlimDirectoryPath(
                "/storage/emulated/0/DCIM/Camera/gallerysync-slim/IMG_1.heic"));
        assertFalse(MetadataRestoreJob.StaleRows.isSlimDirectoryPath(null));
    }

    @Test
    public void sharingIsIgnoredForSelfAndForTheProvenTwin() {
        List<String> onlySelf = new ArrayList<>(Arrays.asList("14"));
        assertFalse(MetadataRestoreJob.StaleRows.sharesFileWithOtherRow(onlySelf, "14", "15563"));
        List<String> selfAndTwin = new ArrayList<>(Arrays.asList("14", "15563"));
        assertFalse(MetadataRestoreJob.StaleRows.sharesFileWithOtherRow(selfAndTwin, "14", "15563"));
        List<String> withThird = new ArrayList<>(Arrays.asList("14", "15563", "16001"));
        assertTrue(MetadataRestoreJob.StaleRows.sharesFileWithOtherRow(withThird, "14", "15563"));
        // twin 为 null 时没有可排除的另一行，同 sha1 的 15563 就是"其它引用"。
        assertTrue(MetadataRestoreJob.StaleRows.sharesFileWithOtherRow(selfAndTwin, "14", null));
        assertTrue(MetadataRestoreJob.StaleRows.sharesFileWithOtherRow(withThird, "14", "15563"));
        assertTrue(MetadataRestoreJob.StaleRows.sharesFileWithOtherRow(withThird, "14", null));
        assertTrue(MetadataRestoreJob.StaleRows.sharesFileWithOtherRow(null, "14", "15563"));
    }

    /**
     * 第 3 类：同一份文件被登记了两次（同 sha1、同 localFile，大小写不同算同一路径）。
     *
     * <p>本行是较新的那条登记（id 15563），要保留的是更老的 14；两行的 size 都等于盘上那份文件
     * 的字节数，引用列表里除了这两行没有别人。
     */
    private static MetadataRestoreJob.StaleRowFacts classThreeFacts() {
        MetadataRestoreJob.StaleRowFacts facts = new MetadataRestoreJob.StaleRowFacts();
        facts.rowId = "15563";
        facts.rowExists = true;
        facts.sha1Matches = true;
        facts.hasServerId = false;
        facts.localFlag = 7;
        facts.localFileResolved = true;
        facts.slimDirectory = false;
        facts.classThree = true;
        facts.duplicateFilePresent = true;
        facts.rowSizeMatchesDisk = true;
        facts.diskLengthMatchesFile = true;
        facts.referencingRowIds = new ArrayList<>(Arrays.asList("14", "15563"));
        facts.onlyRowForSha1 = false;
        facts.survivorFound = true;
        facts.survivorRowId = "14";
        facts.survivorNewer = false;
        facts.survivorSha1Matches = true;
        facts.survivorStateSafe = true;
        facts.survivorSizeMatchesFile = true;
        return facts;
    }

    @Test
    public void classThreeNewerDuplicateIsDeletable() {
        assertEquals("", decision(classThreeFacts()));
    }

    @Test
    public void classThreeSurvivorWithFlagZeroIsStillAccepted() {
        // 真机上 201 组重复行里有 154 组的最老那行是 Gallery 自建的 localFlag=0。
        MetadataRestoreJob.StaleRowFacts facts = classThreeFacts();
        facts.localFlag = 0;
        assertTrue(MetadataRestoreJob.StaleRows.isDuplicateLocalFlag(0));
        assertEquals("", decision(facts));
    }

    @Test
    public void classThreeOlderRowIsRefusedBecauseTheSurvivorIsNewer() {
        MetadataRestoreJob.StaleRowFacts facts = classThreeFacts();
        facts.rowId = "14";
        facts.survivorRowId = "15563";
        facts.survivorNewer = true;
        assertEquals("duplicate_survivor_newer", decision(facts));
    }

    @Test
    public void classThreeRowWithFlagTwoIsRefused() {
        MetadataRestoreJob.StaleRowFacts facts = classThreeFacts();
        facts.localFlag = 2;
        assertFalse(MetadataRestoreJob.StaleRows.isDuplicateLocalFlag(2));
        assertEquals("duplicate_unsafe_state", decision(facts));
    }

    @Test
    public void classThreeSurvivorWithUnsafeFlagIsRefused() {
        MetadataRestoreJob.StaleRowFacts facts = classThreeFacts();
        facts.survivorStateSafe = false;
        assertEquals("duplicate_unsafe_state", decision(facts));
    }

    @Test
    public void classThreeRowWhoseSizeDisagreesWithTheDiskIsRefused() {
        MetadataRestoreJob.StaleRowFacts facts = classThreeFacts();
        facts.rowSizeMatchesDisk = false;
        assertEquals("duplicate_length_mismatch", decision(facts));
    }

    @Test
    public void classThreeSurvivorWhoseSizeDisagreesWithTheDiskIsRefused() {
        MetadataRestoreJob.StaleRowFacts facts = classThreeFacts();
        facts.survivorSizeMatchesFile = false;
        assertEquals("duplicate_length_mismatch", decision(facts));
    }

    @Test
    public void classThreeRowWhoseDiskLengthDisagreesWithTheFileIsRefused() {
        MetadataRestoreJob.StaleRowFacts facts = classThreeFacts();
        facts.diskLengthMatchesFile = false;
        assertEquals("duplicate_length_mismatch", decision(facts));
    }

    @Test
    public void classThreeRowWithoutASurvivorIsRefused() {
        MetadataRestoreJob.StaleRowFacts facts = classThreeFacts();
        facts.survivorFound = false;
        facts.survivorRowId = null;
        assertEquals("duplicate_no_survivor", decision(facts));
    }

    @Test
    public void classThreeFileReferencedByAThirdRowIsRefused() {
        MetadataRestoreJob.StaleRowFacts facts = classThreeFacts();
        facts.referencingRowIds = new ArrayList<>(Arrays.asList("14", "15563", "16001"));
        assertEquals("duplicate_file_shared", decision(facts));
    }

    @Test
    public void classThreeFileOnlyReferencedThroughAnotherRowsThumbnailIsRefused() {
        // 第三行没登记 localFile，只把自己的 thumbnailFile 指向这份文件，同样算共享。
        MetadataRestoreJob.StaleRowFacts facts = classThreeFacts();
        facts.referencingRowIds = new ArrayList<>(Arrays.asList("14", "15563", "16002"));
        assertEquals("duplicate_file_shared", decision(facts));
    }

    @Test
    public void classThreeReferenceQueryFailureIsTreatedAsShared() {
        MetadataRestoreJob.StaleRowFacts facts = classThreeFacts();
        facts.referenceQueryFailed = true;
        facts.referencingRowIds = new ArrayList<>();
        assertEquals("duplicate_file_shared", decision(facts));
    }

    @Test
    public void classThreeSurvivorWithMismatchedSha1IsRefused() {
        MetadataRestoreJob.StaleRowFacts facts = classThreeFacts();
        facts.survivorSha1Matches = false;
        assertEquals("duplicate_sha1_mismatch", decision(facts));
    }

    @Test
    public void classThreeRowWhoseFileIsMissingIsRefused() {
        MetadataRestoreJob.StaleRowFacts facts = classThreeFacts();
        facts.duplicateFilePresent = false;
        assertEquals("duplicate_missing", decision(facts));
    }

    @Test
    public void classThreeRowWithoutResolvablePathIsRefused() {
        MetadataRestoreJob.StaleRowFacts facts = classThreeFacts();
        facts.localFileResolved = false;
        assertEquals("duplicate_missing", decision(facts));
    }

    @Test
    public void classThreeRowIsRefusedWhenItIsTheOnlyRowForItsSha1() {
        MetadataRestoreJob.StaleRowFacts facts = classThreeFacts();
        facts.onlyRowForSha1 = true;
        assertEquals("stale_row_would_lose_file", decision(facts));
    }

    @Test
    public void classThreeUploadedRowIsRejected() {
        MetadataRestoreJob.StaleRowFacts facts = classThreeFacts();
        facts.hasServerId = true;
        assertEquals("stale_row_has_server_id", decision(facts));
    }

    @Test
    public void flagZeroIsStillRejectedForTheOlderClasses() {
        // 0 的放宽只属于第 3 类：第 1/2 类仍然只认 7/8。
        MetadataRestoreJob.StaleRowFacts one = classOneFacts();
        one.localFlag = 0;
        assertEquals("stale_row_state_unsafe", decision(one));
        MetadataRestoreJob.StaleRowFacts two = classTwoFacts();
        two.localFlag = 0;
        assertEquals("stale_row_state_unsafe", decision(two));
    }

    @Test
    public void duplicatePathsAreComparedIgnoringCase() {
        // 真机上 /storage/emulated/0/pictures/… 与 /Pictures/… 是同一份文件。
        assertTrue(MetadataRestoreJob.StaleRows.isSameDuplicatePath(
                "/storage/emulated/0/pictures/1637/pic.jpg",
                "/storage/emulated/0/Pictures/1637/pic.jpg"));
        assertTrue(MetadataRestoreJob.StaleRows.isSameDuplicatePath(
                "/storage/emulated/0/DCIM/Camera/IMG_1.HEIC",
                "/storage/emulated/0/DCIM/Camera/IMG_1.heic"));
        assertFalse(MetadataRestoreJob.StaleRows.isSameDuplicatePath(
                "/storage/emulated/0/Pictures/1637/pic.jpg",
                "/storage/emulated/0/Pictures/1638/pic.jpg"));
        assertFalse(MetadataRestoreJob.StaleRows.isSameDuplicatePath(null,
                "/storage/emulated/0/Pictures/1637/pic.jpg"));
        assertFalse(MetadataRestoreJob.StaleRows.isSameDuplicatePath("", ""));
    }
}
