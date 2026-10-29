package ai.neargo.shop.infra;

import java.util.List;
import java.util.ArrayList;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared test-only CREATE reader so both schema guards compare actual definitions, not lines. */
public final class SchemaDdl {
    private static final Pattern TABLE =
            Pattern.compile(
                    "\\bCREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?`?(\\w+)`?(?!\\w)",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern NEXT_TABLE =
            Pattern.compile("\\bCREATE\\s+TABLE\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern COLUMN =
            Pattern.compile(
                    "^`?(\\w+)`?\\s+(BIGINT|MEDIUMINT|SMALLINT|TINYINT|INTEGER|INT|VARCHAR|CHAR|DATETIME|TIMESTAMP"
                        + "|DATE|TIME|MEDIUMTEXT|LONGTEXT|TINYTEXT|TEXT|JSON|DECIMAL|NUMERIC|DOUBLE|FLOAT"
                        + "|MEDIUMBLOB|LONGBLOB|BLOB|BOOLEAN|BOOL|VARBINARY|BINARY|BIT|ENUM|SET)(?![\\w])",
                    Pattern.CASE_INSENSITIVE);
    private static final Set<String> CONSTRAINTS =
            Set.of(
                    "CHECK",
                    "CONSTRAINT",
                    "KEY",
                    "UNIQUE",
                    "PRIMARY",
                    "INDEX",
                    "FULLTEXT",
                    "FOREIGN",
                    "SPATIAL");

    private SchemaDdl() {}

    public static String codeText(String sql) {
        return sqlText(sql).code();
    }

    static Map<String, Set<String>> createColumns(String sql) {
        SqlText text = sqlText(sql);
        Map<String, Set<String>> tables = new LinkedHashMap<>();
        Matcher table = TABLE.matcher(text.clean());
        Matcher nextTable = NEXT_TABLE.matcher(text.code());
        while (table.find()) {
            if (Character.toUpperCase(text.code().charAt(table.start())) != 'C') {
                continue;
            }
            int start = table.end();
            while (start < sql.length() && Character.isWhitespace(text.clean().charAt(start))) {
                start++;
            }
            if (start == sql.length() || text.code().charAt(start) != '(') {
                throw malformed("missing CREATE body for " + table.group(1));
            }
            start++;
            int boundary = nextTable.find(start) ? nextTable.start() : sql.length();
            int depth = 1;
            int end = start;
            for (; end < boundary; end++) {
                char ch = text.code().charAt(end);
                if (ch == ';') {
                    throw malformed("unclosed CREATE " + table.group(1));
                }
                if (ch == '(') {
                    depth++;
                } else if (ch == ')' && --depth == 0) {
                    break;
                }
            }
            if (depth != 0) {
                throw malformed("unclosed CREATE " + table.group(1));
            }
            // The SQL after the matching ')' is table options, never another CREATE body.
            int tail = end + 1;
            int tailDepth = 0;
            while (tail < boundary && text.code().charAt(tail) != ';') {
                char ch = text.code().charAt(tail);
                if (ch == '(') {
                    tailDepth++;
                } else if (ch == ')' && --tailDepth < 0) {
                    throw malformed("unmatched table-tail parenthesis");
                }
                tail++;
            }
            if (tailDepth != 0) {
                throw malformed("unclosed table-tail parenthesis");
            }
            if (tail == boundary) {
                throw malformed("missing table terminator for " + table.group(1));
            }
            Set<String> columns = new LinkedHashSet<>();
            int definitionStart = start;
            depth = 0;
            for (int i = start; i <= end; i++) {
                char ch = text.code().charAt(i);
                if (ch == '(') {
                    depth++;
                } else if (ch == ')' && i < end) {
                    depth--;
                }
                if (i == end || (ch == ',' && depth == 0)) {
                    Matcher column =
                            COLUMN.matcher(text.clean().substring(definitionStart, i).trim());
                    if (column.find()
                            && !CONSTRAINTS.contains(column.group(1).toUpperCase(Locale.ROOT))) {
                        columns.add(column.group(1));
                    }
                    definitionStart = i + 1;
                }
            }
            tables.put(table.group(1), columns);
            table.region(tail + 1, sql.length());
        }
        return tables;
    }

    /** Independent client-delimiter reader; quoted semicolons and compound trigger bodies remain intact. */
    static List<String> statements(String sql) {
        SqlText text = sqlText(sql);
        var result = new ArrayList<String>();
        String delimiter = ";";
        int begin = 0;
        for (int at = 0; at < sql.length();) {
            if ((at == 0 || sql.charAt(at - 1) == '\n') && text.code().startsWith("DELIMITER ", at)) {
                if (!text.clean().substring(begin, at).isBlank()) { throw malformed("delimiter with pending SQL"); }
                int end = sql.indexOf('\n', at);
                if (end < 0) { end = sql.length(); }
                delimiter = text.clean().substring(at + 10, end).trim();
                if (!delimiter.equals(";") && !delimiter.equals("$$")) { throw malformed("unknown delimiter"); }
                at = end;
                begin = end;
            } else if (text.code().startsWith(delimiter, at)) {
                String statement = text.clean().substring(begin, at).trim();
                if (!statement.isEmpty()) { result.add(statement); }
                at += delimiter.length();
                begin = at;
            } else { at++; }
        }
        if (!text.clean().substring(begin).isBlank()) { throw malformed("unterminated SQL"); }
        return result;
    }

    /** All named checks/unique constraints and column definitions, independently read from actual CREATE text. */
    static Map<String, Map<String, String>> definitions(String sql) {
        SqlText text = sqlText(sql);
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        Matcher table = TABLE.matcher(text.clean());
        while (table.find()) {
            if (Character.toUpperCase(text.code().charAt(table.start())) != 'C') { continue; }
            int begin = text.code().indexOf('(', table.end());
            int depth = 1;
            int end = begin + 1;
            for (; end < sql.length() && depth != 0; end++) {
                if (text.code().charAt(end) == '(') { depth++; }
                if (text.code().charAt(end) == ')') { depth--; }
            }
            if (depth != 0) { throw malformed("unclosed definition"); }
            Map<String, String> parts = new LinkedHashMap<>();
            int start = begin + 1;
            depth = 0;
            for (int at = start; at < end; at++) {
                char ch = text.code().charAt(at);
                if (ch == '(') { depth++; }
                if (ch == ')' && at < end - 1) { depth--; }
                if (at == end - 1 || ch == ',' && depth == 0) {
                    String definition = text.clean().substring(start, at).trim();
                    Matcher named = Pattern.compile("(?i)^CONSTRAINT\\s+(\\w+)\\s+(.+)$", Pattern.DOTALL).matcher(definition);
                    String key;
                    if (named.matches()) { key = named.group(1); }
                    else { key = definition.split("\\s+", 2)[0]; }
                    if (parts.putIfAbsent(key, definition) != null) { throw malformed("duplicate definition " + key); }
                    start = at + 1;
                }
            }
            result.put(table.group(1), parts);
        }
        return result;
    }

    static void applyCommonAlters(Map<String, Set<String>> tables, String sql) {
        for (String statement : statements(sql)) {
            Matcher add = Pattern.compile("(?is)^ALTER TABLE (\\w+) ADD COLUMN (\\w+) .+$").matcher(statement);
            if (add.matches()) {
                Set<String> columns = tables.get(add.group(1));
                if (columns == null || !columns.add(add.group(2))) { throw malformed("invalid common ADD"); }
            }
        }
    }

    /** Normalize formatting only; literals and boolean parentheses retain their meaning. */
    static String normalized(String sql) {
        String code = sqlText(sql).code();
        StringBuilder result = new StringBuilder();
        for (int at = 0; at < sql.length(); at++) {
            char ch = sql.charAt(at);
            if (code.charAt(at) == ' ' && !Character.isWhitespace(ch)) { result.append(ch); }
            else if (!Character.isWhitespace(ch)) { result.append(Character.toLowerCase(ch)); }
        }
        return result.toString();
    }

    /** Independent finite conversion for the scalar literal-IN checks retained by the H2 renderer. */
    static String h2Check(String sql) {
        Pattern literalIn = Pattern.compile("(?i)(\\w+)\\s+(NOT\\s+)?IN\\s*\\(((?:'[^']*'|[+-]?\\d+)(?:\\s*,\\s*(?:'[^']*'|[+-]?\\d+))*)\\)");
        Matcher in = literalIn.matcher(sql);
        StringBuilder out = new StringBuilder();
        while (in.find()) {
            String column = in.group(1);
            StringBuilder converted = new StringBuilder("(CASE " + column);
            for (String value : in.group(3).split(",")) { converted.append(" WHEN ").append(value.trim()).append(" THEN TRUE"); }
            converted.append(" ELSE CASE WHEN ").append(column).append(" IS NULL THEN NULL ELSE FALSE END END)");
            in.appendReplacement(out, Matcher.quoteReplacement((in.group(2) == null ? "" : "NOT ") + converted));
        }
        in.appendTail(out);
        String converted = out.toString().replaceAll("(?i)JSON_VALID\\((\\w+)\\)", "($1 IS JSON)");
        Matcher microsecond = Pattern.compile("(?i)\\bMICROSECOND\\(\\s*([A-Za-z_]\\w*)\\s*\\)")
                .matcher(sqlText(converted).code());
        StringBuilder projected = new StringBuilder();
        int cursor = 0;
        while (microsecond.find()) {
            projected.append(converted, cursor, microsecond.start())
                    .append("EXTRACT(MICROSECOND FROM ").append(microsecond.group(1)).append(")");
            cursor = microsecond.end();
        }
        return projected.append(converted, cursor, converted.length()).toString();
    }

    private static SqlText sqlText(String sql) {
        char[] clean = sql.toCharArray();
        char[] code = sql.toCharArray();
        for (int i = 0; i < sql.length(); ) {
            int start = i;
            char ch = sql.charAt(i);
            boolean line =
                    ch == '#'
                            || (sql.startsWith("--", i)
                                    && (i + 2 == sql.length()
                                            || Character.isWhitespace(sql.charAt(i + 2))));
            boolean block = sql.startsWith("/*", i);
            if (line || block) {
                if (line) {
                    while (i < sql.length() && sql.charAt(i) != '\n') {
                        i++;
                    }
                } else {
                    i = sql.indexOf("*/", i + 2);
                    if (i < 0) {
                        throw malformed("unclosed comment");
                    }
                    i += 2;
                }
                Arrays.fill(clean, start, i, ' ');
                Arrays.fill(code, start, i, ' ');
            } else if (ch == '\'' || ch == '"' || ch == '`') {
                i++;
                boolean closed = false;
                while (i < sql.length()) {
                    if (sql.charAt(i) == '\\') {
                        i += 2;
                        continue;
                    }
                    if (sql.charAt(i++) != ch) {
                        continue;
                    }
                    if (i < sql.length() && sql.charAt(i) == ch) {
                        i++;
                        continue;
                    }
                    closed = true;
                    break;
                }
                if (!closed) {
                    throw malformed("unclosed quote");
                }
                Arrays.fill(code, start, i, ' ');
            } else {
                i++;
            }
        }
        return new SqlText(new String(clean), new String(code));
    }

    private static IllegalArgumentException malformed(String reason) {
        return new IllegalArgumentException("Malformed SQL: " + reason);
    }

    private record SqlText(String clean, String code) {}
}
