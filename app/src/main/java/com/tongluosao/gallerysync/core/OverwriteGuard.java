package com.tongluosao.gallerysync.core;

/**
 * 上传前的最后一道闸门：本地这份文件能不能覆盖远端**同名**的那个对象。
 *
 * <p>为什么需要它（2026-11 事故）：上传链路原来的判据只有"同路径"——远端有同名对象、
 * 字节不同就照常 PUT 覆盖。可一旦本地那张照片被系统/云相册换成了**低清高质量图**
 * （长度远小于原图），而相册又把它标脏重新入队，一次普通的 PUT 就会把 WebDAV 上那份
 * 完整原图永久盖掉：远端从此只剩一张缩略图，且再没有任何副本能还原。
 *
 * <p>所以覆盖不再默认放行，而是要求"这次覆盖说得通"：
 * <ul>
 *   <li>远端没有同名对象（或大小问不出来）→ 谈不上覆盖，照传；</li>
 *   <li>远端长度与本地相同 → 是同一份大小的替换，交给既有的字节校验去判，不在这里拦；</li>
 *   <li>远端长度与本地不同 → <b>只有</b>当本地这份就是注册表里登记的那条原图时才允许覆盖
 *       （正常的"原图替换原图"，以及远端那份被截断/损坏时的修复）；否则拒绝。</li>
 * </ul>
 *
 * <p>纯函数、不碰 Android API，规则可以逐条钉进单测。
 */
public final class OverwriteGuard {

    /** 这一项该不该把字节发出去 */
    public enum Decision {
        /** 照常上传（含覆盖写） */
        PROCEED,
        /** 拒绝上传：本地这份不是登记过的那条原图，而远端同名对象的长度与它不同 */
        REFUSE_NOT_REGISTERED_ORIGINAL
    }

    private OverwriteGuard() {
    }

    /**
     * 判定一次上传能不能落到远端同名对象上。
     *
     * <p>判定顺序就是安全边界，改顺序等于改语义：
     * <ol>
     *   <li>{@code !remoteExists} → 放行。没有同名对象，PUT 只是新建；</li>
     *   <li>{@code remoteLength <= 0} → 放行。"问不出来"（0 / 负数）绝不等于"长度不同"，
     *       证明不了有风险就不许拦 —— 拦错的代价是这一项永远传不上去；</li>
     *   <li>{@code localLength <= 0} → 放行。本地长度不可用时这里没有可比的东西
     *       （空文件 / 全 0 载荷在别处已经拦下），不在这里做二次判定；</li>
     *   <li>{@code remoteLength == localLength} → 放行。长度相同说明"大小没变"，
     *       是不是同一份字节由既有的 skip / 回读校验负责，与覆盖守卫无关；</li>
     *   <li>长度不同：{@code localIsRegisteredOriginal} 为真才放行（原图替换原图，
     *       或远端那份被截断后的修复），否则拒绝。</li>
     * </ol>
     *
     * @param remoteExists              远端是否<b>确实</b>有同名对象；"问不出来"必须传 false
     * @param remoteLength              远端对象长度；&lt;= 0 表示存在但大小未知（含 -1 未知）
     * @param localLength               本地待传文件长度；&lt;= 0 表示不可用
     * @param localIsRegisteredOriginal 本地这份是不是注册表里那条原图（远端路径 + 长度，
     *                                  登记了摘要时还包括 SHA-256）
     */
    public static Decision decide(boolean remoteExists, long remoteLength, long localLength,
                                  boolean localIsRegisteredOriginal) {
        if (!remoteExists) {
            return Decision.PROCEED;
        }
        if (remoteLength <= 0L) {
            return Decision.PROCEED;
        }
        if (localLength <= 0L) {
            return Decision.PROCEED;
        }
        if (remoteLength == localLength) {
            return Decision.PROCEED;
        }
        return localIsRegisteredOriginal
                ? Decision.PROCEED
                : Decision.REFUSE_NOT_REGISTERED_ORIGINAL;
    }
}
