package com.tongluosao.gallerysync.hook;

import android.content.Context;
import android.content.Intent;
import android.util.AttributeSet;

import com.tongluosao.gallerysync.core.Config;
import com.tongluosao.gallerysync.core.Logger;
import com.tongluosao.gallerysync.ui.SettingsActivity;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import io.github.libxposed.api.XposedInterface;

/**
 * 相册设置页里的模块入口（可选能力，默认关闭）。
 *
 * <p>相册的备份区在 CN 版会被整条摘掉 —— {@code initGlobalBackupPreference()} 末尾那句
 * {@code removePreference(mSelectBackupCategory)} 会把 key 为 {@code backup_category} 的分类
 * 连同里面的"选择备份"一起移除，没给第三方留任何插槽。
 *
 * <p>早先的做法是限时伪装成国际版（{@code isInternational} + {@code isSupportOneDrive} 双双覆写为真），
 * 骗相册走"国际版且支持 OneDrive"那条分支，把"选择备份"露出来，再接管它的点击。
 * 这条路已废弃，两个原因：
 * <ul>
 *   <li>露出来的"选择备份"点进去是 {@code BackupSelectActivity}，里面只有一个下拉框，
 *       选项来自 {@code arrays.xml} 的 {@code global_backup_display_title} ——
 *       固定就是 Google 与 OneDrive，对 WebDAV 毫无意义；</li>
 *   <li>接管点击依赖 {@code miuix.preference.TextPreference} 的点击最终会走到
 *       Fragment 的 {@code onPreferenceClick}，实测并不可靠（用户点进去看到的仍是原生页）。</li>
 * </ul>
 *
 * <p>现在改为直接往设置页注入一条自己的入口：挂在 key 为 {@code backup_automatically} 的那张卡
 * （就是设置页里并排显示"云空间"与"相册云同步"的"云同步和云空间"）的正下方，点击打开模块配置页。
 *
 * <p><b>注入位置必须靠 {@code setOrder}，不能靠列表下标。</b>
 * {@code PreferenceGroup.addPreference()} 内部是 {@code Collections.binarySearch} 按
 * {@code Preference.compareTo}（先比 {@code mOrder}）定位插入，并不是追加到末尾；
 * 而 {@code PreferenceGroupAdapter} 构造时还会再 {@code sortPreferences()} 一次。
 * 也就是说下标会被覆盖，order 不会 —— 详见 {@link #placeBelow}。
 *
 * <p>宿主侧的 androidx / miuix 类型一律用反射访问，避免模块引入与相册版本不一致的依赖。
 */
public final class UiMaskHook {

    private static final String CLS_SETTINGS_FRAGMENT = "com.miui.gallery.ui.GallerySettingsFragment";
    /** 设置页里"云同步和云空间"那张卡，见 gallery_preferences.xml */
    private static final String KEY_ANCHOR = "backup_automatically";
    /** 注入项的 key：既用于判重，也避免与相册已有的 key 撞车 */
    private static final String KEY_ENTRY = "migallerysync_entry";

    private static final String CLS_TEXT_PREFERENCE = "miuix.preference.TextPreference";
    private static final String CLS_PREFERENCE = "androidx.preference.Preference";
    private static final String CLS_PREFERENCE_GROUP = "androidx.preference.PreferenceGroup";
    private static final String CLS_CLICK_LISTENER =
            "androidx.preference.Preference$OnPreferenceClickListener";

    private static final String ENTRY_TITLE = "MiGallerySync";
    private static final String ENTRY_SUMMARY = "WebDAV 备份设置";

    /**
     * 模块自己的 applicationId，必须与 {@code app/build.gradle.kts} 里的 applicationId 一致。
     *
     * <p>这个常量不能省。{@code new Intent(context, SettingsActivity.class)} 会走
     * {@code ComponentName(Context, Class)}，而那个构造器取的是<b>传入 Context 的包名</b> ——
     * 这里传进来的是相册的 Activity，于是拼出
     * {@code com.miui.gallery/com.tongluosao.gallerysync.ui.SettingsActivity}
     * 这种不存在的组件，{@code startActivity} 直接抛 {@code ActivityNotFoundException}
     * （真机日志：{@code Activity class {com.miui.gallery/...} does not exist}）。
     */
    private static final String MODULE_PACKAGE = "com.tongluosao.gallerysync";

    private final XposedInterface mXposed;
    private final ClassLoader mClassLoader;
    private volatile boolean mEnabled;

    private UiMaskHook(XposedInterface xposed, ClassLoader classLoader) {
        this.mXposed = xposed;
        this.mClassLoader = classLoader;
    }

    public static UiMaskHook install(XposedInterface xposed, ClassLoader classLoader) {
        UiMaskHook hook = new UiMaskHook(xposed, classLoader);
        hook.installEntryHook();
        return hook;
    }

