package ai.neargo.inventory.domain.service.impl;

import ai.neargo.inventory.domain.config.ConditionalOnInventory;
import ai.neargo.inventory.domain.service.InventoryOrderTraceService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Queries the inventory ledger and source documents without joining or mutating the platform database. */
@Service
@ConditionalOnInventory
public class InventoryOrderTraceServiceImpl implements InventoryOrderTraceService {
    private final JdbcTemplate jdbc;

    public InventoryOrderTraceServiceImpl(@Qualifier("invDataSource") DataSource invDataSource) {
        this.jdbc = new JdbcTemplate(invDataSource);
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager", readOnly = true)
    public InventoryOrderTrace trace(String orderNo) {
        if (orderNo == null || orderNo.isBlank() || orderNo.length() > 64) {
            throw new IllegalArgumentException("ORDER_NO_REQUIRED");
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT r.reservation_id, r.owner_id, r.status, r.lifecycle_version, r.outbound_no AS reservation_outbound,
                       r.created_at AS reservation_created,
                       l.line_no, l.fulfillment_ref, l.item_id, l.location_id, l.qty AS reserved_qty,
                       l.issued_qty, l.released_qty, l.outbound_no AS line_outbound, l.issued_at, l.updated_by
                  FROM inv_reservation r LEFT JOIN inv_reservation_line l
                    ON l.reservation_id=r.reservation_id AND l.owner_id=r.owner_id
                 WHERE r.external_ref=? ORDER BY r.owner_id, r.id, l.line_no
                """, orderNo);

        Map<String, ReservationBuilder> reservations = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String reservationId = str(row, "reservation_id");
            String key = str(row, "owner_id") + ":" + reservationId;
            ReservationBuilder reservation = reservations.computeIfAbsent(key, ignored -> new ReservationBuilder(
                    reservationId, str(row, "owner_id"), str(row, "status"), integer(row, "lifecycle_version"),
                    str(row, "reservation_outbound"), time(row, "reservation_created")));
            if (row.get("line_no") == null) continue;
            String itemId = str(row, "item_id");
            String outboundNo = str(row, "line_outbound");
            List<Ledger> ledgers = outboundNo == null ? List.of() : jdbc.query("""
                    SELECT id, doc_no, reason_code, qty_delta, balance_after, operator, created_by, occurred_at
                      FROM inv_ledger WHERE doc_no=? AND item_id=? ORDER BY id
                    """, (rs, n) -> {
                int after = rs.getInt("balance_after");
                int delta = rs.getInt("qty_delta");
                return new Ledger(rs.getLong("id"), rs.getString("doc_no"), rs.getString("reason_code"), delta,
                        after - delta, after, rs.getString("operator"), rs.getString("created_by"),
                        rs.getTimestamp("occurred_at").toLocalDateTime());
            }, outboundNo, itemId);
            List<ReturnReceipt> returns = jdbc.query("""
                    SELECT receipt_no, after_sale_no, qty, quality, location_id, inbound_no, created_by, created_at
                      FROM inv_return_receipt WHERE original_line_id=(
                        SELECT id FROM inv_reservation_line WHERE reservation_id=? AND owner_id=? AND line_no=? LIMIT 1)
                     ORDER BY id
                    """, (rs, n) -> new ReturnReceipt(rs.getString("receipt_no"), rs.getString("after_sale_no"),
                    rs.getInt("qty"), rs.getString("quality"), rs.getString("location_id"), rs.getString("inbound_no"),
                    rs.getString("created_by"), rs.getTimestamp("created_at").toLocalDateTime()),
                    reservationId, str(row, "owner_id"), row.get("line_no"));
            reservation.lines.add(new Line(integer(row, "line_no"), str(row, "fulfillment_ref"), itemId,
                    str(row, "location_id"), integer(row, "reserved_qty"), integer(row, "issued_qty"),
                    integer(row, "released_qty"), outboundNo, time(row, "issued_at"), str(row, "updated_by"),
                    ledgers, returns));
        }
        return new InventoryOrderTrace(orderNo, reservations.values().stream().map(ReservationBuilder::build).toList());
    }

    private static String str(Map<String, Object> row, String name) {
        Object value = row.get(name); return value == null ? null : value.toString();
    }
    private static Integer integer(Map<String, Object> row, String name) {
        Object value = row.get(name); return value instanceof Number n ? n.intValue() : null;
    }
    private static java.time.LocalDateTime time(Map<String, Object> row, String name) {
        Object value = row.get(name);
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toLocalDateTime();
        return value instanceof java.time.LocalDateTime localDateTime ? localDateTime : null;
    }
    private static final class ReservationBuilder {
        private final String id, owner, status, outbound;
        private final Integer version;
        private final java.time.LocalDateTime created;
        private final List<Line> lines = new ArrayList<>();
        private ReservationBuilder(String id, String owner, String status, Integer version, String outbound,
                                   java.time.LocalDateTime created) {
            this.id=id; this.owner=owner; this.status=status; this.version=version; this.outbound=outbound; this.created=created;
        }
        private Reservation build() { return new Reservation(id, owner, status, version, outbound, created, List.copyOf(lines)); }
    }
}
