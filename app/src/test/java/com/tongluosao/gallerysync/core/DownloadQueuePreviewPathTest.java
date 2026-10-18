package com.tongluosao.gallerysync.core;

import org.junit.Test;
import java.io.File;
import static org.junit.Assert.*;

public class DownloadQueuePreviewPathTest {
    @Test public void sameStemDifferentFormatsHaveDistinctPreviews() {
        File jpeg = DownloadQueue.previewPathFor(new File("album/photo.jpg"));
        File png = DownloadQueue.previewPathFor(new File("album/photo.png"));
        assertNotEquals(jpeg, png);
        assertEquals("photo.jpg.hq.jpg", jpeg.getName());
        assertEquals("photo.png.hq.jpg", png.getName());
        assertEquals(new File("album"), png.getParentFile());
    }
}
