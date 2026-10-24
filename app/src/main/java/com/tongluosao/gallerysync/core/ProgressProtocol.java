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
     * 分项进度的 extra：一个 JSON **数组**字符串（不是帧里的字段），与 {@link #EXTRA_PAYLOAD}
     * 同一次广播一起发。
     *
     * <p>为什么单独一个 extra 而不是塞进帧里：帧的字段名（{@code active}/{@code pl}）已经被
     * 上传进度页用满，而分项是"逐项从开始到结束的流水"这种完全不同形状的数据；单独一个
     * extra 让老的接收端（不认识这个键）照常读帧，不会因为多一个字段就整帧弃用。
     *
     * <p>数组元素：{@code {"id":"…","name":"…","state":"running|done|failed","ms":1234,"reason":"…"}}，
     * 最新在前、最多 {@link #ITEMS_MAX} 条。缺字段/多字段都由接收端容错，不认识的键直接忽略。
     */
    public static final String EXTRA_ITEMS = "items";

    /** 分项列表最多带几条：界面只看最近一屏，再多只是白扛 JSON */
    public static final int ITEMS_MAX = 200;

    /** 分项状态：正在处理这一项 */
    public static final String ITEM_RUNNING = "running";

    /** 分项状态：这一项处理完了 */
    public static final String ITEM_DONE = "done";

    /** 分项状态：这一项失败/跳过了（原因在 {@link #ITEM_KEY_REASON}） */
    public static final String ITEM_FAILED = "failed";

    /** 分项元素的字段名 */
    public static final String ITEM_KEY_ID = "id";
    public static final String ITEM_KEY_NAME = "name";
    public static final String ITEM_KEY_STATE = "state";
    public static final String ITEM_KEY_MS = "ms";
    public static final String ITEM_KEY_REASON = "reason";

    /**
     * 反向命令广播的 action：模块 App → 相册进程。
     *
     * <p>方向与 {@link #ACTION} 相反。相册进程里那条指令（重置已上传标记）必须由**相册
     * 自己**去写它的 provider —— 模块 App 没有那个 provider 的写权限，而相册进程里注入的
     * 代码用的是相册的 UID，天然满足。
     *
     * <p>为什么不用 {@code XposedService}：它只有 {@code getRemotePreferences} 这一类
     * "App 写、注入进程读"的接口，没有"让注入进程执行一个动作"的通道，所以只能再开一条广播。
     *
     * <p><b>定向的包名是相册、不是本模块</b>：接收器注册在**相册进程**里
     * （{@code GalleryHooks.ensureCommandReceiver}），所以必须 {@code setPackage(相册包名)}。
     * 写成 {@code setPackage(本模块)} 就只投给模块自己的进程 —— 那里根本没有注册这个
     * action 的接收器，广播会被安静丢掉，表现成"点了按钮什么都没发生"。
     *
     * <p>相册没在跑时广播同样被丢弃，这是可接受的（那说明备份此刻也没在跑）。
     */
    public static final String ACTION_COMMAND = MODULE_PACKAGE + ".action.COMMAND";

    /** 命令广播要投递到的进程：接收器注册在相册进程里，不是模块自己的进程 */
    public static final String TARGET_PACKAGE = "com.miui.gallery";

    /** 命令名：把当前账号的"已上传"标记全部重置回未上传 */
    public static final String CMD_RESET_UPLOADED = "reset_uploaded";

    /** Restore metadata-corrupted originals listed in Gallery's private request file. */
    public static final String CMD_RESTORE_METADATA_ORIGINALS = "restore_metadata_originals";

    /**
     * 启动模块自己的空间优化队列。相册进程收到后自己查候选（{@code SlimCandidates}）、
     * 起工作线程池；进度走 {@link #STATE_*} 那套广播回模块 App 的优化页。
     */
    public static final String CMD_SLIM_START = "slim_start";

    /** 停止空间优化队列：正在处理的那张做完就收尾，不再领新任务 */
    public static final String CMD_SLIM_STOP = "slim_stop";

    /** 优化并发路数（1..4），随 {@link #CMD_SLIM_START} 一起下发 */
    public static final String EXTRA_CONCURRENCY = "concurrency";

    /** 命令广播的唯一 extra：命令名 */
    public static final String EXTRA_COMMAND = "command";

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

    /**
     * 任务方向。
     *
     * <p>上传项是该字段缺省时的取值（老版本相册进程发来的帧没有它，客户端按上传处理）；
     * 下载项显式带 {@link #DIR_DOWN}，进度页据此把文案从"上传"改成"下载"。
     */
    public static final String DIR_UP = "up";
    public static final String DIR_DOWN = "down";

    private ProgressProtocol() {
    }
}
