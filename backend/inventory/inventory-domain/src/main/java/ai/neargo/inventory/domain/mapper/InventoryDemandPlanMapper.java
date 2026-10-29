package ai.neargo.inventory.domain.mapper;

import ai.neargo.inventory.domain.config.ConditionalOnInventory;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import javax.sql.DataSource;

/** Typed persistence on the original inventory balances and immutable demand operations. */
@Repository
@ConditionalOnInventory
public class InventoryDemandPlanMapper {
    private final JdbcTemplate db;

    public InventoryDemandPlanMapper(@Qualifier("invDataSource") DataSource source) {
        db = new JdbcTemplate(source);
    }

    public record Head(
            Long id,
            String planNo,
            String planHash,
            int lifecycleVersion,
            String status,
            LocalDateTime expiresAt) {}

    public record Operation(
            String planNo,
            String requestHash,
            int reversed,
            String kind,
            String deltaSnapshot,
            String receiptSnapshot) {}

    public record Leaf(
            Long id,
            String leafRef,
            String componentRef,
            String fulfillmentRef,
            String branchKind,
            String itemId,
            String locationId,
            BigDecimal qty,
            BigDecimal issuedQty,
            BigDecimal releasedQty,
            String unit,
            String definitionHash,
            String documentNo,
            String ledgerRef,
            Long costMinor,
            String currency) {}

    public record Valuation(Long defaultCostMinor, String costCurrency, int trackBatch) {}

    public record Lot(
            BigDecimal qty, Long unitCostMinor, String currency, String batchNo, String lotRef) {}

    public List<Long> legacyReservations(String value0, String value1) {
        return db.query(
                "SELECT id FROM inv_reservation WHERE owner_id=? AND external_ref=?",
                (rs, n) -> rs.getLong(1),
                value0,
                value1);
    }

    public int insertPlan(
            String value0,
            String value1,
            String value2,
            String value3,
            String value4,
            int value5,
            LocalDateTime value6) {
        return db.update(
                "INSERT INTO"
                    + " inv_demand_plan(owner_id,external_ref,plan_no,plan_hash,plan_snapshot,lifecycle_version,status,expires_at,created_by)"
                    + " VALUES(?,?,?,?,?,?, 'HELD',?,'SYSTEM')",
                value0,
                value1,
                value2,
                value3,
                value4,
                value5,
                value6);
    }

    public int reserveBalance(
            BigDecimal value0, String value1, String value2, String value3, BigDecimal value4) {
        return db.update(
                "UPDATE inv_stock_balance SET reserved=reserved+?,version=version+1"
                        + " WHERE owner_id=? AND item_id=? AND location_id=? AND"
                        + " on_hand-reserved-unavailable_quantity>=?",
                value0,
                value1,
                value2,
                value3,
                value4);
    }

    public int insertLeaf(
            String value0,
            String value1,
            String value2,
            String value3,
            String value4,
            String value5,
            String value6,
            String value7,
            BigDecimal value8,
            String value9,
            String value10) {
        return db.update(
                "INSERT INTO"
                    + " inv_demand_leaf(owner_id,plan_no,leaf_ref,component_ref,fulfillment_ref,branch_kind,item_id,location_id,qty,unit,definition_hash,created_by)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?,?,'SYSTEM')",
                value0,
                value1,
                value2,
                value3,
                value4,
                value5,
                value6,
                value7,
                value8,
                value9,
                value10);
    }

    public int acceptUnpaid(String value0, String value1) {
        return db.update(
                "UPDATE inv_demand_plan SET status='ACCEPTED_UNPAID' WHERE owner_id=? AND"
                        + " plan_no=?",
                value0,
                value1);
    }

    public int releaseCompletedHold(String owner, String plan) {
        return db.update(
                "UPDATE inv_demand_plan SET status='RELEASED' WHERE owner_id=? AND plan_no=? AND"
                    + " status='HELD' AND NOT EXISTS (SELECT 1 FROM inv_demand_leaf l WHERE"
                    + " l.owner_id=? AND l.plan_no=? AND (l.issued_qty<>0 OR"
                    + " l.released_qty<>l.qty))",
                owner,
                plan,
                owner,
                plan);
    }

