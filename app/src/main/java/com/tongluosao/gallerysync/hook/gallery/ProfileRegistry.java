package com.tongluosao.gallerysync.hook.gallery;

import com.tongluosao.gallerysync.core.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 有序的档案表：按 {@link #select()} 的顺序探测，第一个命中的档案就是这个相册版本。
 *
 * <p>探测只做一次（第一次 {@link #select()} 时），结果含"没有命中"也会缓存：
 * 相册升级要重启进程才生效，重复探测只会在热路径上白跑 {@code Class.forName}。
 *
 * <p>用法：模块初始化时建一个实例（{@link #defaults()}），之后到处用它取能力；
 * 不要在每条 hook 回调里 new。
 */
public final class ProfileRegistry {

    private final List<Profile> mProfiles;
    private final ClassLoader mLoader;
    private final Object mSelectLock = new Object();

    private volatile boolean mSelected;
    private volatile Profile mProfile;

    public ProfileRegistry(List<Profile> profiles, ClassLoader loader) {
        mProfiles = profiles == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(profiles));
        mLoader = loader != null ? loader : ProfileRegistry.class.getClassLoader();
    }

    public ProfileRegistry(List<Profile> profiles) {
        this(profiles, ProfileRegistry.class.getClassLoader());
    }

    /** 自带 {@link Profiles} 里那套档案的注册表，宿主侧直接用这个。 */
    public static ProfileRegistry defaults() {
        return new ProfileRegistry(Profiles.all(), ProfileRegistry.class.getClassLoader());
    }

    /** 首次调用时探测并缓存；没有任何档案命中返回 null。 */
    public Profile select() {
        if (mSelected) {
            return mProfile;
        }
        synchronized (mSelectLock) {
            if (!mSelected) {
                Profile match = null;
                for (Profile profile : mProfiles) {
                    if (profile.probe(mLoader)) {
                        match = profile;
                        break;
                    }
                }
                mProfile = match;
                mSelected = true;
                if (match == null) {
                    Logger.w("event=profile_missing candidates=" + profileNames());
                } else {
                    Logger.i("event=profile_selected profile=" + match.name());
                }
            }
            return mProfile;
        }
    }

    /** 当前版本的能力实例；没有匹配档案、或该档案没这项能力时返回 null。 */
    public Object get(Capability capability) {
        Profile profile = select();
        return profile == null ? null : profile.capability(capability);
    }

    /** 当前命中的档案；没有命中返回 null。 */
    public Profile profile() {
        return select();
    }

    public List<Profile> profiles() {
        return mProfiles;
    }

    /**
     * 一行结构化报告，供 {@link #logReport()} 与诊断页使用：
     * <pre>profile=G2026 sync_items=ok slim_item=unavailable(SLIM_ITEM: no-factory)</pre>
     *
     * <p>只看声明与已记录的失败，<b>不实例化能力</b>：报告不该有副作用。
     * 能力键是 {@link Capability#key()}（小写枚举名），顺序固定为枚举声明顺序。
     */
    public String report() {
        Profile profile = select();
        StringBuilder sb = new StringBuilder(192);
        sb.append("profile=").append(profile == null ? "none" : profile.name());
        for (Capability capability : Capability.values()) {
            sb.append(' ').append(capability.key()).append('=');
            String failure = failureOf(profile, capability);
            if (failure == null) {
                sb.append("ok");
            } else {
                sb.append("unavailable(").append(capability.name())
                        .append(": ").append(failure).append(')');
            }
        }
        return sb.toString();
    }

    /** 打一行 capability_report；档案缺成员时逐条 warn，保证失败可解释。 */
    public void logReport() {
        Logger.i("event=capability_report " + report());
        Profile profile = select();
        if (profile == null) {
            Logger.w("event=capability_report_no_profile candidates=" + profileNames());
            return;
        }
        List<String> missing = profile.missing(mLoader);
        for (String item : missing) {
            Logger.w("event=capability_missing profile=" + profile.name() + " " + item);
        }
    }

    /** null 表示这项能力可用（已声明且没失败过）。 */
    private static String failureOf(Profile profile, Capability capability) {
        if (profile == null) {
            return "no-profile";
        }
        if (!profile.declares(capability)) {
            return "no-factory";
        }
        String recorded = profile.capabilityFailure(capability);
        return recorded == null ? null : recorded;
    }

    private String profileNames() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < mProfiles.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(mProfiles.get(i).name());
        }
        return sb.append(']').toString();
    }
}
