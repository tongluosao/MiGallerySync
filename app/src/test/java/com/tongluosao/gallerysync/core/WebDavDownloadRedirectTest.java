package com.tongluosao.gallerysync.core;

import org.junit.Test;
import java.io.IOException;
import java.net.URL;
import static org.junit.Assert.*;

public class WebDavDownloadRedirectTest {
    @Test public void relativeRedirectKeepsOriginAndSignature() throws Exception {
        URL origin = new URL("https://dav.example/dav/a.jpg");
        URL next = WebDavClient.downloadRedirect(origin, "/signed/a.jpg?token=a%2Bb");
        assertTrue(WebDavClient.sameOrigin(origin, next));
        assertEquals("/signed/a.jpg?token=a%2Bb", next.getFile());
        assertTrue(WebDavClient.sameOrigin(origin, new URL("https://DAV.example:443/a")));
    }

    @Test public void originIncludesPortAndProtocol() throws Exception {
        URL origin = new URL("http://localhost:1200/dav/a");
        assertFalse(WebDavClient.sameOrigin(origin, new URL("http://localhost:1201/a")));
        assertFalse(WebDavClient.sameOrigin(origin, new URL("https://localhost:1200/a")));
        assertFalse(WebDavClient.sameOrigin(origin, new URL("http://other.example:1200/a")));
    }

    @Test public void rejectsDowngradeCredentialsAndHeaderInjection() throws Exception {
        URL origin = new URL("https://dav.example/a");
        for (String value : new String[]{"http://storage.example/a", "file:///tmp/a",
                "https://user:secret@storage.example/a", "/a\r\nInjected: yes", "/bad path"}) {
            try {
                WebDavClient.downloadRedirect(origin, value);
                fail("accepted unsafe redirect");
            } catch (IOException expected) { }
        }
    }
}
