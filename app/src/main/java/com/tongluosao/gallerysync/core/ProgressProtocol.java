package com.tongluosao.gallerysync.core;

/**
 * 上传进度通道的双进程共享常量与编解码。
 *
 * <p><b>为什么是广播</b>：进度数据在相册进程（上传线程跑在 {@code com.miui.gallery} 里），
 * 界面在模块 App 进程，而 libxposed 的 {@code XposedService} 没有任何推送/订阅 API
 * —— 它的全部方法里只有 {@code getRemotePreferences}（App→相册，且 App 侧
 * {@code computeIfAbsent} 永久缓存、没有刷新 API）和 {@code openRemoteFile/listRemoteFiles}
 * （方向是「App 写、注入进程只读」）。{@code state.json} 又落在
 * {@code /data/data/com.miui.gallery/files/} 下，模块 App 跨 UID 无 root 读不到。
 * 所以只能自建一条单向通道。
 *
 * <p><b>为什么用定向广播而不是别的</b>：{@code setPackage} 之后只有本模块 App 收得到，
 * 且 App 没运行时（没有动态 receiver）广播被静默丢弃、**不会把 App 拉起来**
 * —— 这正是想要的：进度页只有前台才有意义，模块 App 平时不该因为备份而被唤醒。
 *
 * <p>本类必须只依赖 platform（{@code org.json} 是 Android 框架自带的），因为它会被注入
 * 到相册进程里执行。
 */
public final class ProgressProtocol {

    /** 模块 App 的包名，也是广播的唯一目标 */
    public static final String MODULE_PACKAGE = "com.tongluosao.gallerysync";

    /** 进度广播的 action */
    public static final String ACTION = MODULE_PACKAGE + ".action.UPLOAD_PROGRESS";

    /** 唯一 extra：整帧的紧凑 JSON 字符串 */
    public static final String EXTRA_PAYLOAD = "payload";

    /**
     * 协议版本。客户端不认识的版本直接忽略整帧 —— 新旧两个版本同时在跑时，
     * 宁可让界面停在上一帧，也不要按错字段解析出一堆假数据。
     */
    public static final int VERSION = 1;

    /** {@code state} 的三个取值 */
    public static final String STATE_UPLOADING = "uploading";
    public static final String STATE_IDLE = "idle";
    /** WebDAV 不可用（连续失败到阈值）：队列仍在退避重试，界面要说明这批不会去小米云 */
    public static final String STATE_UNHEALTHY = "unhealthy";

    /** 上传方向。下载还没做，但字段先留着，将来加下载不用改通道 */
    public static final String DIR_UP = "up";
    public static final String DIR_DOWN = "down";

    private ProgressProtocol() {
    }
}
