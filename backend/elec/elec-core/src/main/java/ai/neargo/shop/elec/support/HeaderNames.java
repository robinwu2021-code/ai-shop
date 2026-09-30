package ai.neargo.shop.elec.support;

import java.util.Locale;

/**
 * 表头写法的规范化。别名表的键、认列时的查找、确认后学成别名，<b>三处用这同一把尺</b> ——
 * 分开写的话，学进去的别名下次查不到，而那不会报任何错，只是每次都重新去问大模型。
 */
public final class HeaderNames {

    /**
     * 备注、联系方式这类列：<b>大模型认了也不采纳、确认后也不学成别名</b>。
     * 供应商常在这里写公司名、电话、微信 —— 导进去就可能出现在买家看得到的地方，身份就漏了（原型 e15）。
     * 他自己手工把这类列选成某个字段仍然可以，那是他明确的选择。
     */
    private static final java.util.Set<String> PRIVATE = java.util.Set.of(
            "备注", "说明", "备注说明", "附注", "REMARK", "REMARKS", "NOTE", "NOTES", "COMMENT", "COMMENTS",
            "联系人", "联系方式", "联系电话", "电话", "手机", "微信", "QQ", "邮箱", "EMAIL", "CONTACT", "TEL", "PHONE",
            "公司", "公司名称", "供应商", "SUPPLIER", "COMPANY");

    private HeaderNames() {
    }

    /** 这一列是不是备注 / 联系方式一类（见 {@link #PRIVATE}） */
    public static boolean isPrivate(String raw) {
        return PRIVATE.contains(norm(raw));
    }

    /** 大写、只留字母数字与 {@code /}（D/C、P/N 就是这么写的）；{@code P/N} 记作 {@code PN}。截 64 字符（列宽） */
    public static String norm(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int cp : raw.toUpperCase(Locale.ROOT).codePoints().toArray()) {
            if (Character.isLetterOrDigit(cp) || cp == '/') {
                sb.appendCodePoint(cp);
            }
        }
        String s = sb.toString();
        if (s.equals("P/N")) {
            return "PN";
        }
        return s.length() > 64 ? s.substring(0, 64) : s;
    }
}
