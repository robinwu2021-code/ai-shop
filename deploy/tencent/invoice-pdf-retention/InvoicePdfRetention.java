import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;

public final class InvoicePdfRetention {
    private static final String EXPIRED_INVOICE_EXPORTS = """
            SELECT e.tenant_no, e.entity_no, e.store_no, e.export_no, e.object_key
            FROM prn_export_task e
            WHERE e.status IN ('READY', 'EXPIRED') AND e.expires_at <= UTC_TIMESTAMP(3)
              AND (
                (e.source_type IN ('INVOICE_PROOF', 'INVOICE_DETAIL')
                  AND BINARY JSON_UNQUOTE(JSON_EXTRACT(e.source_snapshot, '$.invoiceDocument.fact.factNo'))
                    = BINARY e.source_no
                  AND BINARY JSON_UNQUOTE(JSON_EXTRACT(e.source_snapshot, '$.invoiceDocument.fact.scene'))
                    = BINARY e.source_type
                )
                OR (e.source_type = 'SIMULATION' AND EXISTS (
                  SELECT 1 FROM prn_simulation_run s
                  WHERE s.tenant_no = e.tenant_no AND s.entity_no = e.entity_no AND s.store_no = e.store_no
                    AND s.simulation_no = e.source_no AND s.source_type IN ('FACT', 'JOB')
                    AND s.scene IN ('INVOICE_PROOF', 'INVOICE_DETAIL')
                ))
                OR (e.source_type = 'JOB' AND EXISTS (
                  SELECT 1 FROM prn_job j
                  WHERE CAST(j.tenant_no AS BINARY) = CAST(e.tenant_no AS BINARY)
                    AND CAST(j.entity_no AS BINARY) = CAST(e.entity_no AS BINARY)
                    AND CAST(j.store_no AS BINARY) = CAST(e.store_no AS BINARY)
                    AND CAST(j.job_no AS BINARY) = CAST(e.source_no AS BINARY)
                    AND j.scene IN ('INVOICE_PROOF', 'INVOICE_DETAIL')
                ))
                OR (e.source_type = 'BATCH' AND EXISTS (
                  SELECT 1 FROM prn_job j
                  WHERE CAST(j.tenant_no AS BINARY) = CAST(e.tenant_no AS BINARY)
                    AND CAST(j.entity_no AS BINARY) = CAST(e.entity_no AS BINARY)
                    AND CAST(j.store_no AS BINARY) = CAST(e.store_no AS BINARY)
                    AND CAST(j.batch_no AS BINARY) = CAST(e.source_no AS BINARY)
                  GROUP BY j.batch_no
                  HAVING COUNT(*) = SUM(CASE WHEN j.scene IN ('INVOICE_PROOF', 'INVOICE_DETAIL') THEN 1 ELSE 0 END)
                ))
              )
            """;

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !("--check".equals(args[0]) || "--apply".equals(args[0]))) {
            throw new IllegalArgumentException("Expected --check or --apply and an artifact root");
        }
        boolean apply = "--apply".equals(args[0]);
        Path root = Path.of(args[1]).toRealPath();
        int candidates = 0;
        int removed = 0;
        int skippedUnsafe = 0;
        try (Connection conn = DriverManager.getConnection(
                System.getenv("SPRING_DATASOURCE_URL"), System.getenv("SPRING_DATASOURCE_USERNAME"),
                System.getenv("SPRING_DATASOURCE_PASSWORD"))) {
            conn.setReadOnly(true);
            try (var stmt = conn.prepareStatement(EXPIRED_INVOICE_EXPORTS)) {
                stmt.setQueryTimeout(30);
                try (var rows = stmt.executeQuery()) {
                    while (rows.next()) {
                        String key = rows.getString("object_key");
                        String prefix = rows.getString("tenant_no") + "/" + rows.getString("entity_no") + "/"
                                + rows.getString("store_no") + "/print/";
                        if (!key.matches("[A-Za-z0-9_-]+/[A-Za-z0-9_-]+/[A-Za-z0-9_-]+/print/[0-9]{6}/[A-Za-z0-9_-]+[.]pdf")
                                || !key.startsWith(prefix) || !key.endsWith("/" + rows.getString("export_no") + ".pdf")) {
                            skippedUnsafe++;
                            continue;
                        }
                        Path file = root.resolve(key);
                        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                            continue;
                        }
                        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !file.toRealPath().startsWith(root)) {
                            skippedUnsafe++;
                            continue;
                        }
                        candidates++;
                        if (apply && Files.deleteIfExists(file)) {
                            removed++;
                        }
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("Invoice PDF cleanup SQL failure: SQLState=" + e.getSQLState() + ", code=" + e.getErrorCode());
            System.exit(1);
        }
        System.out.printf("%s invoice-pdf-retention mode=%s candidates=%d removed=%d skippedUnsafe=%d%n",
                Instant.now(), args[0], candidates, removed, skippedUnsafe);
    }
}