    public int allocatePaid(String value0, String value1) {
        return db.update(
                "UPDATE inv_demand_plan SET status='ALLOCATED' WHERE owner_id=? AND plan_no=?",
                value0,
                value1);
    }

    public int unreserveBalance(
            BigDecimal value0, String value1, String value2, String value3, BigDecimal value4) {
        return db.update(
                "UPDATE inv_stock_balance SET reserved=reserved-?,version=version+1"
                        + " WHERE owner_id=? AND item_id=? AND location_id=? AND"
                        + " reserved>=?",
                value0,
                value1,
                value2,
                value3,
                value4);
    }

    public Valuation valuation(String value0, String value1) {
        return db.query(
                        "SELECT default_cost_minor,cost_currency,track_batch FROM inv_item"
                                + " WHERE owner_id=? AND item_id=?",
                        (rs, n) ->
                                new Valuation(
                                        rs.getObject("default_cost_minor", Long.class),
                                        rs.getString("cost_currency"),
                                        rs.getInt("track_batch")),
                        value0,
                        value1)
                .getFirst();
    }

    public List<Lot> availableLots(String value0, String value1, String value2) {
        return db.query(
                "SELECT * FROM inv_stock_lot WHERE owner_id=? AND item_id=? AND"
                        + " location_id=? AND qty>0 AND (expire_at IS NULL OR"
                        + " expire_at>=CURRENT_DATE) ORDER BY expire_at IS"
                        + " NULL,expire_at,id FOR UPDATE",
                (rs, n) ->
                        new Lot(
                                rs.getBigDecimal("qty"),
                                rs.getObject("unit_cost_minor", Long.class),
                                rs.getString("currency"),
                                rs.getString("batch_no"),
                                rs.getString("lot_ref")),
                value0,
                value1,
                value2);
    }

    public int insertOutboundLine(
            String value0,
            String value1,
            int value2,
            String value3,
            BigDecimal value4,
            String value5,
            Long value6,
            String value7) {
        return db.update(
                "INSERT INTO"
                    + " inv_outbound_line(owner_id,outbound_no,line_no,item_id,qty,uom,unit_cost_minor,batch_no,created_by)"
                    + " VALUES(?,?,?,?,?,?,?,?,'SYSTEM')",
                value0,
                value1,
                value2,
                value3,
                value4,
                value5,
                value6,
                value7);
    }

    public int updateProgress(
            boolean release,
            BigDecimal value0,
            String value1,
            String value2,
            Long value3,
            String value4,
            String value5,
            String value6,
            String value7) {
        return db.update(
                "UPDATE inv_demand_leaf SET "
                        + (release ? "released_qty" : "issued_qty")
                        + "=?,document_no=COALESCE(?,document_no),ledger_ref=COALESCE(?,ledger_ref),cost_minor=?,currency=COALESCE(?,currency)"
                        + " WHERE owner_id=? AND plan_no=? AND leaf_ref=?",
                value0,
                value1,
                value2,
                value3,
                value4,
                value5,
                value6,
                value7);
    }

    public int insertOutbound(
            String value0,
            String value1,
            String value2,
            String value3,
            String value4,
            BigDecimal value5) {
        return db.update(
                "INSERT INTO"
                    + " inv_outbound_order(outbound_no,owner_id,location_id,purpose,source_ref,status,total_qty,total_cost_minor,occurred_at,posted_at,operator,created_by)"
                    + " VALUES(?,?,?, ?,?,'POSTED',?,NULL,NOW(),NOW(),'SYSTEM','SYSTEM')",
                value0,
                value1,
                value2,
                value3,
                value4,
                value5);
    }

    public int insertOperation(
            String value0,
            String value1,
            String value2,
            String value3,
            String value4,
            String value5,
            String value6) {
        return db.update(
                "INSERT INTO"
                    + " inv_demand_operation(owner_id,plan_no,operation_ref,request_hash,kind,receipt_snapshot,delta_snapshot,created_by)"
                    + " VALUES(?,?,?,?,?,?,?,'SYSTEM')",
                value0,
                value1,
                value2,
                value3,
                value4,
                value5,
                value6);
    }

    public int reserveSupplement(
            BigDecimal value0, String value1, String value2, String value3, BigDecimal value4) {
        return db.update(
                "UPDATE inv_stock_balance SET reserved=reserved+?,version=version+1"
                        + " WHERE owner_id=? AND item_id=? AND location_id=? AND"
                        + " on_hand-reserved-unavailable_quantity>=?",
                value0,
                value1,
                value2,
                value3,
                value4);
    }

