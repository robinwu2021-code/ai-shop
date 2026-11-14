package ai.neargo.shop.config;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/** Wire-only adaptation of frozen historical DDL; resolved Flyway resource checksums stay intact. */
final class FlywayMysql9MigrationSql {
    private static final Pattern COLLATION = Pattern.compile(
            "'(?:''|\\\\.|[^'\\\\])*'|\"(?:\"\"|\\\\.|[^\"\\\\])*\"|`(?:``|[^`])*`"
                    + "|/\\*[\\s\\S]*?\\*/|--[^\\r\\n]*|#[^\\r\\n]*"
                    + "|(\\bCOLLATE\\s*=?\\s*)utf8mb4_uca1400_ai_ci\\b", Pattern.CASE_INSENSITIVE);
    private static final String IDENTIFIER = "(`?[a-zA-Z0-9_]+`?)";
    private static final Pattern LEADING_COMMENTS = Pattern.compile(
            "(?s)\\A(?:\\s+|--[^\\r\\n]*(?:\\r?\\n|$)|/\\*.*?\\*/)*");
    private static final Pattern CREATE_INDEX = Pattern.compile(
            "(?is)^CREATE\\s+(UNIQUE\\s+)?INDEX\\s+IF\\s+NOT\\s+EXISTS\\s+" + IDENTIFIER
                    + "\\s+ON\\s+" + IDENTIFIER + "(\\s*\\(.*)$");
    private static final Pattern ALTER = Pattern.compile(
            "(?is)^ALTER\\s+TABLE\\s+" + IDENTIFIER + "\\s+(.+?);?\\s*$");
    private static final Pattern ADD_COLUMN = Pattern.compile(
            "(?is)^\\s*ADD\\s+COLUMN\\s+IF\\s+NOT\\s+EXISTS\\s+" + IDENTIFIER + "(?=\\s|$)(.*)$");
    private static final Pattern DROP_INDEX = Pattern.compile(
            "(?is)^\\s*DROP\\s+INDEX\\s+IF\\s+EXISTS\\s+" + IDENTIFIER + "\\s*$");
    private static final Pattern ADD_INDEX = Pattern.compile(
            "(?is)^\\s*ADD\\s+(UNIQUE\\s+)?KEY\\s+IF\\s+NOT\\s+EXISTS\\s+" + IDENTIFIER + "(?=\\s|\\(|$)(.*)$");

    private FlywayMysql9MigrationSql() {}

    static String rewrite(Connection connection, String sql) throws SQLException {
        var matcher = COLLATION.matcher(sql);
        var changed = new StringBuilder();
        while (matcher.find()) {
            String replacement = matcher.group(1) == null
                    ? matcher.group() : matcher.group(1) + "utf8mb4_0900_ai_ci";
            matcher.appendReplacement(changed, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(changed);
        String adapted = changed.toString();
        String statement = LEADING_COMMENTS.matcher(adapted).replaceFirst("");
        var create = CREATE_INDEX.matcher(statement);
        if (create.matches()) {
            if (exists(connection, "statistics", create.group(3).replace("`", ""), create.group(2))) return "DO 0";
            return "CREATE " + Objects.toString(create.group(1), "") + "INDEX " + create.group(2)
                    + " ON " + create.group(3) + create.group(4);
        }
        var alter = ALTER.matcher(statement);
        if (!alter.matches()) return adapted;
        String table = alter.group(1).replace("`", "");
        List<String> clauses = new ArrayList<>();
        boolean conditional = false;
        for (String part : splitClauses(alter.group(2))) {
            var column = ADD_COLUMN.matcher(part);
            var drop = DROP_INDEX.matcher(part);
            var index = ADD_INDEX.matcher(part);
            if (column.matches()) {
                conditional = true;
                if (!exists(connection, "columns", table, column.group(1))) {
                    clauses.add("ADD COLUMN " + column.group(1) + column.group(2));
                }
            } else if (drop.matches()) {
                conditional = true;
                if (exists(connection, "statistics", table, drop.group(1))) {
                    clauses.add("DROP INDEX " + drop.group(1));
                }
            } else if (index.matches()) {
                conditional = true;
                if (!exists(connection, "statistics", table, index.group(2))) {
                    clauses.add("ADD " + Objects.toString(index.group(1), "")
                            + "KEY " + index.group(2) + index.group(3));
                }
            } else {
                clauses.add(part);
            }
        }
        if (!conditional) return adapted;
        return clauses.isEmpty() ? "DO 0" : "ALTER TABLE " + alter.group(1) + " " + String.join(",", clauses);
    }

    private static boolean exists(Connection connection, String kind, String table, String name)
            throws SQLException {
        String identity = kind.equals("columns") ? "column_name" : "index_name";
        try (var query = connection.prepareStatement("SELECT 1 FROM information_schema." + kind
                + " WHERE table_schema=DATABASE() AND table_name=? AND " + identity + "=? LIMIT 1")) {
            query.setString(1, table);
            query.setString(2, name.replace("`", ""));
            try (var result = query.executeQuery()) {
                return result.next();
            }
        }
    }

    private static List<String> splitClauses(String sql) {
        List<String> clauses = new ArrayList<>();
        int start = 0;
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < sql.length(); i++) {
            char token = sql.charAt(i);
            if (quote != 0) {
                if (token == '\\') {
                    i++;
                } else if (token == quote) {
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) i++;
                    else quote = 0;
                }
                continue;
            }
            if (token == '\'' || token == '"' || token == '`') quote = token;
            else if (token == '(') depth++;
            else if (token == ')') depth--;
            else if (token == ',' && depth == 0) {
                clauses.add(sql.substring(start, i));
                start = i + 1;
            }
        }
        clauses.add(sql.substring(start));
        return clauses;
    }
}
