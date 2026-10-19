package com.tongluosao.gallerysync.hook;

import java.io.File;
import java.io.IOException;

/** Keeps Gallery's preview timestamp aligned with the source file it replaces. */
public final class PreviewFileTime {
    private PreviewFileTime() {
    }

    public static void copyModifiedTime(File source, File target) throws IOException {
        long modified = source.lastModified();
        if (modified > 0L && !target.setLastModified(modified)) {
            throw new IOException("cannot preserve preview modification time");
        }
    }
}
