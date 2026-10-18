package com.tongluosao.gallerysync.core;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class HeicPreviewEncoderRationalTest {
    @Test
    public void decodesUnsignedRationalsInEitherByteOrder() {
        long numerator = 1_333_333_340L;
        long denominator = 4_000_000_000L;
        double value = numerator / (double) denominator;

        assertEquals("1333333340/4000000000", HeicPreviewEncoder.rawUnsignedRational(
                encode(numerator, denominator, ByteOrder.LITTLE_ENDIAN), value));
        assertEquals("1333333340/4000000000", HeicPreviewEncoder.rawUnsignedRational(
                encode(numerator, denominator, ByteOrder.BIG_ENDIAN), value));
    }

    @Test
    public void preservesUnsignedValuesAboveSignedIntRange() {
        long numerator = 4_000_000_000L;
        long denominator = 2_999_999_996L;

        assertEquals("4000000000/2999999996", HeicPreviewEncoder.rawUnsignedRational(
                encode(numerator, denominator, ByteOrder.LITTLE_ENDIAN),
                numerator / (double) denominator));
    }

    @Test
    public void rejectsMalformedAndNonMatchingRationals() {
        assertNull(HeicPreviewEncoder.rawUnsignedRational(null, 1.0d));
        assertNull(HeicPreviewEncoder.rawUnsignedRational(new byte[7], 1.0d));
        assertNull(HeicPreviewEncoder.rawUnsignedRational(
                encode(1L, 0L, ByteOrder.LITTLE_ENDIAN), 1.0d));
        assertNull(HeicPreviewEncoder.rawUnsignedRational(
                encode(0L, 1L, ByteOrder.LITTLE_ENDIAN), 1.0d));
        assertNull(HeicPreviewEncoder.rawUnsignedRational(
                encode(1L, 2L, ByteOrder.LITTLE_ENDIAN), 0.75d));
        assertNull(HeicPreviewEncoder.rawUnsignedRational(
                encode(1L, 2L, ByteOrder.LITTLE_ENDIAN), Double.NaN));
    }

    private static byte[] encode(long numerator, long denominator, ByteOrder order) {
        return ByteBuffer.allocate(8).order(order)
                .putInt((int) numerator)
                .putInt((int) denominator)
                .array();
    }
}
