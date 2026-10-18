package com.tongluosao.gallerysync.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public class WebDavDirectoryResolutionTest {

    @Test
    public void exactDirectoryNameWinsOverOtherCaseVariants() {
        List<WebDavClient.RemoteEntry> entries = Arrays.asList(
                dir("Pictures"), dir("pictures"));

        assertEquals("pictures", WebDavClient.matchDirectory(entries, "pictures").name);
    }

    @Test
    public void uniqueIgnoreCaseDirectoryMatchReturnsItsRealName() {
        WebDavClient.DirectoryMatch match = WebDavClient.matchDirectory(
                Collections.singletonList(dir("Pictures")), "pictures");

        assertEquals("Pictures", match.name);
        assertFalse(match.missing);
    }

    @Test
    public void ambiguousOrUnknownDirectoryIsNeverReportedMissing() {
        WebDavClient.DirectoryMatch ambiguous = WebDavClient.matchDirectory(
                Arrays.asList(dir("Pictures"), dir("PICTURES")), "pictures");
        WebDavClient.DirectoryMatch unknown = WebDavClient.matchDirectory(null, "pictures");

        assertNull(ambiguous.name);
        assertFalse(ambiguous.missing);
        assertNull(unknown.name);
        assertFalse(unknown.missing);
    }

    @Test
    public void onlyAnEmptyCompleteParentListingConfirmsMissing() {
        WebDavClient.DirectoryMatch missing = WebDavClient.matchDirectory(
                Collections.emptyList(), "pictures");
        WebDavClient.DirectoryMatch blockedByFile = WebDavClient.matchDirectory(
                Collections.singletonList(new WebDavClient.RemoteEntry("pictures", false, 1, 0)),
                "pictures");

        assertTrue(missing.missing);
        assertFalse(blockedByFile.missing);
        assertNull(blockedByFile.name);
    }

    @Test
    public void directoryResolutionKeepsRequestedFileBasenameExact() {
        assertEquals("MiGallerySync/Pictures/IMG_One.JPG",
                WebDavClient.joinPath("MiGallerySync/Pictures", "IMG_One.JPG"));
    }

    private static WebDavClient.RemoteEntry dir(String name) {
        return new WebDavClient.RemoteEntry(name, true, -1, 0);
    }
}
