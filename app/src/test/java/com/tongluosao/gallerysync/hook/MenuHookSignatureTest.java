package com.tongluosao.gallerysync.hook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class MenuHookSignatureTest {

    /** 原来那版签名一字不差时必须排在第一位。 */
    @Test
    public void exactSignatureComesFirst() {
        List<String> declared = Arrays.asList(
                "onFilterFinished(BaseDataItem)",
                "onFilterFinished(BaseDataItem,LinkedHashMap)",
                "onFilterFinished()");
        assertEquals(
                Arrays.asList(
                        "onFilterFinished(BaseDataItem,LinkedHashMap)",
                        "onFilterFinished(BaseDataItem)",
                        "onFilterFinished()"),
                MenuHookSignature.candidatesFor(
                        "onFilterFinished(BaseDataItem,LinkedHashMap)", declared));
    }

    /** 只给方法名时，同名重载全部返回，且保持输入顺序。 */
    @Test
    public void nameOnlyKeepsInputOrderAndReturnsAllOverloads() {
        List<String> declared = Arrays.asList(
                "onFilterFinished(BaseDataItem)",
                "getDownloadOriginTitle(BaseDataItem)",
                "onFilterFinished(BaseDataItem,LinkedHashMap)");
        assertEquals(
                Arrays.asList(
                        "onFilterFinished(BaseDataItem)",
                        "onFilterFinished(BaseDataItem,LinkedHashMap)"),
                MenuHookSignature.candidatesFor("onFilterFinished", declared));
    }

    /** 参数类型换了（LinkedHashMap 变成 Map）也要算同参个数匹配。 */
    @Test
    public void sameArityWithDifferentParamTypeStillMatches() {
        List<String> declared = Arrays.asList(
                "onFilterFinished(BaseDataItem,Map)",
                "onFilterFinished(BaseDataItem)");
        assertEquals(
                Arrays.asList(
                        "onFilterFinished(BaseDataItem,Map)",
                        "onFilterFinished(BaseDataItem)"),
                MenuHookSignature.candidatesFor(
                        "onFilterFinished(BaseDataItem,LinkedHashMap)", declared));
    }

    /** 2 参版本优先，1 参版本仍要返回，不能因为参数个数不同就丢掉。 */
    @Test
    public void oneArgVersionIsKeptAfterTwoArgVersion() {
        List<String> declared = Arrays.asList(
                "onFilterFinished(BaseDataItem)",
                "onFilterFinished(BaseDataItem,LinkedHashMap)");
        List<String> candidates = MenuHookSignature.candidatesFor(
                "onFilterFinished(BaseDataItem,LinkedHashMap)", declared);
        assertEquals(2, candidates.size());
        assertEquals("onFilterFinished(BaseDataItem,LinkedHashMap)", candidates.get(0));
        assertEquals("onFilterFinished(BaseDataItem)", candidates.get(1));
    }

    /** 方法名对不上就没有候选，调用方据此走原来的 missing 日志。 */
    @Test
    public void noMatchReturnsEmpty() {
        List<String> declared = Arrays.asList(
                "onFilterStart(BaseDataItem,LinkedHashMap)",
                "refreshDownloadItem(BaseDataItem,boolean,boolean)");
        assertTrue(MenuHookSignature.candidatesFor("onFilterFinished", declared).isEmpty());
        assertTrue(MenuHookSignature.candidatesFor(
                "onFilterFinished(BaseDataItem,LinkedHashMap)", declared).isEmpty());
    }

    /** 空列表、null 列表、空方法名都要安全返回空列表。 */
    @Test
    public void emptyAndNullInputsAreSafe() {
        assertTrue(MenuHookSignature.candidatesFor("onFilterFinished",
                new ArrayList<String>()).isEmpty());
        assertTrue(MenuHookSignature.candidatesFor("onFilterFinished", null).isEmpty());
        assertTrue(MenuHookSignature.candidatesFor(null,
                Arrays.asList("onFilterFinished(BaseDataItem)")).isEmpty());
        assertTrue(MenuHookSignature.candidatesFor("  ",
                Arrays.asList("onFilterFinished(BaseDataItem)")).isEmpty());
    }

    /** 同一个签名重复声明（桥接方法/父类重写）只返回一次。 */
    @Test
    public void duplicateSignaturesAreReturnedOnce() {
        List<String> declared = Arrays.asList(
                "onFilterFinished(BaseDataItem,LinkedHashMap)",
                "onFilterFinished(BaseDataItem,LinkedHashMap)",
                "onFilterFinished(BaseDataItem)",
                "onFilterFinished(BaseDataItem)");
        assertEquals(
                Arrays.asList(
                        "onFilterFinished(BaseDataItem,LinkedHashMap)",
                        "onFilterFinished(BaseDataItem)"),
                MenuHookSignature.candidatesFor("onFilterFinished", declared));
    }

    /** 带包名的声明签名按方法名比较，泛型里的逗号不算参数分隔。 */
    @Test
    public void packageQualifiedAndGenericSignaturesAreParsed() {
        List<String> declared = Arrays.asList(
                "com.miui.gallery.ui.photoPage.bars.menuitem.DownloadOriginal"
                        + ".onFilterFinished(com.miui.gallery.model.BaseDataItem)");
        assertEquals(
                Arrays.asList(
                        "com.miui.gallery.ui.photoPage.bars.menuitem.DownloadOriginal"
                                + ".onFilterFinished(com.miui.gallery.model.BaseDataItem)"),
                MenuHookSignature.candidatesFor("onFilterFinished", declared));
        assertEquals(Arrays.asList("onFilterFinished(java.util.Map<String,Object>)"),
                MenuHookSignature.candidatesFor("onFilterFinished(LinkedHashMap,Integer)",
                        Arrays.asList("onFilterFinished(java.util.Map<String,Object>)")));
    }

    /** simpleName 去包名、去内部类分隔符，数组后缀保留。 */
    @Test
    public void simpleNameStripsPackageAndKeepsArrays() {
        assertEquals("BaseDataItem", MenuHookSignature.simpleName(
                "com.miui.gallery.model.BaseDataItem"));
        assertEquals("LinkedHashMap", MenuHookSignature.simpleName("java.util.LinkedHashMap"));
        assertEquals("byte[]", MenuHookSignature.simpleName("byte[]"));
        assertEquals("String[][]", MenuHookSignature.simpleName("java.lang.String[][]"));
        assertEquals("MenuItemType", MenuHookSignature.simpleName(
                "com.miui.gallery.ui.photoPage.bars.PhotoPageMenuManager$MenuItemType"));
        assertEquals("BaseDataItem", MenuHookSignature.simpleName(
                "com.miui.gallery.model.BaseDataItem"));
        assertEquals("", MenuHookSignature.simpleName(null));
        assertEquals("", MenuHookSignature.simpleName("  "));
    }
}