    public void setConfig(Config config) {
        mEnabled = config != null && config.isUiMask();
    }

    /**
     * 挂到 {@code initGlobalBackupPreference()} 上，等它跑完再注入。
     *
     * <p>挑这个方法而不是 {@code onCreatePreferences()}：后者尾部有一大串可见性调整，
     * 里面任何一个 NPE 都会让注入落空；而 {@code initGlobalBackupPreference()} 方法体极小，
     * 且全工程只有 {@code onCreatePreferences} 一处调用它，不会重复触发。
     * 更重要的是它跑在 {@code onCreatePreferences} 期间 —— 那时视图还没建，
     * {@code PreferenceGroupAdapter} 也还没构造，改 order 才有效。
     */
    private void installEntryHook() {
        try {
            Class<?> fragment = mClassLoader.loadClass(CLS_SETTINGS_FRAGMENT);
            Method init = fragment.getDeclaredMethod("initGlobalBackupPreference");
            init.setAccessible(true);
            mXposed.hook(init)
                    .setId("gallery_ui_mask_entry")
                    .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                    .intercept(chain -> {
                        try {
                            return chain.proceed();
                        } finally {
                            // 用 finally：原方法万一抛异常，设置页本身已经出问题了，
                            // 这里照样试一次注入，注不进去也只是少一个入口，不会更糟。
                            if (mEnabled) {
                                injectEntry(chain.getThisObject());
                            }
                        }
                    });
            Logger.i("event=hook_registered method=GallerySettingsFragment.initGlobalBackupPreference");
        } catch (Throwable t) {
            Logger.w("event=ui_mask_entry_hook_failed detail=" + t, t);
        }
    }

    /**
     * 往设置页塞一条入口。
     *
     * <p>整段都在 try/catch 里：设置页打不开比"少一个入口"严重得多，
     * 所以任何反射失败都只记日志，绝不往外抛。
     */
    private void injectEntry(Object fragment) {
        try {
            if (fragment == null) {
                return;
            }
            Class<?> prefCls = mClassLoader.loadClass(CLS_PREFERENCE);
            Class<?> groupCls = mClassLoader.loadClass(CLS_PREFERENCE_GROUP);

            Object root = fragment.getClass().getMethod("getPreferenceScreen").invoke(fragment);
            if (root == null) {
                Logger.w("event=ui_mask_entry_anchor_missing reason=no_screen");
                return;
            }
            Method findPreference = groupCls.getMethod("findPreference", CharSequence.class);
            if (findPreference.invoke(root, KEY_ENTRY) != null) {
                // 设置页可能被重建过，注入过的 key 还在就说明这一轮不用再来一次
                Logger.i("event=ui_mask_entry_skip reason=already_present");
                return;
            }
            Context context = activityOf(fragment);
            if (context == null) {
                Logger.w("event=ui_mask_entry_anchor_missing reason=no_context");
                return;
            }
            Object entry = createEntry(context, prefCls);
            if (entry == null) {
                return;
            }
            prefCls.getMethod("setKey", String.class).invoke(entry, KEY_ENTRY);
            prefCls.getMethod("setTitle", CharSequence.class).invoke(entry, ENTRY_TITLE);
            prefCls.getMethod("setSummary", CharSequence.class).invoke(entry, ENTRY_SUMMARY);
            // 不落盘：这一项是模块自己的入口，没有需要相册帮着记的状态
            prefCls.getMethod("setPersistent", boolean.class).invoke(entry, false);
            attachClickListener(entry, context, prefCls);

            Object anchor = findPreference.invoke(root, KEY_ANCHOR);
            int anchorOrder = Integer.MIN_VALUE;
            if (anchor != null) {
                anchorOrder = placeBelow(root, anchor, entry, prefCls, groupCls);
            }
            groupCls.getMethod("addPreference", prefCls).invoke(root, entry);
            Logger.i("event=ui_mask_entry_injected anchor_order="
                    + (anchorOrder == Integer.MIN_VALUE ? "none" : String.valueOf(anchorOrder)));
        } catch (Throwable t) {
            Logger.w("event=ui_mask_entry_inject_failed detail=" + t, t);
        }
    }

