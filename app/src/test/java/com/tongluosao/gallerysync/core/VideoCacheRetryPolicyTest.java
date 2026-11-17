package com.tongluosao.gallerysync.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.FileNotFoundException;
import java.io.IOException;

import org.junit.Test;

public class VideoCacheRetryPolicyTest {

    @Test
    public void retriesWhenDirExistsButPartMissing() {
        assertTrue(VideoCacheRetryPolicy.shouldRetryFirstChunk(0, false, true));
    }

    @Test
    public void retriesOnSecondAttempt() {
        assertTrue(VideoCacheRetryPolicy.shouldRetryFirstChunk(1, false, true));
    }

    @Test
    public void stopsAtAttemptLimit() {
        assertFalse(VideoCacheRetryPolicy.shouldRetryFirstChunk(2, false, true));
        assertFalse(VideoCacheRetryPolicy.shouldRetryFirstChunk(3, false, false));
    }

    @Test
    public void retriesWhenCacheDirMissing() {
        assertTrue(VideoCacheRetryPolicy.shouldRetryFirstChunk(0, true, false));
    }

    @Test
    public void doesNotRetryWhenPartAndDirExist() {
        assertFalse(VideoCacheRetryPolicy.shouldRetryFirstChunk(0, true, true));
    }

    @Test
    public void fileNotFoundIsRecoverable() {
        assertTrue(VideoCacheRetryPolicy.isRecoverableIoFailure(new FileNotFoundException()));
        assertTrue(VideoCacheRetryPolicy.isRecoverableIoFailure(new FileNotFoundException(
                "/data/cache/video-play/a.mp4.part: open failed: ENOENT (No such file)")));
    }

    @Test
    public void plainIoExceptionIsNotRecoverable() {
        assertFalse(VideoCacheRetryPolicy.isRecoverableIoFailure(new IOException("x")));
        assertFalse(VideoCacheRetryPolicy.isRecoverableIoFailure(null));
    }

    @Test
    public void enoentMessageIsRecoverable() {
        assertTrue(VideoCacheRetryPolicy.isRecoverableIoFailure(
                new IOException("open failed: ENOENT (No such file or directory)")));
        assertTrue(VideoCacheRetryPolicy.isRecoverableIoFailure(
                new IOException("wrapped", new FileNotFoundException("No such file"))));
    }
}
