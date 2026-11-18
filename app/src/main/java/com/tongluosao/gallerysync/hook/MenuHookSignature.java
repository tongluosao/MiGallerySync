package com.tongluosao.gallerysync.hook;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 菜单 hook 的方法签名匹配规则。纯 Java、不依赖 Android 类，方便在 JVM 单测里覆盖。
 *
 * <p>同一个方法在不同相册版本上可能换参数类型（{@code LinkedHashMap} 改成 {@code Map}），
 * 甚至换参数个数（{@code onFilterFinished(BaseDataItem, LinkedHashMap)} 变成
 * {@code onFilterFinished(BaseDataItem)}）。这里按「完全一致 → 同名同参个数 → 同名任意参个数」
 * 三档给出值得 hook 的签名，让 hook 端不用死磕单一签名。
 */
public final class MenuHookSignature {

    private MenuHookSignature() { }

    /**
     * 给定目标方法名（{@code "onFilterFinished"}）或完整签名
     * （{@code "onFilterFinished(BaseDataItem,LinkedHashMap)"}），以及某个类声明的签名列表
     * （格式 {@code "name(paramType1,paramType2)"} 或 {@code "name()"}），返回值得 hook 的签名：
     * <ol>
     *   <li>与目标完全一致（目标带参数表时才有意义，用来钉住原来那版签名）；</li>
     *   <li>同名且参数个数与目标一致（参数类型可以不同，例如 Map 换成 LinkedHashMap）；</li>
     *   <li>同名任意参数个数（例如 2 参变成 1 参）。</li>
     * </ol>
     * 每档内部保持输入顺序，跨档去重，同一个签名只返回一次。
     */
    public static List<String> candidatesFor(String name, List<String> declaredSignatures) {
        List<String> result = new ArrayList<>();
        if (name == null || declaredSignatures == null || declaredSignatures.isEmpty()) {
            return result;
        }
        String target = name.trim();
        if (target.isEmpty()) return result;
        String targetName = simpleName(methodNameOf(target));
        int targetArity = arityOf(target);
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        // 第 1 档：完整签名一致
        for (String signature : declaredSignatures) {
            if (signature == null) continue;
            if (signature.trim().equals(target)) ordered.add(signature);
        }
        // 第 2 档：同名 + 同参个数
        if (targetArity >= 0) {
            for (String signature : declaredSignatures) {
                if (signature == null) continue;
                if (simpleName(methodNameOf(signature)).equals(targetName)
                        && arityOf(signature) == targetArity) {
                    ordered.add(signature);
                }
            }
        }
        // 第 3 档：同名任意参个数
        for (String signature : declaredSignatures) {
            if (signature == null) continue;
            if (simpleName(methodNameOf(signature)).equals(targetName)) ordered.add(signature);
        }
        result.addAll(ordered);
        return result;
    }

    /**
     * 去掉包名与泛型参数，保留数组后缀：{@code "java.lang.String[][]"} 变成
     * {@code "String[][]"}，{@code "PhotoPageMenuManager$MenuItemType"} 变成
     * {@code "MenuItemType"}，{@code "[B"} 原样返回。
     */
    public static String simpleName(String type) {
        if (type == null) return "";
        String value = type.trim();
        if (value.isEmpty()) return "";
        // 先摘掉数组后缀，避免把 "[]" 里的字符当成包名分隔符
        StringBuilder suffix = new StringBuilder();
        while (value.endsWith("[]")) {
            suffix.append("[]");
            value = value.substring(0, value.length() - 2).trim();
        }
        int generic = value.indexOf('<');
        if (generic >= 0) value = value.substring(0, generic).trim();
        int cut = Math.max(value.lastIndexOf('.'), value.lastIndexOf('$'));
        if (cut >= 0 && cut + 1 < value.length()) value = value.substring(cut + 1);
        return value + suffix;
    }

    /** 方法名部分，{@code "onFilterFinished(BaseDataItem)"} 只留 {@code "onFilterFinished"}。 */
    static String methodNameOf(String signature) {
        int open = signature.indexOf('(');
        return open < 0 ? signature.trim() : signature.substring(0, open).trim();
    }

    /** 参数个数；没有参数表（只是方法名）时返回 -1，表示个数未知。 */
    static int arityOf(String signature) {
        int open = signature.indexOf('(');
        if (open < 0) return -1;
        int close = signature.lastIndexOf(')');
        String params = close > open ? signature.substring(open + 1, close)
                : signature.substring(open + 1);
        if (params.trim().isEmpty()) return 0;
        int arity = 1;
        int depth = 0;
        for (int i = 0; i < params.length(); i++) {
            char c = params.charAt(i);
            if (c == '<') {
                depth++;
            } else if (c == '>') {
                if (depth > 0) depth--;
            } else if (c == ',' && depth == 0) {
                arity++;
            }
        }
        return arity;
    }
}
