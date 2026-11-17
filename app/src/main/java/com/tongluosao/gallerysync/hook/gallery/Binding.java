package com.tongluosao.gallerysync.hook.gallery;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 一个 {@link Ref} 的解析结果：持有解析到的 {@link Class}，并按"名字 + 参数类型"缓存
 * {@link Method}/{@link Constructor}、按名字缓存 {@link Field}。
 *
 * <p>两条纪律：
 * <ul>
 *   <li>解析只发生一次。{@link #method} 等在缓存命中后是纯 Map 查找，可以放在热路径上。</li>
 *   <li>失败不抛异常、也不静默：必需成员缺失会写进 {@link #missing()}，可选成员缺失写进
 *       {@link #optionalMissing()}，每条形如
 *       {@code 缺少 RequestCloudItem.getSha1():String}，能直接贴进日志。</li>
 * </ul>
 *
 * <p>同一个 Binding 可能被多个 hook 线程使用，缓存用 {@link ConcurrentHashMap}；缺失清单
 * 按字符串去重，重复查询不会让清单变长。
 */
public final class Binding {

    private static final Class<?>[] EMPTY = new Class<?>[0];

    private final Class<?> mClazz;
    private final ClassLoader mLoader;
    private final Map<String, Method> mMethods = new ConcurrentHashMap<>();
    private final Map<String, Constructor<?>> mConstructors = new ConcurrentHashMap<>();
    private final Map<String, Field> mFields = new ConcurrentHashMap<>();
    private final Set<String> mMissing = new LinkedHashSet<>();
    private final Set<String> mOptionalMissing = new LinkedHashSet<>();
    private final Object mMissingLock = new Object();

    Binding(Class<?> clazz, ClassLoader loader) {
        mClazz = clazz;
        mLoader = loader;
    }

    /** 类本身就没找到的 Binding：只剩一条"缺少类 …"的说明。 */
    static Binding unresolved(ClassLoader loader, String reason) {
        Binding binding = new Binding(null, loader);
        binding.noteMissing(reason);
        return binding;
    }

    /** 解析到的类；类名全部没命中时为 null（原因见 {@link #missing()}）。 */
    public Class<?> clazz() {
        return mClazz;
    }

    /** 必需方法的快捷查询；缺失时会补一条 missing 记录并返回 null。 */
    public Method method(String name, Class<?>... params) {
        return lookupMethod(name, params, null);
    }

    /** 字段的快捷查询；缺失时会补一条 missing 记录并返回 null。 */
    public Field field(String name) {
        return lookupField(name, null);
    }

    /** 构造器查询；缺失时会补一条 missing 记录并返回 null。 */
    public Constructor<?> constructor(Class<?>... params) {
        return lookupConstructor(params);
    }

    /** 必需成员缺失清单的快照，顺序与声明顺序一致。 */
    public List<String> missing() {
        synchronized (mMissingLock) {
            return Collections.unmodifiableList(new ArrayList<>(mMissing));
        }
    }

    /** 可选成员缺失清单的快照：只是信息，不影响 {@link #isComplete()}。 */
    public List<String> optionalMissing() {
        synchronized (mMissingLock) {
            return Collections.unmodifiableList(new ArrayList<>(mOptionalMissing));
        }
    }

    /** 所有必需成员都解析到了。 */
    public boolean isComplete() {
        synchronized (mMissingLock) {
            return mMissing.isEmpty();
        }
    }

    @Override
    public String toString() {
        return "Binding{" + label(mClazz) + ", missing=" + missing().size() + "}";
    }

    // ------------------------------------------------------------ 解析实现

    Method lookupMethod(String name, Class<?>[] params, Class<?> expectedReturn) {
        Class<?>[] args = params == null ? EMPTY : params;
        if (mClazz == null) {
            return null;
        }
        Method found = findMethod(name, args);
        if (found == null) {
            noteMissing(missingMethod(mClazz, name, args, expectedReturn));
            return null;
        }
        if (expectedReturn != null && !matches(found.getReturnType(), expectedReturn)) {
            noteMissing("返回类型不符 " + label(mClazz) + "." + signature(name, args)
                    + ": 期望 " + label(expectedReturn) + "，实际 " + label(found.getReturnType()));
        }
        return found;
    }

    Method lookupOptionalMethod(String name, Class<?>[] params, Class<?> expectedReturn) {
        Class<?>[] args = params == null ? EMPTY : params;
        if (mClazz == null) {
            return null;
        }
        Method found = findMethod(name, args);
        if (found == null) {
            noteOptional(missingMethod(mClazz, name, args, expectedReturn));
            return null;
        }
        if (expectedReturn != null && !matches(found.getReturnType(), expectedReturn)) {
            noteOptional("返回类型不符 " + label(mClazz) + "." + signature(name, args)
                    + ": 期望 " + label(expectedReturn) + "，实际 " + label(found.getReturnType()));
        }
        return found;
    }

    Field lookupField(String name, Class<?> expectedType) {
        if (mClazz == null) {
            return null;
        }
        Field found = findField(name);
        if (found == null) {
            noteMissing(missingField(mClazz, name, expectedType));
            return null;
        }
        if (expectedType != null && !matches(found.getType(), expectedType)) {
            noteMissing("类型不符 " + label(mClazz) + "." + name
                    + ": 期望 " + label(expectedType) + "，实际 " + label(found.getType()));
        }
        return found;
    }

    Field lookupOptionalField(String name, Class<?> expectedType) {
        if (mClazz == null) {
            return null;
        }
        Field found = findField(name);
        if (found == null) {
            noteOptional(missingField(mClazz, name, expectedType));
            return null;
        }
        if (expectedType != null && !matches(found.getType(), expectedType)) {
            noteOptional("类型不符 " + label(mClazz) + "." + name
                    + ": 期望 " + label(expectedType) + "，实际 " + label(found.getType()));
        }
        return found;
    }

    Constructor<?> lookupConstructor(Class<?>[] params) {
        Class<?>[] args = params == null ? EMPTY : params;
        String key = key("<init>", args);
        Constructor<?> cached = mConstructors.get(key);
        if (cached != null) {
            return cached;
        }
        if (mClazz == null) {
            return null;
        }
        try {
            Constructor<?> found = mClazz.getDeclaredConstructor(args);
            makeAccessible(found);
            mConstructors.put(key, found);
            return found;
        } catch (NoSuchMethodException e) {
            noteMissing("缺少 " + label(mClazz) + ".<init>" + paramList(args));
            return null;
        } catch (SecurityException e) {
            noteMissing("不可访问 " + label(mClazz) + ".<init>" + paramList(args)
                    + ": " + e.getClass().getSimpleName());
            return null;
        }
    }

    private Method findMethod(String name, Class<?>[] params) {
        String key = key(name, params);
        Method cached = mMethods.get(key);
        if (cached != null) {
            return cached;
        }
        if (mClazz == null) {
            // 类都没找到：缺成员的清单只会是噪音，由"缺少类 …"一条说明
            return null;
        }
        // 从声明类往父类走：4.3.1.6 里 getLocalFile 在 DBImage 上，按名字猜类名会整条链路放行
        for (Class<?> c = mClazz; c != null; c = c.getSuperclass()) {
            try {
                Method found = c.getDeclaredMethod(name, params);
                makeAccessible(found);
                mMethods.put(key, found);
                return found;
            } catch (NoSuchMethodException e) {
                // 继续往父类找
            }
        }
        return null;
    }

    private Field findField(String name) {
        Field cached = mFields.get(name);
        if (cached != null) {
            return cached;
        }
        if (mClazz == null) {
            return null;
        }
        for (Class<?> c = mClazz; c != null; c = c.getSuperclass()) {
            try {
                Field found = c.getDeclaredField(name);
                makeAccessible(found);
                mFields.put(name, found);
                return found;
            } catch (NoSuchFieldException e) {
                // 继续往父类找
            }
        }
        return null;
    }

    /**
     * 私有成员要放开才能读写。Android 上 hidden-api 可能拒绝，这时保留成员本身，
     * 让调用方在 invoke 时看到 IllegalAccessException，比这里假装成功更好排查。
     */
    private static void makeAccessible(java.lang.reflect.AccessibleObject member) {
        try {
            member.setAccessible(true);
        } catch (RuntimeException e) {
            // InaccessibleObjectException / SecurityException：留给调用点暴露
        }
    }

    private void noteMissing(String message) {
        synchronized (mMissingLock) {
            mMissing.add(message);
        }
    }

    private void noteOptional(String message) {
        synchronized (mMissingLock) {
            mOptionalMissing.add(message);
        }
    }

    ClassLoader loader() {
        return mLoader;
    }

    // ------------------------------------------------------------ 文案

    static String label(Class<?> type) {
        if (type == null) {
            return "?";
        }
        String simple = type.getSimpleName();
        return simple.isEmpty() ? type.getName() : simple;
    }

    static String signature(String name, Class<?>[] params) {
        return name + paramList(params);
    }

    private static String paramList(Class<?>[] params) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < params.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(label(params[i]));
        }
        return sb.append(')').toString();
    }

    private static String missingMethod(Class<?> owner, String name, Class<?>[] params,
            Class<?> returns) {
        StringBuilder sb = new StringBuilder("缺少 ");
        if (owner != null) {
            sb.append(label(owner)).append('.');
        }
        sb.append(signature(name, params));
        if (returns != null) {
            sb.append(':').append(label(returns));
        }
        return sb.toString();
    }

    private static String missingField(Class<?> owner, String name, Class<?> type) {
        StringBuilder sb = new StringBuilder("缺少 ");
        if (owner != null) {
            sb.append(label(owner)).append('.');
        }
        sb.append(name);
        if (type != null) {
            sb.append(':').append(label(type));
        }
        return sb.toString();
    }

    private static String key(String name, Class<?>[] params) {
        StringBuilder sb = new StringBuilder(name);
        for (Class<?> param : params) {
            sb.append('/').append(param == null ? "null" : param.getName());
        }
        return sb.toString();
    }

    /** 声明类型匹配：同一类型或期望类型的子类；不做装箱，原始类型必须写全。 */
    private static boolean matches(Class<?> actual, Class<?> expected) {
        if (actual.equals(expected)) {
            return true;
        }
        return !expected.isPrimitive() && !actual.isPrimitive()
                && expected.isAssignableFrom(actual);
    }
}
