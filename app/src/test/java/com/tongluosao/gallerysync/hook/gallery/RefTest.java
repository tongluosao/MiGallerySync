package com.tongluosao.gallerysync.hook.gallery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * {@link Ref}/{@link Binding} 的解析语义：完整解析、缺方法、可选方法、备选类名、构造器、
 * 类型校验与缓存。
 *
 * <p>全部用测试内部的假类，成员名沿用真实相册里的叫法，但类本身是自己的 —— 所以这些用例
 * 在普通 JVM 上就能跑，不需要设备。
 */
public final class RefTest {

    /** 假宿主同步项：只有 {@code getLocalFile()/getSize()}，<b>故意没有</b> {@code getSha1()}。 */
    static class FakeCloudItem {
        private String mLocalFile = "/storage/emulated/0/DCIM/a.jpg";
        private long mSize = 2048L;

        FakeCloudItem() {
        }

        FakeCloudItem(FakeToken token) {
            mLocalFile = token.path;
        }

        public String getLocalFile() {
            return mLocalFile;
        }

        public void setLocalFile(String path) {
            mLocalFile = path;
        }

        public long getSize() {
            return mSize;
        }
    }

    /** 完整版：在假同步项上补一个确实存在的 {@code getSha1()}。 */
    static class FakeCloudItemWithSha1 extends FakeCloudItem {
        public String getSha1() {
            return "0123456789abcdef0123456789abcdef01234567";
        }
    }

    static class FakeToken {
        final String path;

        FakeToken(String path) {
            this.path = path;
        }
    }

    // ------------------------------------------------------------ 完整解析

    @Test
    public void completeRefResolvesEveryDeclaredMember() throws Exception {
        Ref ref = Ref.cls(FakeCloudItemWithSha1.class.getName())
                .ctor()
                .method("getLocalFile").returns(String.class)
                .optionalMethod("getSha1").returns(String.class)
                .method("setLocalFile", String.class)
                .field("mLocalFile").type(String.class)
                .optionalField("priority");
        Binding binding = ref.resolve();

        assertSame(FakeCloudItemWithSha1.class, binding.clazz());
        assertTrue(binding.missing().toString(), binding.isComplete());
        assertTrue(binding.missing().isEmpty());
        // priority 只声明了 optional：不存在只进可选清单
        assertEquals(1, binding.optionalMissing().size());
        assertTrue(binding.optionalMissing().get(0), binding.optionalMissing().get(0).contains("priority"));

        Object item = binding.constructor().newInstance();
        assertEquals("/storage/emulated/0/DCIM/a.jpg", binding.method("getLocalFile").invoke(item));
        binding.method("setLocalFile", String.class).invoke(item, "/tmp/x.jpg");
        assertEquals("/tmp/x.jpg", binding.field("mLocalFile").get(item));
        assertEquals("0123456789abcdef0123456789abcdef01234567",
                binding.method("getSha1").invoke(item));
    }

    // ------------------------------------------------------------ 缺方法

    @Test
    public void missingRequiredMethodIsExplained() {
        Binding binding = Ref.cls(FakeCloudItem.class.getName())
                .method("getSha1").returns(String.class)
                .resolve();

        assertFalse(binding.isComplete());
        List<String> missing = binding.missing();
        assertEquals(1, missing.size());
        String message = missing.get(0);
        assertTrue(message, message.startsWith("缺少 "));
        // 报错必须带类名、方法名与期望返回类型，方便直接对日志改档案
        assertTrue(message, message.contains("FakeCloudItem.getSha1():String"));
        assertTrue(binding.optionalMissing().isEmpty());
        assertNull(binding.method("getSha1"));
    }

    // ------------------------------------------------------------ 可选方法

