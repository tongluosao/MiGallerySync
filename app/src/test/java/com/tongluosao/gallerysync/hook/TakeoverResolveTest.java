package com.tongluosao.gallerysync.hook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * takeover 的远端路径解析规则（纯函数）：文件名 + 字节数 → 唯一的远端路径。
 *
 * <p>平板 3.6 的云端行 {@code localFile}/{@code albumId} 都是空的，这条规则是唯一能证明
 * “远端那一份就是这张照片”的路径，所以三条结论各钉一条：
 * <ul>
 *   <li>同名、同字节数、只有一份 → {@code MATCH}，路径就是那一份；</li>
 *   <li>同名同字节数出现多份 → {@code AMBIGUOUS}，不猜；</li>
 *   <li>没有同名（或同名但字节数不同）→ {@code MISSING}，不登记。</li>
 * </ul>
 */
public final class TakeoverResolveTest {

    private static final long SIZE = 4096L;

    @Test
    public void singleHitWithSameSizeMatches() {
        MetadataRestoreJob.RemoteResolve resolved = MetadataRestoreJob.resolveRemotePath(
                "IMG_0001.jpg", SIZE,
                index(hit("/backup/2024-05/IMG_0001.jpg", SIZE)));
        assertSame(MetadataRestoreJob.RemoteResolve.Status.MATCH, resolved.status);
        assertEquals("/backup/2024-05/IMG_0001.jpg", resolved.remotePath);
    }

    @Test
    public void sameNameWithDifferentSizeIsMissing() {
        MetadataRestoreJob.RemoteResolve resolved = MetadataRestoreJob.resolveRemotePath(
                "IMG_0001.jpg", SIZE,
                index(hit("/backup/2024-05/IMG_0001.jpg", SIZE + 1L)));
        assertSame(MetadataRestoreJob.RemoteResolve.Status.MISSING, resolved.status);
        assertNull(resolved.remotePath);
    }

    @Test
    public void twoHitsWithSameNameAndSizeAreAmbiguous() {
        MetadataRestoreJob.RemoteResolve resolved = MetadataRestoreJob.resolveRemotePath(
                "IMG_0001.jpg", SIZE,
                index(hit("/backup/2024-05/IMG_0001.jpg", SIZE),
                        hit("/backup/old/IMG_0001.jpg", SIZE)));
        assertSame(MetadataRestoreJob.RemoteResolve.Status.AMBIGUOUS, resolved.status);
        assertNull(resolved.remotePath);
    }

    @Test
    public void unknownNameIsMissing() {
        MetadataRestoreJob.RemoteResolve resolved = MetadataRestoreJob.resolveRemotePath(
                "IMG_0002.jpg", SIZE,
                index(hit("/backup/2024-05/IMG_0001.jpg", SIZE)));
        assertSame(MetadataRestoreJob.RemoteResolve.Status.MISSING, resolved.status);
    }

    @Test
    public void emptyIndexIsMissing() {
        MetadataRestoreJob.RemoteResolve resolved = MetadataRestoreJob.resolveRemotePath(
                "IMG_0001.jpg", SIZE, Collections.emptyMap());
        assertSame(MetadataRestoreJob.RemoteResolve.Status.MISSING, resolved.status);
        assertNull(resolved.remotePath);
    }

    @Test
    public void sameNameDifferentSizesPicksTheMatchingOne() {
        // 同一张照片传过两个版本：只有字节数对得上的那一份能证明身份，不算多义
        MetadataRestoreJob.RemoteResolve resolved = MetadataRestoreJob.resolveRemotePath(
                "IMG_0001.jpg", SIZE,
                index(hit("/backup/2024-05/IMG_0001.jpg", SIZE),
                        hit("/backup/old/IMG_0001.jpg", SIZE - 1L)));
        assertSame(MetadataRestoreJob.RemoteResolve.Status.MATCH, resolved.status);
        assertEquals("/backup/2024-05/IMG_0001.jpg", resolved.remotePath);
    }

    @Test
    public void blankNameOrNullIndexIsMissing() {
        Map<String, List<MetadataRestoreJob.RemoteHit>> index =
                index(hit("/backup/2024-05/IMG_0001.jpg", SIZE));
        assertSame(MetadataRestoreJob.RemoteResolve.Status.MISSING,
                MetadataRestoreJob.resolveRemotePath(null, SIZE, index).status);
        assertSame(MetadataRestoreJob.RemoteResolve.Status.MISSING,
                MetadataRestoreJob.resolveRemotePath("", SIZE, index).status);
        assertSame(MetadataRestoreJob.RemoteResolve.Status.MISSING,
                MetadataRestoreJob.resolveRemotePath("IMG_0001.jpg", SIZE, null).status);
    }

    private static MetadataRestoreJob.RemoteHit hit(String remotePath, long size) {
        return new MetadataRestoreJob.RemoteHit(remotePath, size);
    }

    /** 与生产代码一致：索引按文件名归组，路径在值里。 */
    private static Map<String, List<MetadataRestoreJob.RemoteHit>> index(
            MetadataRestoreJob.RemoteHit... hits) {
        Map<String, List<MetadataRestoreJob.RemoteHit>> index = new HashMap<>();
        for (MetadataRestoreJob.RemoteHit hit : hits) {
            int slash = hit.remotePath.lastIndexOf('/');
            String name = slash < 0 ? hit.remotePath : hit.remotePath.substring(slash + 1);
            List<MetadataRestoreJob.RemoteHit> bucket = index.get(name);
            if (bucket == null) {
                bucket = new ArrayList<>();
                index.put(name, bucket);
            }
            bucket.add(hit);
        }
        return index;
    }
}
