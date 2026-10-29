package ai.neargo.inventory.domain.mapper;

import ai.neargo.inventory.domain.config.ConditionalOnInventory;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

import javax.sql.DataSource;

/** Immutable production receipts read the same inventory operations and historical ledger. */
@Repository
@ConditionalOnInventory
public class InventoryProductionMapper {
    private final JdbcTemplate db;

    public InventoryProductionMapper(@Qualifier("invDataSource") DataSource source) {
        db = new JdbcTemplate(source);
    }

    public record Operation(
            Long id,
            String requestHash,
            String receiptSnapshot,
            String reverseEventNo,
            String reverseHash,
            String operationRef) {}

    public record Delta(String deltaSnapshot) {}

    public record Ledger(
            BigDecimal qtyDelta,
            Long unitCostMinor,
            String costCurrency,
            String docNo,
            String batchNo) {}

    public List<String> activeOwners(boolean queryOnly, String value0) {
        return db.query(
                "SELECT owner_id FROM inv_owner WHERE owner_id=? AND"
                        + " status='ACTIVE'"
                        + (queryOnly ? "" : " FOR UPDATE"),
                (rs, n) -> rs.getString(1),
                value0);
    }

    public List<Operation> operations(String value0, String value1, String value2) {
        return db.query(
                "SELECT * FROM inv_production_operation WHERE owner_id=? AND store_no=? AND"
                        + " event_no=?",
                (rs, n) ->
                        new Operation(
                                rs.getObject("id", Long.class),
                                rs.getString("request_hash"),
                                rs.getString("receipt_snapshot"),
                                rs.getString("reverse_event_no"),
                                rs.getString("reverse_hash"),
                                rs.getString("operation_ref")),
                value0,
                value1,
                value2);
    }

    public Delta demandDelta(String value0, String value1) {
        return db.query(
                        "SELECT delta_snapshot FROM inv_demand_operation WHERE owner_id=? AND"
                                + " operation_ref=?",
                        (rs, n) -> new Delta(rs.getString("delta_snapshot")),
                        value0,
                        value1)
                .getFirst();
    }

    public Ledger ledger(String value0, Long value1) {
        return db.query(
                        "SELECT qty_delta,unit_cost_minor,cost_currency,doc_no,batch_no FROM"
                                + " inv_ledger WHERE owner_id=? AND id=?",
                        (rs, n) ->
                                new Ledger(
                                        rs.getBigDecimal("qty_delta"),
                                        rs.getObject("unit_cost_minor", Long.class),
                                        rs.getString("cost_currency"),
                                        rs.getString("doc_no"),
                                        rs.getString("batch_no")),
                        value0,
                        value1)
                .getFirst();
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
                    + " inv_production_operation(owner_id,store_no,event_no,request_hash,plan_no,operation_ref,receipt_snapshot,created_by)"
                    + " VALUES(?,?,?,?,?,?,?,'SYSTEM')",
                value0,
                value1,
                value2,
                value3,
                value4,
                value5,
                value6);
    }

    public List<String> lockOwner(String value0) {
        return db.query(
                "SELECT owner_id FROM inv_owner WHERE owner_id=? FOR UPDATE",
                (rs, n) -> rs.getString(1),
                value0);
    }

    public List<Operation> originalOperations(String value0, String value1, String value2) {
        return db.query(
                "SELECT * FROM inv_production_operation WHERE owner_id=? AND store_no=? AND"
                        + " event_no=?",
                (rs, n) ->
                        new Operation(
                                rs.getObject("id", Long.class),
                                rs.getString("request_hash"),
                                rs.getString("receipt_snapshot"),
                                rs.getString("reverse_event_no"),
                                rs.getString("reverse_hash"),
                                rs.getString("operation_ref")),
                value0,
                value1,
                value2);
    }

    public int confirmReverse(String value0, String value1, String value2, Long value3) {
        return db.update(
                "UPDATE inv_production_operation SET"
                        + " reverse_event_no=?,reverse_hash=?,receipt_snapshot=? WHERE id=?",
                value0,
                value1,
                value2,
                value3);
    }
}
