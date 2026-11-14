package ai.neargo.shop.report.config;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;
import java.util.regex.Pattern;

import javax.sql.DataSource;

/** Positive database identity and effective grant check before any report migration. */
public final class ReportDatabaseIdentityGuard {
    private static final Pattern GRANT = Pattern.compile("(?is)^GRANT\\s+(.+?)\\s+ON\\s+(.+?)\\s+TO\\s+.+$");
    private static final Set<String> DML = Set.of("SELECT", "INSERT", "UPDATE", "DELETE");
    private static final Set<String> DDL = Set.of("SELECT", "INSERT", "UPDATE", "DELETE", "CREATE", "ALTER", "INDEX", "DROP");

    public void verify(DataSource source, ReportStoreProperties props, ReportStoreProperties.AccessRole role) {
        if (props.getExpectedCatalog() == null || props.getExpectedCatalog().isBlank()
                || props.getExpectedInstance() == null || props.getExpectedInstance().isBlank()) {
            throw new IllegalStateException("REPORT_DATABASE_IDENTITY_NOT_CONFIGURED");
        }
        try (Connection connection = source.getConnection()) {
            String catalog = connection.getCatalog();
            String product = connection.getMetaData().getDatabaseProductName();
            String instance;
            if ("MySQL".equals(product)) {
                try (var statement = connection.createStatement(); var rs = statement.executeQuery("SELECT @@server_uuid")) {
                    if (!rs.next()) throw new IllegalStateException("REPORT_DATABASE_IDENTITY_UNAVAILABLE");
                    instance = rs.getString(1);
                }
            } else if ("H2".equals(product) && connection.getMetaData().getURL().startsWith("jdbc:h2:mem:")) {
                // Embedded memory databases have no network alias. Native MySQL proofs remain mandatory.
                instance = connection.getMetaData().getURL();
            } else throw new IllegalStateException("REPORT_DATABASE_ENGINE_UNVERIFIED");
            if (!props.getExpectedCatalog().equals(catalog) || !props.getExpectedInstance().equals(instance)) {
                throw new IllegalStateException("REPORT_DATABASE_IDENTITY_MISMATCH");
            }
            try (var statement = connection.createStatement(); var rs = statement.executeQuery("""
                    SELECT table_name FROM information_schema.tables
                    WHERE table_schema = DATABASE()
                    """)) {
                while (rs.next()) {
                    String table = rs.getString(1).toLowerCase(Locale.ROOT);
                    if (!table.startsWith("rpt_")) throw new IllegalStateException("REPORT_FOREIGN_SCHEMA_CONTENT");
                }
            }
            if ("MySQL".equals(product)) {
                Set<String> effective = new HashSet<>();
                try (var statement = connection.createStatement(); var rs = statement.executeQuery("SHOW GRANTS")) {
                    while (rs.next()) {
                        String grant = rs.getString(1);
                        verifyGrant(grant, catalog, role);
                        var parsed=GRANT.matcher(grant);
                        if (parsed.matches() && !parsed.group(1).equalsIgnoreCase("USAGE"))
                            Arrays.stream(parsed.group(1).toUpperCase(Locale.ROOT).split(","))
                                    .map(String::trim).forEach(effective::add);
                    }
                }
                Set<String> required=role==ReportStoreProperties.AccessRole.READER?Set.of("SELECT")
                        :role==ReportStoreProperties.AccessRole.WRITER?DML:DDL;
                if (!effective.containsAll(required)) throw new IllegalStateException("REPORT_REQUIRED_DATABASE_PRIVILEGE_MISSING");
            }
        } catch (SQLException ex) {
            throw new ReportDatabaseConnectionException();
        }
    }

    static void verifyGrant(String grant, String catalog, ReportStoreProperties.AccessRole role) {
        var match = GRANT.matcher(grant);
        if (!match.matches()) throw new IllegalStateException("REPORT_EFFECTIVE_GRANTS_UNPROVEN");
        String permissions = match.group(1).toUpperCase(Locale.ROOT);
        String target = match.group(2).replace("`", "").replace("\\_", "_");
        if (permissions.equals("USAGE") && target.equals("*.*")) return;
        if (!target.equals(catalog + ".*") || grant.toUpperCase(Locale.ROOT).contains("WITH GRANT OPTION")) {
            throw new IllegalStateException("REPORT_EXCESS_DATABASE_PRIVILEGE");
        }
        Set<String> allowed = role == ReportStoreProperties.AccessRole.READER ? Set.of("SELECT")
                : role == ReportStoreProperties.AccessRole.WRITER ? DML : DDL;
        for (String permission : permissions.split(",")) {
            if (!allowed.contains(permission.trim())) throw new IllegalStateException("REPORT_EXCESS_DATABASE_PRIVILEGE");
        }
    }

    /** No underlying SQL exception message/cause: JDBC URLs and credentials must not reach operators. */
    public static final class ReportDatabaseConnectionException extends IllegalStateException {
        public ReportDatabaseConnectionException() { super("REPORT_DATABASE_UNAVAILABLE"); }
    }
}