    /**
     * 把 entry 的 order 排到 anchor 正下方，返回 anchor 的 order
     * （拿不到锚点位置时返回 {@link Integer#MIN_VALUE}，此时 entry 会落到列表末尾）。
     *
     * <p>为什么非要动 order：相册的 {@code gallery_preferences.xml} 没写 {@code android:order}，
     * 而 {@code PreferenceGroup.mOrderingAsAdded} 默认为 true，于是 inflate 时每个子项按加入顺序
     * 拿到了 0,1,2… 的 order。要让 entry 落在 anchor 之后，得先把 anchor 之后的项统统后移一位，
     * 否则 entry 会和紧邻的那一项撞同一个 order —— 撞号时 {@code compareTo} 会退化成按标题比较，
     * 位置就不再受我们控制了。
     */
    private static int placeBelow(Object root, Object anchor, Object entry,
                                  Class<?> prefCls, Class<?> groupCls) throws Exception {
        Method getOrder = prefCls.getMethod("getOrder");
        Method setOrder = prefCls.getMethod("setOrder", int.class);
        int anchorOrder = (Integer) getOrder.invoke(anchor);
        // Integer.MAX_VALUE 是"没指定 order"的哨兵，拿它做加法会溢出
        if (anchorOrder == Integer.MAX_VALUE) {
            return Integer.MIN_VALUE;
        }
        int target = anchorOrder + 1;
        int count = (Integer) groupCls.getMethod("getPreferenceCount").invoke(root);
        Method getAt = groupCls.getMethod("getPreference", int.class);
        for (int i = 0; i < count; i++) {
            Object sibling = getAt.invoke(root, i);
            int order = (Integer) getOrder.invoke(sibling);
            if (order >= target && order != Integer.MAX_VALUE) {
                setOrder.invoke(sibling, order + 1);
            }
        }
        setOrder.invoke(entry, target);
        return anchorOrder;
    }

    /**
     * 造一条设置项。
     *
     * <p>优先用相册自己的 {@code miuix.preference.TextPreference}：它渲染成和相邻项一致的
     * Miuix 卡片（右侧还带箭头）。拿不到再退回 {@code androidx.preference.Preference}，
     * 后者样式会朴素一些，但可渲染、可点，功能不打折。
     *
     * <p>不调 {@code setIcon}：模块的资源 id 在宿主包里解析不到，会画错东西。
     */
    private Object createEntry(Context context, Class<?> prefCls) {
        try {
            Class<?> miuix = mClassLoader.loadClass(CLS_TEXT_PREFERENCE);
            try {
                return miuix.getConstructor(Context.class).newInstance(context);
            } catch (Throwable t) {
                Logger.w("event=ui_mask_entry_miuix_unavailable reason=ctor_context", t);
            }
            try {
                return miuix.getConstructor(Context.class, AttributeSet.class)
                        .newInstance(context, (Object) null);
            } catch (Throwable t) {
                Logger.w("event=ui_mask_entry_miuix_unavailable reason=ctor_context_attrs", t);
            }
        } catch (Throwable t) {
            Logger.w("event=ui_mask_entry_miuix_unavailable reason=no_class", t);
        }
        try {
            return prefCls.getConstructor(Context.class).newInstance(context);
        } catch (Throwable t) {
            Logger.w("event=ui_mask_entry_create_failed", t);
            return null;
        }
    }

    /**
     * 用动态代理实现宿主的 {@code OnPreferenceClickListener}。
     *
     * <p>不能直接用 lambda：那是编译期针对模块自己那份 androidx.preference 生成的实现，
     * 而这里必须实现<b>宿主 ClassLoader 里</b>的那个接口，否则
     * {@code setOnPreferenceClickListener} 会因为类型对不上直接抛 {@code IllegalArgumentException}。
     */
    private void attachClickListener(Object entry, Context context, Class<?> prefCls) throws Exception {
        Class<?> listenerCls = mClassLoader.loadClass(CLS_CLICK_LISTENER);
        InvocationHandler handler = (proxy, method, args) -> {
            switch (method.getName()) {
                case "onPreferenceClick":
                    openSettings(context);
                    return Boolean.TRUE;
                // 下面三个必须自己兜住：Preference 内部会拿监听器做比较，
                // 落到 default 分支返回 null 会直接 NPE 或拆箱失败
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == (args != null && args.length > 0 ? args[0] : null);
                case "toString":
                    return "MiGallerySyncEntryClickListener";
                default:
                    return null;
            }
        };
        Object listener = Proxy.newProxyInstance(
                listenerCls.getClassLoader(), new Class<?>[]{listenerCls}, handler);
        prefCls.getMethod("setOnPreferenceClickListener", listenerCls).invoke(entry, listener);
    }

    private static void openSettings(Context context) {
        try {
            Intent intent = new Intent();
            // 必须显式指定包名，不能用 Intent(Context, Class)，理由见 MODULE_PACKAGE
            intent.setClassName(MODULE_PACKAGE, SettingsActivity.class.getName());
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            Logger.i("event=ui_mask_entry_opened");
        } catch (Throwable t) {
            // detail 拼进 msg：框架日志通道只带 message、不带 throwable
            Logger.w("event=ui_mask_entry_open_failed detail=" + t, t);
        }
    }

    /** Fragment.getActivity() 的反射版本，避免依赖 androidx.fragment */
    private static Context activityOf(Object fragment) {
        if (fragment == null) {
            return null;
        }
        try {
            Method getActivity = fragment.getClass().getMethod("getActivity");
            Object activity = getActivity.invoke(fragment);
            return activity instanceof Context ? (Context) activity : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
