package ai.neargo.shop.infra;

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
