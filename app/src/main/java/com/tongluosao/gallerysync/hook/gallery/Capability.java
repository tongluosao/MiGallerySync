package com.tongluosao.gallerysync.hook.gallery;

import java.util.Locale;

/**
 * 相册版本能提供的"能力"清单：某个版本到底支持哪几件事，由该版本的 {@link Profile} 声明。
 *
 * <p>这里只是枚举名，不代表当前相册一定有对应的类/方法 —— 真正的存在性由 Profile 里的
 * {@link Ref} 与 {@link Profile#capability} 的工厂决定。新增能力时先在这里加一项，
 * 否则档案里没地方挂工厂。
 */
public enum Capability {

    /** 同步队列项：把云表一行读成"要上传的文件"，对应 RequestCloudItem/DBItem 那一套。 */
    SYNC_ITEMS("同步队列项"),
    /** 云表行读写：provider 的 cloud 表查询与列名。 */
    CLOUD_ROWS("云表行读写"),
    /** 空间优化项：SlimController 处理的那条链路。 */
    SLIM_ITEM("空间优化项"),
    /** 在线视频播放：视频直链/缓存策略所在的方法。 */
    VIDEO_ONLINE("在线视频播放"),
    /** 同步状态文案：列表里"已同步/未同步"那段文字。 */
    SYNC_STATUS_TEXT("同步状态文案"),
    /** 备份列表：设置页里那份待备份清单。 */
    BACKUP_LIST("备份列表"),
    /** 回收站队列：删除后的入队/出队路径。 */
    TRASH_QUEUE("回收站队列"),
    /** 原生下载策略：宿主自己决定"要不要下原图"的那段判断。 */
    NATIVE_DOWNLOAD_POLICY("原生下载策略");

    private final String mLabel;

    Capability(String label) {
        mLabel = label;
    }

    /** 人类可读的中文名字，给诊断页与日志用。 */
    public String label() {
        return mLabel;
    }

    /**
     * 结构化报告里的键：小写枚举名，保持 ASCII 且稳定，方便 grep 与脚本解析。
     * 用 {@link Locale#ROOT} 是防土耳其语环境下 "I" 被折成 "ı"。
     */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }
}
