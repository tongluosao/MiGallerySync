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
}
