package ai.neargo.shop.beauty.policy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Canonical form for the frozen execution schema checks and triggers. */
final class BeautyExecutionSql {
    private static final String METADATA_LITERAL =
                "_(?:utf8mb4|utf8|ascii|latin1|binary)"
                        + "(?:"
                        + Pattern.quote("\\")
                        + "){1,2}'[^'\\\\]*(?:"
                        + Pattern.quote("\\")
                        + "){1,2}'";
    private static final Pattern TOKEN =
                Pattern.compile(
                        METADATA_LITERAL
                                + "|"
                                + "\\s+|'(?:''|\\\\.|[^'\\\\])*'|`[^`]+`|<=>|<>|!=|<=|>=|"
                                + "[a-zA-Z_][a-zA-Z0-9_]*|[0-9]+|[(),=<>;+.-]");

    private BeautyExecutionSql() {}

    static List<String> tokens(String sql) {
        Matcher matcher = TOKEN.matcher(sql);
        List<String> result = new ArrayList<>();
        int offset = 0;
        while (offset < sql.length()) {
            matcher.region(offset, sql.length());
            requireValid(matcher.lookingAt());
            String value = matcher.group();
            offset = matcher.end();
            if (value.isBlank() || value.matches("_(utf8mb4|utf8|ascii|latin1|binary)")) {
                continue;
            }
            if (value.startsWith("_") && value.indexOf('\'') >= 0) {
                int first = value.indexOf('\''), end = value.length() - 1;
                while (end > first && value.charAt(end - 1) == '\\') end--;
                value = "'" + value.substring(first + 1, end) + "'";
            }
            if (value.startsWith("`")) {
                value = value.substring(1, value.length() - 1);
            }
            result.add(value.startsWith("'") ? value : value.toLowerCase(Locale.ROOT));
        }
        return result;
    }

    static String expression(String sql) {
        Expression parser = new Expression(tokens(sql));
        String value = parser.or();
        requireValid(parser.at == parser.input.size());
        return value;
    }

    /**
     * Finite expression grammar retains AND/OR/BETWEEN precedence while accepting server-added
     * parentheses.
     */
    private static final class Expression {
        private final List<String> input;
        private int at;

        private Expression(List<String> input) {
            this.input = input;
        }

        private String or() {
            List<String> values = new ArrayList<>();
            values.add(and());
            while (take("or")) {
                values.add(and());
            }
            return values.size() == 1 ? values.getFirst() : associative("or", values);
        }

        private String and() {
            List<String> values = new ArrayList<>();
            values.add(comparison());
            while (take("and")) {
                values.add(comparison());
            }
            return values.size() == 1 ? values.getFirst() : associative("and", values);
        }

        private String comparison() {
            if (take("not")) {
                return "not(" + comparison() + ")";
            }
            String left = arithmetic();
            if (take("is")) {
                boolean negated = take("not");
                expect("null");
                return (negated ? "notnull(" : "isnull(") + left + ")";
            }
            if (take("between")) {
                String lower = atom();
                expect("and");
                return "between(" + left + "," + lower + "," + atom() + ")";
            }
            boolean not = take("not");
            if (take("in")) {
                expect("(");
                List<String> args = arguments();
                return (not ? "notin(" : "in(") + left + "," + String.join(",", args) + ")";
            }
            requireValid(!not);
            if (at < input.size()
                    && Set.of("=", "<=>", "<>", "!=", "<", ">", "<=", ">=", "regexp")
                            .contains(input.get(at))) {
                String operation = input.get(at++);
                String right = arithmetic();
                if (("<>".equals(operation) || "!=".equals(operation))
                        && ("0".equals(left) && right.startsWith("coalesce(")
                                || "0".equals(right) && left.startsWith("coalesce("))) {
                    return booleanTerm("0".equals(left) ? right : left);
                }
                return ("!=".equals(operation)
                                ? "<>"
                                : "regexp".equals(operation) ? "regexp_like" : operation)
                        + "("
                        + left
                        + ","
                        + right
                        + ")";
            }
            return left;
        }

        private String arithmetic() {
            String value = atom();
            while (at < input.size() && ("+".equals(input.get(at)) || "-".equals(input.get(at)))) {
                String operation = input.get(at++);
                value = operation + "(" + value + "," + atom() + ")";
            }
            return value;
        }

        private static String booleanTerm(String value) {
            return value.startsWith("coalesce(") ? "nonzero(" + value + ")" : value;
        }

        private static String associative(String operator, List<String> values) {
            String prefix = operator + "(";
            return prefix
                    + String.join(
                            ",",
                            values.stream()
                                    .map(Expression::booleanTerm)
                                    .map(
                                            value ->
                                                    value.startsWith(prefix) && value.endsWith(")")
                                                            ? value.substring(
                                                                    prefix.length(),
                                                                    value.length() - 1)
                                                            : value)
                                    .toList())
                    + ")";
        }

        private String atom() {
            if (take("(")) {
                String value = or();
                expect(")");
                return value;
            }
            requireValid(at < input.size());
            String value = input.get(at++);
            requireValid(!List.of(")", ",", "and", "or").contains(value));
            if (take("(")) {
                if ("cast".equals(value)) {
                    // V15 uses the MySQL CAST(... AS JSON) grammar, not comma-separated function
                    // arguments.
                    String argument = or();
                    expect("as");
                    expect("json");
                    expect(")");
                    return "cast-json(" + argument + ")";
                }
                return value + "(" + String.join(",", arguments()) + ")";
            }
            return value;
        }

        private List<String> arguments() {
            List<String> args = new ArrayList<>();
            if (!take(")")) {
                args.add(or());
                while (take(",")) {
                    args.add(or());
                }
                expect(")");
            }
            return args;
        }

        private boolean take(String value) {
            if (at < input.size() && input.get(at).equals(value)) {
                at++;
                return true;
            }
            return false;
        }

        private void expect(String value) {
            requireValid(take(value));
        }
    }
    private static void requireValid(boolean valid) {
        if (!valid) throw new IllegalStateException("Unsupported beauty execution schema expression");
    }
}