    @Test
    public void missingOptionalMethodKeepsBindingComplete() {
        Binding binding = Ref.cls(FakeCloudItem.class.getName())
                .method("getLocalFile").returns(String.class)
                .optionalMethod("getSha1").returns(String.class)
                .resolve();

        assertTrue(binding.isComplete());
        assertTrue(binding.missing().isEmpty());
        assertEquals(1, binding.optionalMissing().size());
        assertTrue(binding.optionalMissing().get(0), binding.optionalMissing().get(0).contains("getSha1():String"));
        assertNull(binding.method("getSha1"));
    }

    // ------------------------------------------------------------ 备选类名

    @Test
    public void alternativeClassNameIsTriedInOrder() {
        Binding binding = Ref.cls("com.miui.gallery.cloud.RequestCloudItem")
                .alt(FakeCloudItem.class.getName())
                .method("getLocalFile").returns(String.class)
                .resolve();

        assertSame(FakeCloudItem.class, binding.clazz());
        assertTrue(binding.isComplete());
    }

    @Test
    public void missingClassListsEveryCandidate() {
        Binding binding = Ref.cls("com.example.gallery.Gone")
                .alt("com.example.gallery.AlsoGone")
                .method("getLocalFile")
                .resolve();

        assertNull(binding.clazz());
        assertFalse(binding.isComplete());
        assertEquals(1, binding.missing().size());
        String message = binding.missing().get(0);
        assertTrue(message, message.contains("缺少类 com.example.gallery.Gone"));
        assertTrue(message, message.contains("com.example.gallery.AlsoGone"));
        assertNull(binding.method("getLocalFile"));
    }

    // ------------------------------------------------------------ 构造器

    @Test
    public void constructorIsResolvedAndMissingOneIsExplained() throws Exception {
        Binding binding = Ref.cls(FakeCloudItem.class.getName())
                .ctor(FakeToken.class)
                .resolve();
        assertTrue(binding.isComplete());

        Object item = binding.constructor(FakeToken.class).newInstance(new FakeToken("/tmp/token.jpg"));
        assertEquals("/tmp/token.jpg", binding.method("getLocalFile").invoke(item));

        Binding broken = Ref.cls(FakeCloudItem.class.getName())
                .ctor(FakeToken.class, int.class)
                .resolve();
        assertFalse(broken.isComplete());
        assertTrue(broken.missing().get(0), broken.missing().get(0).contains("<init>(FakeToken, int)"));
    }

    // ------------------------------------------------------------ 类型校验

    @Test
    public void fieldTypeMismatchIsExplained() {
        Binding binding = Ref.cls(FakeCloudItem.class.getName())
                .field("mSize").type(String.class)
                .resolve();

        assertFalse(binding.isComplete());
        String message = binding.missing().get(0);
        assertTrue(message, message.contains("类型不符 FakeCloudItem.mSize"));
        assertTrue(message, message.contains("实际 long"));
        // 字段本身还是能拿到，调用方可以自己判断怎么降级
        assertNotNull(binding.field("mSize"));
    }

    @Test
    public void returnTypeMismatchIsExplained() {
        Binding binding = Ref.cls(FakeCloudItem.class.getName())
                .method("getSize").returns(int.class)
                .resolve();

        assertFalse(binding.isComplete());
        String message = binding.missing().get(0);
        assertTrue(message, message.contains("返回类型不符 FakeCloudItem.getSize()"));
        assertTrue(message, message.contains("期望 int"));
        assertTrue(message, message.contains("实际 long"));
    }

    // ------------------------------------------------------------ 缓存与用法错误

    @Test
    public void resolveIsCachedPerLoader() {
        Ref ref = Ref.cls(FakeCloudItem.class.getName()).method("getLocalFile");
        assertSame(ref.resolve(), ref.resolve());
        assertSame(ref.resolve(FakeCloudItem.class.getClassLoader()), ref.resolve());
    }

    @Test(expected = IllegalStateException.class)
    public void returnsWithoutMethodIsRejected() {
        Ref.cls(FakeCloudItem.class.getName()).returns(String.class);
    }

    @Test(expected = IllegalArgumentException.class)
    public void emptyClassNameIsRejected() {
        Ref.cls("  ");
    }
}
