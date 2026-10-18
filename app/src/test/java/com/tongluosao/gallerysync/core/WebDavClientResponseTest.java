package com.tongluosao.gallerysync.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.StringReader;

import org.junit.Test;

public class WebDavClientResponseTest {

    @Test
    public void chunkedBodyIsCompleteOnlyAfterTerminalChunkAndTrailerBoundary() {
        WebDavClient.Drain complete = WebDavClient.readChunkedChecked(
                new BufferedReader(new StringReader("3\r\nabc\r\n0\r\n\r\n")), 32);

        assertTrue(complete.clean);
        assertEquals("abc", complete.text);

        WebDavClient.Drain missingTerminator = WebDavClient.readChunkedChecked(
                new BufferedReader(new StringReader("3\r\nabc\r\n")), 32);
        assertFalse(missingTerminator.clean);

        WebDavClient.Drain truncatedChunk = WebDavClient.readChunkedChecked(
                new BufferedReader(new StringReader("5\r\nhe")), 32);
        assertFalse(truncatedChunk.clean);
    }

    @Test
    public void multistatusMustHaveAResponseAndClosingRoot() {
        String complete = "<D:multistatus xmlns:D=\"DAV:\"><D:response>"
                + "<D:href>/dav/file.jpg</D:href></D:response></D:multistatus>";
        String truncated = "<D:multistatus xmlns:D=\"DAV:\"><D:response>"
                + "<D:href>/dav/file.jpg</D:href></D:response>";
        String noResponse = "<D:multistatus xmlns:D=\"DAV:\"></D:multistatus>";

        assertTrue(WebDavClient.hasCompleteMultistatus(complete));
        assertFalse(WebDavClient.hasCompleteMultistatus(truncated));
        assertFalse(WebDavClient.hasCompleteMultistatus(noResponse));
    }

    @Test
    public void onlyPositiveExactSizeAllowsSkipWhenDirectoryListingIsUnavailable() {
        assertTrue(WebDavClient.hasExactSize(614494L, 614494L));
        assertFalse(WebDavClient.hasExactSize(614493L, 614494L));
        assertFalse(WebDavClient.hasExactSize(-1L, 614494L)); // explicit 404
        assertFalse(WebDavClient.hasExactSize(0L, 614494L)); // unknown / missing length
        assertFalse(WebDavClient.hasExactSize(0L, 0L)); // empty local files are never skipped
    }
}
