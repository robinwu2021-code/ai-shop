package ai.neargo.shop.elec.support;

import java.util.Locale;

/**
 * 表头写法的规范化。别名表的键、认列时的查找、确认后学成别名，<b>三处用这同一把尺</b> ——
 * 分开写的话，学进去的别名下次查不到，而那不会报任何错，只是每次都重新去问大模型。
 */
public final class HeaderNames {

    private HeaderNames() {
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
