package com.tongluosao.gallerysync.core;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class SlimHookPreviewTimeTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void videoPosterInheritsOriginalModificationTime() throws IOException {
        File original = temporaryFolder.newFile("video.mp4");
        File preview = temporaryFolder.newFile("poster.jpg");
        long expected = 1_700_000_000_000L;
        Files.setLastModifiedTime(original.toPath(),
                java.nio.file.attribute.FileTime.fromMillis(expected));

        com.tongluosao.gallerysync.hook.PreviewFileTime.copyModifiedTime(original, preview);

        assertEquals(expected, preview.lastModified());
    }

    @Test
    public void zeroSourceTimeLeavesPosterTimeUnchanged() throws IOException {
        File original = temporaryFolder.newFile("video-without-time.mp4");
        File preview = temporaryFolder.newFile("poster-with-time.jpg");
        long originalTime = 0L;
        if (!original.setLastModified(originalTime)) {
            return;
        }
        long expected = 1_600_000_000_000L;
        Files.setLastModifiedTime(preview.toPath(),
                java.nio.file.attribute.FileTime.fromMillis(expected));

        com.tongluosao.gallerysync.hook.PreviewFileTime.copyModifiedTime(original, preview);

        assertEquals(expected, preview.lastModified());
        assertFalse("precondition: synthetic zero time was preserved",
                original.lastModified() != 0L);
    }
}
