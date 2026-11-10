package ai.neargo.shop.sql;

import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.List;

/** Decode only MySQL metadata's escaped delimiters around simple charset-prefixed literals. */
public final class NativeCheckMetadata {
    private static final Pattern SIMPLE = Pattern.compile(
            "(?i)(_(?:utf8mb4|utf8|ascii|latin1|binary))" + Pattern.quote("\\'")
                    + "([^'\\\\]*)" + Pattern.quote("\\'"));

    private NativeCheckMetadata() {
    }

    /** Flatten only the same associative SQL boolean operator; retain order and mixed grouping. */
    public static String combine(String operator, List<String> values) {
        if (!operator.equals("and") && !operator.equals("or")) throw new IllegalArgumentException("boolean operator");
        var flat = new ArrayList<String>();
        for (String value : values) {
            if (!value.startsWith(operator + "(")) {
                flat.add(value);
                continue;
            }
            String body = value.substring(operator.length() + 1, value.length() - 1);
            int depth = 0, start = 0;
            boolean quoted = false;
            for (int i = 0; i < body.length(); i++) {
                char c = body.charAt(i);
                if (quoted && c == '\\') { i++; continue; }
                if (c == '\'') {
                    if (quoted && i + 1 < body.length() && body.charAt(i + 1) == '\'') { i++; continue; }
                    quoted = !quoted;
                }
                if (quoted) continue;
                if (c == '(') depth++;
                if (c == ')') depth--;
                if (c == ',' && depth == 0) { flat.add(body.substring(start, i)); start = i + 1; }
            }
            flat.add(body.substring(start));
        }
        return flat.size() == 1 ? flat.getFirst() : operator + "(" + String.join(",", flat) + ")";
    }

    public static String decode(String sql) {
        return SIMPLE.matcher(sql).replaceAll("$1'$2'");
    }
}