    public int supplementLeaf(BigDecimal value0, Long value1) {
        return db.update("UPDATE inv_demand_leaf SET qty=qty+? WHERE id=?", value0, value1);
    }

    public int insertSupplement(
            String value0,
            String value1,
            String value2,
            String value3,
            String value4,
            String value5) {
        return db.update(
                "INSERT INTO"
                    + " inv_demand_operation(owner_id,plan_no,operation_ref,request_hash,kind,receipt_snapshot,delta_snapshot,created_by)"
                    + " VALUES(?,?,?,?,'SUPPLEMENT',?,?,'SYSTEM')",
                value0,
                value1,
                value2,
                value3,
                value4,
                value5);
    }

    public int insertInboundLine(
            String value0,
            String value1,
            int value2,
            String value3,
            BigDecimal value4,
            String value5,
            Long value6,
            String value7) {
        return db.update(
                "INSERT INTO"
                    + " inv_inbound_line(owner_id,inbound_no,line_no,item_id,qty,uom,unit_cost_minor,batch_no,created_by)"
                    + " VALUES(?,?,?,?,?,?,?,?,'SYSTEM')",
                value0,
                value1,
                value2,
                value3,
                value4,
                value5,
                value6,
                value7);
    }

    public int markLeafDocument(String value0, String value1, Long value2) {
        return db.update(
                "UPDATE inv_demand_leaf SET document_no=?,ledger_ref=? WHERE id=?",
                value0,
                value1,
                value2);
    }

    public int restoreReservation(BigDecimal value0, String value1, String value2, String value3) {
        return db.update(
                "UPDATE inv_stock_balance SET reserved=reserved+?,version=version+1 WHERE"
                        + " owner_id=? AND item_id=? AND location_id=?",
                value0,
                value1,
                value2,
                value3);
    }

    public int reverseLeaf(
            BigDecimal value0, String value1, String value2, String value3, BigDecimal value4) {
        return db.update(
                "UPDATE inv_demand_leaf SET issued_qty=issued_qty-?,cost_minor=NULL"
                        + " WHERE owner_id=? AND plan_no=? AND leaf_ref=? AND"
                        + " issued_qty>=?",
                value0,
                value1,
                value2,
                value3,
                value4);
    }

    public int insertInbound(
            String value0, String value1, String value2, String value3, BigDecimal value4) {
        return db.update(
                "INSERT INTO"
                    + " inv_inbound_order(inbound_no,owner_id,location_id,source_type,source_ref,status,total_qty,total_cost_minor,occurred_at,posted_at,operator,created_by)"
                    + " VALUES(?,?,?,'PRODUCTION_REV',?,'POSTED',?,NULL,NOW(),NOW(),'SYSTEM','SYSTEM')",
                value0,
                value1,
                value2,
                value3,
                value4);
    }

    public int markReversed(String value0, String value1) {
        return db.update(
                "UPDATE inv_demand_operation SET reversed=1 WHERE owner_id=? AND operation_ref=?",
                value0,
                value1);
    }

    public int insertReversal(
            String value0,
            String value1,
            String value2,
            String value3,
            String value4,
            String value5,
            String value6) {
        return db.update(
                "INSERT INTO"
                    + " inv_demand_operation(owner_id,plan_no,operation_ref,request_hash,kind,original_operation_ref,receipt_snapshot,delta_snapshot,created_by)"
                    + " VALUES(?,?,?,?,'REVERSE',?,?,?,'SYSTEM')",
                value0,
                value1,
                value2,
                value3,
                value4,
                value5,
                value6);
    }

    public List<Leaf> leaves(String value0, String value1) {
        return db.query(
                "SELECT * FROM inv_demand_leaf WHERE owner_id=? AND plan_no=?",
                (rs, n) ->
                        new Leaf(
                                rs.getObject("id", Long.class),
                                rs.getString("leaf_ref"),
                                rs.getString("component_ref"),
                                rs.getString("fulfillment_ref"),
                                rs.getString("branch_kind"),
                                rs.getString("item_id"),
                                rs.getString("location_id"),
                                rs.getBigDecimal("qty"),
                                rs.getBigDecimal("issued_qty"),
                                rs.getBigDecimal("released_qty"),
                                rs.getString("unit"),
                                rs.getString("definition_hash"),
                                rs.getString("document_no"),
                                rs.getString("ledger_ref"),
                                rs.getObject("cost_minor", Long.class),
                                rs.getString("currency")),
                value0,
                value1);
    }

