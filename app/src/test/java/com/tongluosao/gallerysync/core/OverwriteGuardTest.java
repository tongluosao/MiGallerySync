package com.tongluosao.gallerysync.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * 覆盖闸门的判定：什么时候允许覆盖远端同名对象，什么时候必须拒绝。
 *
 * <p>事故（2026-11）：本地照片被系统/云相册换成低清高质量图（长度远小于原图），
 * 相册把它标脏重新入队 → 老判据只有"同路径" → 一次 PUT 就把远端完整原图盖掉。
 * 所以这里把"只有登记过的那条原图才配覆盖长度不同的远端对象"钉成断言。
 */
public class OverwriteGuardTest {

    /** 远端没有同名对象：PUT 只是新建，谈不上覆盖 */
    @Test
    public void absentRemoteProceeds() {
        assertEquals(OverwriteGuard.Decision.PROCEED,
                OverwriteGuard.decide(false, -1L, 5_000L, false));
        assertEquals(OverwriteGuard.Decision.PROCEED,
                OverwriteGuard.decide(false, 4_096L, 5_000L, false));
    }

    /** 长度相同：交给既有的字节校验，覆盖闸门不管 */
    @Test
    public void sameLengthProceeds() {
        assertEquals(OverwriteGuard.Decision.PROCEED,
                OverwriteGuard.decide(true, 5_000L, 5_000L, false));
        assertEquals(OverwriteGuard.Decision.PROCEED,
                OverwriteGuard.decide(true, 5_000L, 5_000L, true));
    }

    /** 长度不同、本地这份又不是登记过的原图：这就是事故那一支，必须拒绝 */
    @Test
    public void differentLengthNotRegisteredRefuses() {
        // 远端是完整原图（120 KB），本地只剩低清图（18 KB）
        assertEquals(OverwriteGuard.Decision.REFUSE_NOT_REGISTERED_ORIGINAL,
                OverwriteGuard.decide(true, 120_000L, 18_000L, false));
        // 反向也一样：本地比远端大，同样不是"原图替换原图"
        assertEquals(OverwriteGuard.Decision.REFUSE_NOT_REGISTERED_ORIGINAL,
                OverwriteGuard.decide(true, 18_000L, 120_000L, false));
    }

    /** 长度不同但本地这份就是登记的那条原图：允许覆盖（原图替换原图 / 修复被截断的远端） */
    @Test
    public void differentLengthButRegisteredOriginalProceeds() {
        assertEquals(OverwriteGuard.Decision.PROCEED,
                OverwriteGuard.decide(true, 120_000L, 18_000L, true));
    }

    /** 登记长度与本地不同（= 本地不是那条原图）：拒绝。调用方传进来的就是 false */
    @Test
    public void registeredLengthMismatchRefuses() {
        assertEquals(OverwriteGuard.Decision.REFUSE_NOT_REGISTERED_ORIGINAL,
                OverwriteGuard.decide(true, 120_000L, 18_000L, false));
    }

    /** 远端大小未知（0 / 负数）：证明不了"长度不同"，不许拦 —— 拦错等于这一项永远传不上去 */
    @Test
    public void unknownRemoteLengthProceeds() {
        assertEquals(OverwriteGuard.Decision.PROCEED,
                OverwriteGuard.decide(true, 0L, 18_000L, false));
        assertEquals(OverwriteGuard.Decision.PROCEED,
                OverwriteGuard.decide(true, -1L, 18_000L, false));
        assertEquals(OverwriteGuard.Decision.PROCEED,
                OverwriteGuard.decide(true, -120_000L, 18_000L, false));
    }

    /** 本地长度不可用（0 / 负数）：空文件与坏载荷在别处已经拦下，这里不再二次判定 */
    @Test
    public void unusableLocalLengthProceeds() {
        assertEquals(OverwriteGuard.Decision.PROCEED,
                OverwriteGuard.decide(true, 120_000L, 0L, false));
        assertEquals(OverwriteGuard.Decision.PROCEED,
                OverwriteGuard.decide(true, 120_000L, -1L, false));
    }

    /** 远端 0 字节对象（上次残留的空文件）：大小未知按放行处理，不能因此把真图拦死 */
    @Test
    public void zeroRemoteObjectProceeds() {
        assertEquals(OverwriteGuard.Decision.PROCEED,
                OverwriteGuard.decide(true, 0L, 0L, false));
    }
}
