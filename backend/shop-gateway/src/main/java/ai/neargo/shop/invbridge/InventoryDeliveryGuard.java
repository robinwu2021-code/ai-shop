package ai.neargo.shop.invbridge;

import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Fail closed: only successful inventory-mirror delivery proves the stock hold was recorded. */
@Component
@ConditionalOnInventory
public class InventoryDeliveryGuard {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public InventoryDeliveryGuard(@Qualifier("dataSource") DataSource platform, ObjectMapper json) {
        this.jdbc = new JdbcTemplate(platform);
        this.json = json;
    }

    public boolean blocked(String skuNo) {
        var candidates = jdbc.query("""
                SELECT e.payload,
                  CASE WHEN (e.event_type='INV_MIRROR_ADJUST' AND e.aggregate_id=?)
                    OR EXISTS (SELECT 1 FROM prd_stock_lock l WHERE l.lock_no=e.aggregate_id AND l.sku_no=?)
                    THEN 1 ELSE 0 END AS scoped
                FROM sys_outbox e
                LEFT JOIN sys_outbox_delivery d ON d.event_no=e.event_no AND d.handler_id='inventory-mirror'
                WHERE e.event_type IN ('INV_MIRROR_RESERVE','INV_MIRROR_ADJUST')
                  AND (CASE WHEN d.event_no IS NOT NULL THEN d.status ELSE e.status END IS NULL
                    OR CASE WHEN d.event_no IS NOT NULL THEN d.status ELSE e.status END <> 'SENT')
                """, (rs, row) -> new Candidate(rs.getString("payload"), rs.getInt("scoped") == 1), skuNo, skuNo);
        for (var candidate : candidates) {
            if (candidate.scoped()) return true;
            try {
                JsonNode root = json.readTree(candidate.payload());
                if (root == null || !root.isObject()) continue;
                if (matches(root, skuNo)) return true;
                JsonNode items = root.get("items");
                if (items != null && items.isArray()) {
                    for (JsonNode item : items) if (matches(item, skuNo)) return true;
                }
            } catch (RuntimeException invalidPayload) {
                // Unscoped historical poison stays FAILED in outbox for operators;
                // it must not block inventory belonging to unrelated merchants.
            }
        }
        return false;
    }

    private record Candidate(String payload, boolean scoped) { }

    private boolean matches(JsonNode value, String skuNo) {
        JsonNode key = value.get("skuNo");
        return key != null && key.isString() && skuNo.equals(key.asString());
    }
}
