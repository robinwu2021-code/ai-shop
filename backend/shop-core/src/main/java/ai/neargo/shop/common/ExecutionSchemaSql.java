package ai.neargo.shop.common;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Canonical form for the frozen execution schema checks and triggers. */
public final class ExecutionSchemaSql {
    private static final Pattern TOKEN = Pattern.compile("_(?:utf8mb4|utf8|ascii|latin1|binary)\\\\\'[^\'\\\\]*\\\\\'|\\s+|'(?:''|\\\\.|[^'\\\\])*'|`[^`]+`|<=>|<>|!=|<=|>=|"
                + "[a-zA-Z_][a-zA-Z0-9_]*|[0-9]+|[(),=<>;+.-]");

    private ExecutionSchemaSql() {}

    // Preserve literal bytes and boolean grouping. Only quoting, whitespace and charset introducers normalize.
    public static List<String> tokens(String sql) {
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
            // MySQL 9 reports charset-prefixed delimiters as escaped quotes in CHECK_CLAUSE.
            int escaped = value.indexOf("\\'");
            if (value.startsWith("_") && escaped > 0) {
                value = "'" + value.substring(escaped + 2, value.length() - 2) + "'";
            }
            if (value.startsWith("`")) {
                value = value.substring(1, value.length() - 1);
            }
            result.add(value.startsWith("'") ? value : value.toLowerCase(Locale.ROOT));
        }
        return result;
    }

    public static String expression(String sql) {
        Expression parser = new Expression(tokens(sql));
        String value = parser.or();
        requireValid(parser.at == parser.input.size());
        return value;
    }

    /** Finite expression grammar retains AND/OR/BETWEEN precedence while accepting server-added parentheses. */
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
            return logical("or", values);
        }

        private String and() {
            List<String> values = new ArrayList<>();
            values.add(comparison());
            while (take("and")) {
                values.add(comparison());
            }
            return logical("and", values);
        }

        /** MySQL flattens adjacent identical logical operators in its stored clauses. */
        private static String logical(String operator, List<String> values) {
            if (values.size() == 1) {
                return values.getFirst();
            }
            String prefix = operator + "(";
            return prefix + values.stream()
                    .map(value -> value.startsWith(prefix)
                            ? value.substring(prefix.length(), value.length() - 1) : value)
                    .collect(Collectors.joining(",")) + ")";
        }

        private String comparison() {
            if (take("not")) {
                return "not(" + comparison() + ")";
            }
            String left = atom();
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
            if (at < input.size() && Set.of("=", "<>", "!=", "<", ">", "<=", ">=", "regexp")
                    .contains(input.get(at))) {
                String operation = input.get(at++);
                String right = atom();
                return ("!=".equals(operation) ? "<>" : operation) + "(" + left + "," + right + ")";
            }
            return left;
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
                List<String> args = arguments();
                String function = switch (value) {
                    case "regexp_like" -> args.size() == 2 ? "regexp" : value;
                    case "octet_length" -> "length";
                    default -> value;
                };
                return function + "(" + String.join(",", args) + ")";
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
        if (!valid) {
            throw new IllegalStateException("Unsupported execution schema expression");
        }
    }
}
