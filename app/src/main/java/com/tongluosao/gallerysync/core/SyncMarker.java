package com.tongluosao.gallerysync.core;

/**
 * 上传成功并确认在服务器上落盘后，补写相册那一行的"已同步"标记。
 *
 * <p>为什么需要：相册判断"要不要上传"看的是 cloud 表的 localFlag，而它只在小米云真的传成功之后
 * 才把 localFlag 写成 0。替换模式把上传截在入队口，这条标记就永远不发生，
 * 于是同一批照片每轮同步都会被重新枚举一遍。
 *
 * <p>实现方必须自己吞掉异常：标记写入失败只该让相册多枚举一次，不能把已完成的上传判成失败。
 */
public interface SyncMarker {

    void markSynced(String rowId);
}
