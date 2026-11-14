package ai.neargo.shop.beauty.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Closed numeric/time/ASCII-enum grammar; grouping is retained in the operator tree. */
final class BeautyCheckExpression {
    private static final Pattern TOKEN = Pattern.compile(
            "\\G\\s*((?:_utf8mb4|_latin1)\\\\'[a-zA-Z_]+\\\\'|`[^`]+`|'(?:''|[^'])*'"
                    + "|[a-zA-Z_][a-zA-Z0-9_]*|[0-9]+|<=|>=|<>|!=|[()=<>+,])");
    private final List<String> tokens;
    private int position;

    private BeautyCheckExpression(List<String> tokens) {
        this.tokens = tokens;
    }

    static String canonical(String expression) {
        String input = expression.strip();
        List<String> tokens = new ArrayList<>();
        var matcher = TOKEN.matcher(input);
        int end = 0;
        while (matcher.find()) {
            String token = matcher.group(1);
            // Decode only a complete charset-prefixed ASCII enum token, never text inside another literal.
            int escapedLiteral = token.indexOf("\\'");
            if (escapedLiteral > 0
                    && (token.startsWith("_utf8mb4") || token.startsWith("_latin1"))) {
                token = "'" + token.substring(escapedLiteral + 2, token.length() - 2) + "'";
            }
            if (token.startsWith("`")) {
                require(token.matches("`[a-zA-Z_][a-zA-Z0-9_]*`"));
                token = token.substring(1, token.length() - 1);
            }
            tokens.add(token.startsWith("'") ? token : token.toLowerCase(Locale.ROOT));
            end = matcher.end();
        }
        require(end == input.length() && !tokens.isEmpty());
        var parser = new BeautyCheckExpression(tokens);
        Node result = parser.or();
        require(parser.position == tokens.size());
        return result.text();
    }

    private Node or() {
        Node result = and();
        while (take("or")) {
            result = booleanNode("or", result, and());
        }
        return result;
    }

    private Node and() {
        Node result = comparison();
        while (take("and")) {
            result = booleanNode("and", result, comparison());
        }
        return result;
    }

    private Node comparison() {
        Node left = addition();
        if (take("is")) {
            boolean not = take("not");
            expect("null");
            return value((not ? "is-not-null(" : "is-null(") + left.text() + ")");
        }
        if (take("in")) {
            expect("(");
            List<String> values = new ArrayList<>();
            do {
                values.add(addition().text());
            } while (take(","));
            expect(")");
            return value("in(" + left.text() + "," + String.join(",", values) + ")");
        }
        if (position < tokens.size() && Set.of("=", "!=", "<>", "<", ">", "<=", ">=")
                .contains(tokens.get(position))) {
            String operator = tokens.get(position++);
            return value(operator + "(" + left.text() + "," + addition().text() + ")");
        }
        return left;
    }

    private Node addition() {
        Node result = primary();
        while (take("+")) {
            result = value("+(" + result.text() + "," + primary().text() + ")");
        }
        return result;
    }

    private Node primary() {
        if (take("(")) {
            Node result = or();
            expect(")");
            return result;
        }
        require(position < tokens.size());
        String token = tokens.get(position++);
        if (Set.of("_utf8mb4", "_latin1").contains(token)) {
            require(position < tokens.size() && tokens.get(position).startsWith("'"));
            token = tokens.get(position++);
        }
        if (token.startsWith("'")) {
            return value("literal(" + token + ")");
        }
        require(token.matches("[a-z_][a-z0-9_]*|[0-9]+"));
        if (take("(")) {
            require(Set.of("json_valid", "octet_length", "length", "char_length",
                    "json_type", "json_length").contains(token));
            Node argument = primary();
            expect(")");
            // MySQL stores OCTET_LENGTH as its byte-length synonym LENGTH in CHECK_CLAUSE.
            String function = "octet_length".equals(token) ? "length" : token;
            return value(function + "(" + argument.text() + ")");
        }
        return value(token);
    }

    /**
     * Same boolean operators are associative even for SQL NULL; mixed groups and order stay exact.
     */
    private record Node(String text, String booleanOperator, List<Node> terms) {}

    private static Node value(String text) {
        return new Node(text, null, List.of());
    }

    private static Node booleanNode(String operator, Node left, Node right) {
        var terms = new ArrayList<Node>();
        terms.addAll(operator.equals(left.booleanOperator()) ? left.terms() : List.of(left));
        terms.addAll(operator.equals(right.booleanOperator()) ? right.terms() : List.of(right));
        String text = terms.getFirst().text();
        for (int index = 1; index < terms.size(); index++) {
            text = operator + "(" + text + "," + terms.get(index).text() + ")";
        }
        return new Node(text, operator, List.copyOf(terms));
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
