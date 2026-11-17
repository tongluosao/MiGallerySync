package com.tongluosao.gallerysync.hook.gallery;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 声明式反射描述：把"相册某个版本里的某个类长什么样"写成数据，由 {@link Binding} 负责解析。
 *
 * <p>用法（{@code returns()}/{@code type()} 必须紧跟对应的 {@code method()}/{@code field()}）：
 * <pre>{@code
 * Ref.cls("com.miui.gallery.cloud.RequestCloudItem")
 *         .alt("com.miui.gallery.data.remote.RequestCloudItem")   // 备选类名，按顺序尝试
 *         .ctor(Cursor.class)                                     // 必需构造器
 *         .method("getLocalFile").returns(String.class)           // 必需方法
 *         .optionalMethod("getSha1").returns(String.class)        // 缺失不算失败
 *         .field("mLocalFile").type(String.class)                 // 必需字段
 * }</pre>
 *
 * <p>解析只在 {@link #resolve()} 里做一次：结果缓存在 Ref 上，之后热路径直接走
 * {@link Binding#method} 的按名字缓存，不会重复 {@code getDeclaredMethod}。
 * 只写真实存在过的成员 —— 猜出来的名字会让"版本识别"失去意义。
 */
public final class Ref {

    /** 类名候选，第一个是主名，其余是 {@link #alt} 追加的备选。 */
    private final List<String> mClassNames = new ArrayList<>(2);
    private final List<MethodDecl> mMethods = new ArrayList<>();
    private final List<FieldDecl> mFields = new ArrayList<>();

    private boolean mCtorRequired;
    private Class<?>[] mCtorParams = EMPTY;

    private MethodDecl mLastMethod;
    private FieldDecl mLastField;

    private volatile Binding mBinding;

    private static final Class<?>[] EMPTY = new Class<?>[0];

    private Ref() {
    }

    /**
     * 从一个类名开始描述。类不存在时 {@link #resolve()} 不会抛异常，而是在
     * {@link Binding#missing()} 里写清"试过哪几个名字"。
     */
    public static Ref cls(String className) {
        if (className == null || className.trim().isEmpty()) {
            throw new IllegalArgumentException("Ref.cls() 需要一个非空的类名");
        }
        Ref ref = new Ref();
        ref.mClassNames.add(className.trim());
        return ref;
    }

    /** 追加一个备选类名：主名加载不到时按追加顺序继续尝试。 */
    public Ref alt(String className) {
        if (className == null || className.trim().isEmpty()) {
            throw new IllegalArgumentException("Ref.alt() 需要一个非空的类名");
        }
        mClassNames.add(className.trim());
        return this;
    }

    /** 必需构造器；缺了就算档案不完整。 */
    public Ref ctor(Class<?>... params) {
        mCtorRequired = true;
        mCtorParams = params == null ? EMPTY : params.clone();
        return this;
    }

    /** 必需方法；缺失会进 {@link Binding#missing()}。 */
    public Ref method(String name, Class<?>... params) {
        return addMethod(name, params, true);
    }

    /** 可选方法；缺失只进 {@link Binding#optionalMissing()}，不影响 {@link Binding#isComplete()}。 */
    public Ref optionalMethod(String name, Class<?>... params) {
        return addMethod(name, params, false);
    }

    /**
     * 上一个 {@code method()}/{@code optionalMethod()} 的期望返回类型。
     * 与实际返回类型不一致时记为"返回类型不符"（方法本身仍然可用）。
     *
     * <p>原始类型要写 {@code long.class}，框架不做自动装箱匹配。
     */
    public Ref returns(Class<?> returnType) {
        if (mLastMethod == null) {
            throw new IllegalStateException("returns(" + Binding.label(returnType)
                    + ") 之前必须先有 method()/optionalMethod()");
        }
        mLastMethod.returns = returnType;
        return this;
    }

    /** 必需字段；缺失会进 {@link Binding#missing()}。 */
    public Ref field(String name) {
        return addField(name, true);
    }

    /** 可选字段；缺失只进 {@link Binding#optionalMissing()}。 */
    public Ref optionalField(String name) {
        return addField(name, false);
    }

    /** 上一个 {@code field()} 的期望声明类型；不一致时记为"类型不符"。 */
    public Ref type(Class<?> fieldType) {
        if (mLastField == null) {
            throw new IllegalStateException("type(" + Binding.label(fieldType)
                    + ") 之前必须先有 field()/optionalField()");
        }
        mLastField.type = fieldType;
        return this;
    }

    /** 用本类自己的 ClassLoader 解析；结果缓存。 */
    public Binding resolve() {
        return resolve(Ref.class.getClassLoader());
    }

    /**
     * 用指定的 ClassLoader 解析（解析宿主类必须用宿主/模块共同的那个加载器）。
     * 同一个 Ref 只缓存最后一次成功解析的结果；换了加载器会重新解析。
     */
    public Binding resolve(ClassLoader loader) {
        ClassLoader cl = loader != null ? loader : Ref.class.getClassLoader();
        Binding cached = mBinding;
        if (cached != null && cached.loader() == cl) {
            return cached;
        }
        synchronized (this) {
            cached = mBinding;
            if (cached == null || cached.loader() != cl) {
                cached = doResolve(cl);
                mBinding = cached;
            }
            return cached;
        }
    }

    /** 这个 Ref 声明的类名（含备选），给诊断页展示用。 */
    public List<String> classNames() {
        return Collections.unmodifiableList(new ArrayList<>(mClassNames));
    }

    private Ref addMethod(String name, Class<?>[] params, boolean required) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("method() 需要一个非空的方法名");
        }
        MethodDecl decl = new MethodDecl(name.trim(), params == null ? EMPTY : params.clone(),
                required);
        mMethods.add(decl);
        mLastMethod = decl;
        mLastField = null;
        return this;
    }

    private Ref addField(String name, boolean required) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("field() 需要一个非空的字段名");
        }
        FieldDecl decl = new FieldDecl(name.trim(), required);
        mFields.add(decl);
        mLastField = decl;
        mLastMethod = null;
        return this;
    }

    private Binding doResolve(ClassLoader loader) {
        Class<?> clazz = loadClass(loader);
        if (clazz == null) {
            return Binding.unresolved(loader, missingClassMessage());
        }
        Binding binding = new Binding(clazz, loader);
        if (mCtorRequired) {
            binding.lookupConstructor(mCtorParams);
        }
        for (MethodDecl decl : mMethods) {
            if (decl.required) {
                binding.lookupMethod(decl.name, decl.params, decl.returns);
            } else {
                binding.lookupOptionalMethod(decl.name, decl.params, decl.returns);
            }
        }
        for (FieldDecl decl : mFields) {
            if (decl.required) {
                binding.lookupField(decl.name, decl.type);
            } else {
                binding.lookupOptionalField(decl.name, decl.type);
            }
        }
        return binding;
    }

    private Class<?> loadClass(ClassLoader loader) {
        for (String name : mClassNames) {
            try {
                return Class.forName(name, false, loader);
            } catch (ClassNotFoundException e) {
                // 试下一个备选名；全部失败时由 missingClassMessage() 说明试过哪些
            } catch (LinkageError e) {
                // 类文件在但依赖缺失/版本不符，同样当作"不是这个版本"
            } catch (SecurityException e) {
                // 加载器拒绝访问：对这个档案来说等价于不存在
            }
        }
        return null;
    }

    private String missingClassMessage() {
        StringBuilder sb = new StringBuilder("缺少类 ").append(mClassNames.get(0));
        if (mClassNames.size() > 1) {
            sb.append("（备选 ");
            for (int i = 1; i < mClassNames.size(); i++) {
                if (i > 1) {
                    sb.append(", ");
                }
                sb.append(mClassNames.get(i));
            }
            sb.append(" 也不存在）");
        }
        return sb.toString();
    }

    private static final class MethodDecl {
        final String name;
        final Class<?>[] params;
        final boolean required;
        Class<?> returns;

        MethodDecl(String name, Class<?>[] params, boolean required) {
            this.name = name;
            this.params = params;
            this.required = required;
        }
    }

    private static final class FieldDecl {
        final String name;
        final boolean required;
        Class<?> type;

        FieldDecl(String name, boolean required) {
            this.name = name;
            this.required = required;
        }
    }
}
