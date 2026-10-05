package com.tongluosao.gallerysync.hook;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;

import com.tongluosao.gallerysync.core.Logger;
import com.tongluosao.gallerysync.core.SyncMarker;

/**
 * 补写相册的"已同步"标记，语句照抄宿主自己的落册那一步：
 * {@code CloudUtils.updateToLocalDBForSync} 最终执行
 * {@code UPDATE cloud SET localFlag = 0 WHERE _id = ? AND localFlag NOT IN (2,15)}。
 *
 * <p>只写 localFlag，**不伪造 serverId / serverStatus**。云端确实没有这份文件，
 * 伪造出来的 serverId 会让相册后续按它去查看、恢复、删云端对象，那些操作全部对不上号；
 * localFlag 单独归 0 只影响"还需不需要上传"这一件事，也正是我们要的。
 *
 * <p>跑在相册进程里用的是相册自己的 UID，所以对它那个 provider 的写权限天然满足，
 * 不需要任何额外授权。
 */
public final class MarkSynced implements SyncMarker {

    private static final Uri CLOUD_URI =
            Uri.parse("content://com.miui.gallery.cloud.provider/cloud");

    private final Context mContext;

    public MarkSynced(Context context) {
        mContext = context.getApplicationContext();
    }

    @Override
    public void markSynced(String rowId) {
        if (rowId == null || rowId.isEmpty()) {
            return;
        }
        try {
            ContentValues values = new ContentValues();
            values.put("localFlag", 0);
            int rows = mContext.getContentResolver().update(CLOUD_URI, values,
                    "_id = ? AND localFlag NOT IN(2,15)", new String[]{rowId});
            Logger.d("event=mark_synced row=" + rowId + " rows=" + rows);
        } catch (Throwable t) {
            // 记不上账只是让相册下轮再枚举一次，队列按 路径+大小+mtime 去重，不会重传
            Logger.w("event=mark_synced_failed row=" + rowId, t);
        }
    }

    /**
     * 把"已经标成上传完成"的行全部重置回"待上传"，让相册重新枚举、模块重新传一遍。
     *
     * <p><b>为什么需要它</b>："相册说传完了、远端却没有"这类坏账，任何代码改动都**看不见** ——
     * 相册挑待上传项的谓词是 {@code localFlag != 0}（{@code CloudUtils.getSelectionOwnerNeedSync}），
     * 这些行 {@code localFlag} 已经是 0，相册根本不会再把它们交给 {@code invokeList}，
     * 模块也就永远没有机会发现远端缺文件并补传。唯一的修法就是把这些行退回"未上传"。
     *
     * <p>做法完全照抄宿主自己关闭云备份时那一套（{@code DeleteDataUtil.resetTableCloud}）：
     * 同一组列、同一个目标值，只把范围收紧到"已经标成完成"的行上。
     * 那些列正是相册用来认定"这份文件在云上"的全部依据 ——
     * 它们的 {@code serverId} 指向的小米云对象本来就不存在（我们自己换掉了云），
     * 留着只会让相册之后按它去查看/恢复云端对象而全部对不上号。
     *
     * <p><b>为什么范围是这两条</b>：
     * <ul>
     *   <li>{@code localFlag = 0}：就是"已完成"这个状态。置回 7（"自动创建，待上传"），
     *       这是相册在 {@code SyncOwnerDataFromLocal.putToRequestCloudItemList} 里
     *       明确会走上传分支的取值之一（4/7/8 才进上传列表）。</li>
     *   <li>{@code serverId IS NOT NULL}：跟宿主那条谓词保持同一个前提。没有 serverId 的行
     *       本来也没被相册算成"在云上"，动它们纯属多余。</li>
     * </ul>
     *
     * <p>刻意**不动** {@code localFlag IN (2,15)} 的行：那是"已删除/编辑中"的在途状态，
     * 替相册改它们会打乱它自己的删除同步。
     *
     * <p>不动 {@code localFile}：文件本身还在手机上，我们要的就是让相册重新上传它。
     *
     * @return 实际改动的行数；抛出异常时由调用方记日志
     */
    public int resetUploaded() {
        ContentValues values = new ContentValues();
        values.putNull("serverId");
        values.putNull("serverTag");
        values.putNull("serverStatus");
        values.put("localFlag", 7);
        int rows = mContext.getContentResolver().update(CLOUD_URI, values,
                "localFlag = 0 AND serverId IS NOT NULL", null);
        Logger.i("event=reset_uploaded rows=" + rows);
        return rows;
    }
}
