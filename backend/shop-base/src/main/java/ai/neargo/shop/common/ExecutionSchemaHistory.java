package ai.neargo.shop.common;

import java.sql.Connection;
import java.sql.SQLException;

/** A Flyway baseline covers history only. The caller must still verify every physical schema invariant. */
public final class ExecutionSchemaHistory {
    private ExecutionSchemaHistory() {}

    public static boolean baselineCovers(
            Connection connection, String schema, String historyTable, int migration) throws SQLException {
        if (!schema.matches("[A-Za-z0-9_]+") || !historyTable.matches("[A-Za-z0-9_]+")) {
            return false;
        }
        try (var statement = connection.prepareStatement(
                "SELECT version, checksum, success FROM `" + schema + "`.`" + historyTable + "` WHERE type='BASELINE'");
                var rows = statement.executeQuery()) {
            if (!rows.next()) {
                return false;
            }
            String version = rows.getString("version");
            return version != null && version.matches("[0-9]{1,9}")
                    && Integer.parseInt(version) >= migration
                    && rows.getObject("checksum") == null && rows.getBoolean("success") && !rows.next();
        }
    }
}