    public int expireUnreserve(
            BigDecimal value0, String value1, String value2, String value3, BigDecimal value4) {
        return db.update(
                "UPDATE inv_stock_balance SET reserved=reserved-?,version=version+1 WHERE"
                        + " owner_id=? AND item_id=? AND location_id=? AND reserved>=?",
                value0,
                value1,
                value2,
                value3,
                value4);
    }

    public int expireLeaf(Long value0) {
        return db.update(
                "UPDATE inv_demand_leaf SET released_qty=qty-issued_qty WHERE id=?", value0);
    }

    public int expirePlan(Long value0) {
        return db.update("UPDATE inv_demand_plan SET status='EXPIRED' WHERE id=?", value0);
    }

    public List<String> lockOwner(String value0) {
        return db.query(
                "SELECT owner_id FROM inv_owner WHERE owner_id=? AND status='ACTIVE' FOR"
                        + " UPDATE",
                (rs, n) -> rs.getString(1),
                value0);
    }

    public List<Head> heads(String value0, String value1) {
        return db.query(
                "SELECT * FROM inv_demand_plan WHERE owner_id=? AND external_ref=?",
                (rs, n) ->
                        new Head(
                                rs.getObject("id", Long.class),
                                rs.getString("plan_no"),
                                rs.getString("plan_hash"),
                                rs.getInt("lifecycle_version"),
                                rs.getString("status"),
                                rs.getTimestamp("expires_at").toLocalDateTime()),
                value0,
                value1);
    }

    public List<Operation> operations(String value0, String value1) {
        return db.query(
                "SELECT * FROM inv_demand_operation WHERE owner_id=? AND operation_ref=?",
                (rs, n) ->
                        new Operation(
                                rs.getString("plan_no"),
                                rs.getString("request_hash"),
                                rs.getInt("reversed"),
                                rs.getString("kind"),
                                rs.getString("delta_snapshot"),
                                rs.getString("receipt_snapshot")),
                value0,
                value1);
    }

    public List<Leaf> findLeaves(String value0, String value1, String value2) {
        return db.query(
                "SELECT * FROM inv_demand_leaf WHERE owner_id=? AND plan_no=? AND" + " leaf_ref=?",
                (rs, n) ->
                        new Leaf(
                                rs.getObject("id", Long.class),
                                rs.getString("leaf_ref"),
                                rs.getString("component_ref"),
                                rs.getString("fulfillment_ref"),
                                rs.getString("branch_kind"),
                                rs.getString("item_id"),
                                rs.getString("location_id"),
                                rs.getBigDecimal("qty"),
                                rs.getBigDecimal("issued_qty"),
                                rs.getBigDecimal("released_qty"),
                                rs.getString("unit"),
                                rs.getString("definition_hash"),
                                rs.getString("document_no"),
                                rs.getString("ledger_ref"),
                                rs.getObject("cost_minor", Long.class),
                                rs.getString("currency")),
                value0,
                value1,
                value2);
    }

    public List<Leaf> orderedLeaves(String value0, String value1) {
        return db.query(
                "SELECT * FROM inv_demand_leaf WHERE owner_id=? AND plan_no=? ORDER BY"
                        + " leaf_ref",
                (rs, n) ->
                        new Leaf(
                                rs.getObject("id", Long.class),
                                rs.getString("leaf_ref"),
                                rs.getString("component_ref"),
                                rs.getString("fulfillment_ref"),
                                rs.getString("branch_kind"),
                                rs.getString("item_id"),
                                rs.getString("location_id"),
                                rs.getBigDecimal("qty"),
                                rs.getBigDecimal("issued_qty"),
                                rs.getBigDecimal("released_qty"),
                                rs.getString("unit"),
                                rs.getString("definition_hash"),
                                rs.getString("document_no"),
                                rs.getString("ledger_ref"),
                                rs.getObject("cost_minor", Long.class),
                                rs.getString("currency")),
                value0,
                value1);
    }
}
