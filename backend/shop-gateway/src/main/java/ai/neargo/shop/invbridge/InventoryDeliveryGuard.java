package ai.neargo.shop.invbridge;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.shop.product.mapper.ProductMappers.StockLockMapper;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Fail closed: only successful inventory-mirror delivery proves the stock hold was recorded.
 *
 * <p>Outbox lives in the platform store, stock locks in the trade store: two reads, never one join.
 */
@Component
@ConditionalOnInventory
public class InventoryDeliveryGuard {
    private final JdbcTemplate jdbc;
    private final StockLockMapper locks;
    private final ObjectMapper json;

    public InventoryDeliveryGuard(@Qualifier("dataSource") DataSource platform, StockLockMapper locks, ObjectMapper json) {
        this.jdbc = new JdbcTemplate(platform);
        this.locks = locks;
        this.json = json;
    }

    public boolean blocked(String skuNo) {
        List<Candidate> candidates = jdbc.query("""
                SELECT e.event_type, e.aggregate_id, e.payload
                FROM sys_outbox e
                LEFT JOIN sys_outbox_delivery d ON d.event_no=e.event_no AND d.handler_id='inventory-mirror'
                WHERE e.event_type IN ('INV_MIRROR_RESERVE','INV_MIRROR_ADJUST')
                  AND (CASE WHEN d.event_no IS NOT NULL THEN d.status ELSE e.status END IS NULL
                    OR CASE WHEN d.event_no IS NOT NULL THEN d.status ELSE e.status END <> 'SENT')
                """, (rs, row) -> new Candidate(rs.getString("event_type"), rs.getString("aggregate_id"),
                rs.getString("payload")));
        if (candidates.isEmpty()) return false;
        Set<String> lockedHere = lockedHere(skuNo, candidates);
        for (var candidate : candidates) {
            if ("INV_MIRROR_ADJUST".equals(candidate.type()) && skuNo.equals(candidate.aggregate())) return true;
            if (candidate.aggregate() != null && lockedHere.contains(candidate.aggregate())) return true;
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

    private Set<String> lockedHere(String skuNo, List<Candidate> candidates) {
        Set<String> lockNos = new HashSet<>();
        for (var candidate : candidates) if (candidate.aggregate() != null) lockNos.add(candidate.aggregate());
        if (lockNos.isEmpty()) return Set.of();
        return new HashSet<>(DataScopeContext.executeWithoutScope(() -> locks.lockNosHoldingSku(skuNo, lockNos)));
    }

    private record Candidate(String type, String aggregate, String payload) { }

    private boolean matches(JsonNode value, String skuNo) {
        JsonNode key = value.get("skuNo");
        return key != null && key.isString() && skuNo.equals(key.asString());
    }
}
