package ai.neargo.shop.beauty.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Small closed grammar for the frozen numeric/time invariants; grouping is retained in the operator tree. */
final class BeautyCheckExpression {
    private static final Pattern TOKEN = Pattern.compile(
            "\\G\\s*([a-z_][a-z0-9_]*|[0-9]+|<=|>=|<>|!=|[()=<>+,])");
    private final List<String> tokens;
    private int position;

    private BeautyCheckExpression(List<String> tokens) {
        this.tokens = tokens;
    }

    static String canonical(String expression) {
        String input = expression.strip().toLowerCase(Locale.ROOT).replace("`", "");
        List<String> tokens = new ArrayList<>();
        var matcher = TOKEN.matcher(input);
        int end = 0;
        while (matcher.find()) {
            tokens.add(matcher.group(1));
            end = matcher.end();
        }
        require(end == input.length() && !tokens.isEmpty());
        var parser = new BeautyCheckExpression(tokens);
        String result = parser.or();
        require(parser.position == tokens.size());
        return result;
    }

    private String or() {
        String result = and();
        while (take("or")) {
            result = "or(" + result + "," + and() + ")";
        }
        return result;
    }

    private String and() {
        String result = comparison();
        while (take("and")) {
            result = "and(" + result + "," + comparison() + ")";
        }
        return result;
    }

    private String comparison() {
        String left = addition();
        if (take("is")) {
            boolean not = take("not");
            expect("null");
            return (not ? "is-not-null(" : "is-null(") + left + ")";
        }
        if (take("in")) {
            expect("(");
            List<String> values = new ArrayList<>();
            do {
                values.add(addition());
            } while (take(","));
            expect(")");
            return "in(" + left + "," + String.join(",", values) + ")";
        }
        if (position < tokens.size() && Set.of("=", "!=", "<>", "<", ">", "<=", ">=")
                .contains(tokens.get(position))) {
            String operator = tokens.get(position++);
            return operator + "(" + left + "," + addition() + ")";
        }
        return left;
    }

    private String addition() {
        String result = primary();
        while (take("+")) {
            result = "+(" + result + "," + primary() + ")";
        }
        return result;
    }

    private String primary() {
        if (take("(")) {
            String result = or();
            expect(")");
            return result;
        }
        require(position < tokens.size());
        String token = tokens.get(position++);
        require(token.matches("[a-z_][a-z0-9_]*|[0-9]+"));
        if (take("(")) {
            require(Set.of("json_valid", "octet_length", "length").contains(token));
            String argument = primary();
            expect(")");
            // MySQL stores OCTET_LENGTH as its byte-length synonym LENGTH in CHECK_CLAUSE.
            String function = "octet_length".equals(token) ? "length" : token;
            return function + "(" + argument + ")";
        }
        return token;
    }

    private boolean take(String token) {
        if (position < tokens.size() && token.equals(tokens.get(position))) {
            position++;
            return true;
        }
        return false;
    }

    private void expect(String token) {
        require(take(token));
    }

    private static void require(boolean valid) {
        if (!valid) {
            throw new IllegalStateException("Unsupported or malformed beauty CHECK expression");
        }
    }
}
