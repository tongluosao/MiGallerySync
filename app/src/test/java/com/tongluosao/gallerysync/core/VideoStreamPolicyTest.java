package com.tongluosao.gallerysync.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class VideoStreamPolicyTest {
    private static final int TAIL_BYTES = 4 * 1024 * 1024;
    private static final long MB = 1024L * 1024L;
    private static final long MIN_WINDOW = 4L * MB;
    private static final long MAX_WINDOW = 64L * MB;
    private static final long START_WINDOW = 8L * MB;

    @Test
    public void playheadZeroUsesAtLeastStartWindow() {
        long window = VideoStreamPolicy.windowBytes(10L * 1024L, 0L, MIN_WINDOW, MAX_WINDOW,
                START_WINDOW);
        assertTrue(window >= START_WINDOW);
        assertEquals(START_WINDOW, window);
    }

    @Test
    public void fastPlaybackIsClampedToMaxWindow() {
        long window = VideoStreamPolicy.windowBytes(MB, MB, MIN_WINDOW, MAX_WINDOW,
                START_WINDOW);
        assertEquals(MAX_WINDOW, window);
    }

    @Test
    public void slowPlaybackIsClampedToMinWindow() {
        long window = VideoStreamPolicy.windowBytes(10L * 1024L, MB, MIN_WINDOW, MAX_WINDOW,
                START_WINDOW);
        assertEquals(MIN_WINDOW, window);
    }

    @Test
    public void windowFollowsBitrateInsideRange() {
        // 0.2MB/s × 120s ≈ 24MB，介于 4MB 和 64MB 之间，不做夹取
        long bytesPerSecond = MB / 5L;
        long window = VideoStreamPolicy.windowBytes(bytesPerSecond, MB, MIN_WINDOW, MAX_WINDOW,
                START_WINDOW);
        assertEquals(bytesPerSecond * VideoStreamPolicy.WINDOW_SECONDS, window);
        assertTrue(window > MIN_WINDOW && window < MAX_WINDOW);
    }

    @Test
    public void tailCoverageMatchesBoundary() {
        long length = 201626640L;
        long tailStart = VideoStreamPolicy.tailStart(length, TAIL_BYTES);
        assertEquals(length - TAIL_BYTES, tailStart);
        assertTrue(VideoStreamPolicy.coveredByTail(tailStart, length, TAIL_BYTES));
        assertFalse(VideoStreamPolicy.coveredByTail(tailStart - 1L, length, TAIL_BYTES));
        assertFalse(VideoStreamPolicy.coveredByTail(0L, length, TAIL_BYTES));
    }

    @Test
    public void fileSmallerThanTailIsFullyCovered() {
        long length = 1024L * 1024L;
        assertEquals(0L, VideoStreamPolicy.tailStart(length, TAIL_BYTES));
        assertTrue(VideoStreamPolicy.coveredByTail(0L, length, TAIL_BYTES));
        assertTrue(VideoStreamPolicy.coveredByTail(length - 1L, length, TAIL_BYTES));
    }

    @Test
    public void unknownLengthHasNoTailRegion() {
        assertEquals(0L, VideoStreamPolicy.tailStart(0L, TAIL_BYTES));
        assertEquals(12345L, VideoStreamPolicy.tailStart(12345L, 0));
    }

    @Test
    public void localServeRequiresStrictlyMoreBytesThanStart() {
        assertFalse(VideoStreamPolicy.canServeFromLocal(1000L, 1000L));
        assertTrue(VideoStreamPolicy.canServeFromLocal(1001L, 1000L));
        assertTrue(VideoStreamPolicy.canServeFromLocal(1000L, 0L));
    }

    @Test
    public void resumeDecisionOnlyLostWhenShorter() {
        assertEquals(VideoStreamPolicy.RESUME,
                VideoStreamPolicy.resumeDecision(64L * MB, 32L * MB));
        assertEquals(VideoStreamPolicy.RESUME,
                VideoStreamPolicy.resumeDecision(32L * MB, 32L * MB));
        assertEquals(VideoStreamPolicy.LOST,
                VideoStreamPolicy.resumeDecision(32L * MB - 1L, 32L * MB));
        assertEquals(VideoStreamPolicy.LOST, VideoStreamPolicy.resumeDecision(-1L, 0L));
    }

    @Test
    public void clampKeepsValuesInsideRange() {
        assertEquals(5L, VideoStreamPolicy.clamp(5L, 1L, 10L));
        assertEquals(1L, VideoStreamPolicy.clamp(-5L, 1L, 10L));
        assertEquals(10L, VideoStreamPolicy.clamp(50L, 1L, 10L));
    }
}
