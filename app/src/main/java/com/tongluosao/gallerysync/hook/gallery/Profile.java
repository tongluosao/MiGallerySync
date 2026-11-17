package com.tongluosao.gallerysync.hook.gallery;

import com.tongluosao.gallerysync.core.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 一个相册版本的"档案"：用哪些类来认定这个版本（sentinels）、这个版本里有哪些成员
 * （若干 {@link Ref}）、以及每个 {@link Capability} 怎么造出来（工厂）。
 *
 * <p>档案是纯声明，不做任何 hook：真正挂钩子的一方从 {@link #capability} 取工厂、
 * 从 {@link #validate} 取 {@link Binding}，自己不写死类名。
 *
 * <p>解析与能力工厂都是懒的，且只做一次：{@link #validate()} 的结果按加载器缓存，
 * 能力第一次取用时才实例化。任何失败都降级成 null/缺失清单，不把异常抛回宿主进程。
 */
public final class Profile {

    private final String mName;
    private final List<String> mSentinels;
    private final List<Ref> mRefs;
    private final Map<Capability, Supplier<Object>> mCapabilities;
    private final ClassLoader mLoader;

    /** 能力解析失败的记录（got null / 工厂抛异常），供 {@link ProfileRegistry#report()} 解释。 */
    private final Map<Capability, String> mFailures = new ConcurrentHashMap<>();

    private final Object mValidateLock = new Object();
    private volatile List<Binding> mBindings;
    private volatile ClassLoader mBindingsLoader;

    private Profile(Builder builder) {
        mName = builder.mName;
        mSentinels = Collections.unmodifiableList(new ArrayList<>(builder.mSentinels));
        mRefs = Collections.unmodifiableList(new ArrayList<>(builder.mRefs));
        mCapabilities = Collections.unmodifiableMap(
                new EnumMap<>(builder.mCapabilities));
        mLoader = builder.mLoader != null ? builder.mLoader : Profile.class.getClassLoader();
    }

    /** 开始写一个档案；名字建议用 {@code G<版本号>}，与 {@link Profiles} 里的排序对应。 */
    public static Builder named(String name) {
        return new Builder(name);
    }

    public String name() {
        return mName;
    }

    /** 判定"是不是这个版本"的类名清单。 */
    public List<String> sentinels() {
        return mSentinels;
    }

    public List<Ref> refs() {
        return mRefs;
    }

    /** 所有 sentinel 都能加载才算命中这个版本。 */
    public boolean probe() {
        return probe(mLoader);
    }

    public boolean probe(ClassLoader loader) {
        ClassLoader cl = loader != null ? loader : mLoader;
        if (mSentinels.isEmpty()) {
            // 没有 sentinel 的档案无法判定版本，宁可不算命中
            return false;
        }
        for (String sentinel : mSentinels) {
            if (!isLoadable(sentinel, cl)) {
                return false;
            }
        }
        return true;
    }

    /** 逐个 Ref 解析并汇总；结果按加载器缓存，第二次调用不再碰反射。 */
    public List<Binding> validate() {
        return validate(mLoader);
    }

    public List<Binding> validate(ClassLoader loader) {
        ClassLoader cl = loader != null ? loader : mLoader;
        List<Binding> cached = mBindings;
        if (cached != null && mBindingsLoader == cl) {
            return cached;
        }
        synchronized (mValidateLock) {
            cached = mBindings;
            if (cached == null || mBindingsLoader != cl) {
                List<Binding> resolved = new ArrayList<>(mRefs.size());
                for (Ref ref : mRefs) {
                    resolved.add(ref.resolve(cl));
                }
                cached = Collections.unmodifiableList(resolved);
                mBindings = cached;
                mBindingsLoader = cl;
            }
            return cached;
        }
    }

    /** 所有 Ref 的必需成员缺失清单（人类可读，可直接贴日志）。 */
    public List<String> missing() {
        return missing(mLoader);
    }

    public List<String> missing(ClassLoader loader) {
        List<String> all = new ArrayList<>();
        for (Binding binding : validate(loader)) {
            all.addAll(binding.missing());
        }
        return Collections.unmodifiableList(all);
    }

    /** 这个版本声明的成员是否全部存在。 */
    public boolean isComplete() {
        return missing().isEmpty();
    }

    /** 这个版本是否声明了某项能力（只看声明，不实例化）。 */
    public boolean declares(Capability capability) {
        return capability != null && mCapabilities.containsKey(capability);
    }

    /**
     * 取能力实例：第一次调用工厂，之后每次都是新实例（工厂自己决定是否缓存）。
     * 没声明、工厂返回 null、工厂抛异常，一律返回 null，并把原因记下来给报告用。
     */
    public Object capability(Capability capability) {
        if (capability == null) {
            return null;
        }
        Supplier<Object> factory = mCapabilities.get(capability);
        if (factory == null) {
            mFailures.put(capability, "no-factory");
            return null;
        }
        try {
            Object value = factory.get();
            if (value == null) {
                mFailures.put(capability, "factory-null");
                Logger.w("event=capability_unavailable profile=" + mName
                        + " capability=" + capability.name() + " detail=factory-null");
                return null;
            }
            mFailures.remove(capability);
            return value;
        } catch (Throwable t) {
            String reason = "factory-failed:" + t.getClass().getSimpleName();
            mFailures.put(capability, reason);
            Logger.w("event=capability_failed profile=" + mName
                    + " capability=" + capability.name() + " detail=" + t, t);
            return null;
        }
    }

    /** 能力的失败原因；没失败过（或没试过）返回 null。 */
    public String capabilityFailure(Capability capability) {
        return capability == null ? null : mFailures.get(capability);
    }

    @Override
    public String toString() {
        return "Profile{" + mName + ", sentinels=" + mSentinels.size()
                + ", refs=" + mRefs.size() + ", capabilities=" + mCapabilities.size() + "}";
    }

    private static boolean isLoadable(String className, ClassLoader loader) {
        try {
            Class.forName(className, false, loader);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        } catch (LinkageError e) {
            return false;
        } catch (SecurityException e) {
            return false;
        }
    }

    /** 档案的流式构造器；{@link #build()} 时才做基本校验。 */
    public static final class Builder {

        private final String mName;
        private final List<String> mSentinels = new ArrayList<>(2);
        private final List<Ref> mRefs = new ArrayList<>(2);
        private final Map<Capability, Supplier<Object>> mCapabilities =
                new EnumMap<>(Capability.class);
        private ClassLoader mLoader;

        private Builder(String name) {
            if (name == null || name.trim().isEmpty()) {
                throw new IllegalArgumentException("Profile 名字不能为空");
            }
            mName = name.trim();
        }

        /** 追加一个 sentinel 类名：嵌套类要写成 {@code a.b.Outer$Inner}。 */
        public Builder sentinel(String className) {
            if (className == null || className.trim().isEmpty()) {
                throw new IllegalArgumentException("Profile " + mName + " 的 sentinel 类名不能为空");
            }
            mSentinels.add(className.trim());
            return this;
        }

        public Builder ref(Ref ref) {
            if (ref == null) {
                throw new IllegalArgumentException("Profile " + mName + " 的 ref 不能为 null");
            }
            mRefs.add(ref);
            return this;
        }

        public Builder capability(Capability capability, Supplier<Object> factory) {
            if (capability == null) {
                throw new IllegalArgumentException("Profile " + mName + " 的 capability 不能为 null");
            }
            if (factory == null) {
                throw new IllegalArgumentException("Profile " + mName + " 的能力 "
                        + capability.name() + " 缺少工厂");
            }
            mCapabilities.put(capability, factory);
            return this;
        }

        /** 解析用的加载器；不设时用模块自己的加载器。 */
        public Builder loader(ClassLoader loader) {
            mLoader = loader;
            return this;
        }

        public Profile build() {
            if (mSentinels.isEmpty()) {
                throw new IllegalStateException("Profile " + mName
                        + " 没有任何 sentinel：无法判断相册是不是这个版本");
            }
            return new Profile(this);
        }
    }
}
