package ai.neargo.shop.food.mapper;

import ai.neargo.shop.food.entity.FoodBusinessOperationRow;

import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

/** Primary-source business mapper. All SQL and source-table selection stay inside persistence. */
public class FoodBusinessOperationsMapper {
    private final JdbcTemplate db;

    public FoodBusinessOperationsMapper(DataSource source) {
        db = new JdbcTemplate(source);
    }

    private static List<FoodBusinessOperationRow> rows(List<Map<String, Object>> rows) {
        return rows.stream().map(FoodBusinessOperationRow::new).toList();
    }

    public List<FoodBusinessOperationRow> sourceEvidence(
            String kind, String entity, String store, String sourceNo, boolean lock) {
        String table, column;
        switch (kind) {
            case "CHECK" -> {
                table = "fnb_check";
                column = "check_no";
            }
            case "BATCH" -> {
                table = "fnb_order_batch";
                column = "batch_no";
            }
            case "RESERVATION" -> {
                table = "fnb_reservation";
                column = "reservation_no";
            }
            case "QUEUE" -> {
                table = "fnb_queue_ticket";
                column = "ticket_no";
            }
            case "PREORDER" -> {
                table = "fnb_reservation_preorder";
                column = "preorder_no";
            }
            default -> throw new IllegalArgumentException("Unsupported operational source");
        }
        return rows(
                db.queryForList(
                        "SELECT * FROM "
                                + table
                                + " WHERE tenant_no='MAIN' AND deleted=0 AND entity_no=? AND"
                                + " store_no=? AND "
                                + column
                                + "=?"
                                + (lock ? " FOR UPDATE" : ""),
                        entity,
                        store,
                        sourceNo));
    }

    public List<FoodBusinessOperationRow> operationCases(
            String entity, String store, String status) {
        if (status == null)
            return rows(
                    db.queryForList(
                            "SELECT * FROM fnb_operation_case WHERE entity_no=? AND store_no=?"
                                    + " ORDER BY created_at,case_no LIMIT 200",
                            entity,
                            store));
        return rows(
                db.queryForList(
                        "SELECT * FROM fnb_operation_case WHERE entity_no=? AND store_no=? AND"
                                + " status=? ORDER BY created_at,case_no LIMIT 200",
                        entity,
                        store,
                        status));
    }

    public List<FoodBusinessOperationRow> checkBusinessGuardSelectOrderBatch(
            String e, String store, String batch, String e2, String store2, String batch2) {
        return rows(
                db.queryForList(
                        "SELECT b.check_no FROM fnb_order_batch b WHERE b.entity_no=? AND"
                            + " b.store_no=? AND b.batch_no=? AND b.tenant_no='MAIN' AND b.deleted=0"
                            + " AND NOT EXISTS(SELECT 1 FROM fnb_order_line ol WHERE ol.tenant_no=b.tenant_no"
                            + " AND ol.entity_no=b.entity_no AND ol.batch_no=b.batch_no AND ol.deleted=0) UNION SELECT"
                            + " COALESCE(lm.check_no,bm.check_no,b.check_no) AS check_no FROM"
                            + " fnb_order_batch b JOIN fnb_order_line l ON l.entity_no=b.entity_no"
                            + " AND l.tenant_no=b.tenant_no AND l.deleted=0 AND l.batch_no=b.batch_no LEFT JOIN fnb_check_line_membership lm ON"
                            + " lm.tenant_no=l.tenant_no AND lm.entity_no=l.entity_no AND lm.deleted=0 AND lm.active_line_no=l.food_line_no LEFT"
                            + " JOIN fnb_check_batch_membership bm ON bm.tenant_no=b.tenant_no AND bm.entity_no=b.entity_no AND"
                            + " bm.batch_no=b.batch_no AND bm.detached_at IS NULL AND bm.deleted=0"
                            + " WHERE b.tenant_no='MAIN' AND b.deleted=0 AND b.entity_no=? AND b.store_no=? AND b.batch_no=? ORDER BY"
                            + " check_no",
                        e,
                        store,
                        batch,
                        e2,
                        store2,
                        batch2));
    }

    public long checkBusinessGuardPendingReturns(String entity, String store, String check) {
        return db.queryForObject("SELECT COUNT(*) FROM fnb_return r JOIN fnb_order_batch b ON "
                + "b.tenant_no=r.tenant_no AND b.entity_no=r.entity_no AND b.batch_no=r.batch_no "
                + "WHERE r.tenant_no='MAIN' AND r.entity_no=? AND r.store_no=? AND r.deleted=0 "
                + "AND b.deleted=0 AND r.status IN ('BUSINESS_REQUESTED','APPROVAL_PENDING','APPROVAL_UNKNOWN') "
                + "AND EXISTS(SELECT 1 FROM fnb_order_line l LEFT JOIN fnb_check_line_membership lm ON "
                + "lm.tenant_no=l.tenant_no AND lm.entity_no=l.entity_no AND lm.active_line_no=l.food_line_no AND lm.deleted=0 "
                + "LEFT JOIN fnb_check_batch_membership bm ON bm.tenant_no=l.tenant_no AND bm.entity_no=l.entity_no "
                + "AND bm.batch_no=l.batch_no AND bm.deleted=0 AND bm.detached_at IS NULL "
                + "WHERE l.tenant_no=b.tenant_no AND l.entity_no=b.entity_no AND l.batch_no=b.batch_no AND l.deleted=0 "
                + "AND COALESCE(lm.check_no,bm.check_no,b.check_no)=?)", Long.class, entity, store, check);
    }

    public List<FoodBusinessOperationRow> checkBusinessGuardSelectCheck(
            String entity, String store, String check) {
        return rows(
                db.queryForList(
                        "SELECT status,service_state FROM fnb_check WHERE tenant_no='MAIN' AND"
                            + " deleted=0 AND entity_no=? AND store_no=? AND check_no=? FOR UPDATE",
                        entity,
                        store,
                        check));
    }

    public Long checkBusinessGuardCountCheckFreeze(String entity, String store, String check) {
        return db.queryForObject(
                "SELECT COUNT(*) FROM fnb_check_freeze WHERE entity_no=? AND store_no=? AND"
                        + " active_check_no=?",
                Long.class,
                entity,
                store,
                check);
    }

    public Long checkBusinessGuardCountSettlementItem(
            String entity, String store, String check, String check2) {
        return db.queryForObject(
                "SELECT COUNT(*) FROM fnb_settlement_item i JOIN fnb_settlement s ON"
                    + " s.entity_no=i.entity_no AND s.settlement_no=i.settlement_no JOIN"
                    + " fnb_core_order_binding k ON k.entity_no=i.entity_no AND"
                    + " k.payment_root_no=i.payment_root_no JOIN fnb_order_batch b ON"
                    + " b.entity_no=k.entity_no AND b.batch_no=k.batch_no LEFT JOIN fnb_order_line"
                    + " l ON l.entity_no=b.entity_no AND l.batch_no=b.batch_no LEFT JOIN"
                    + " fnb_check_line_membership lm ON lm.entity_no=l.entity_no AND"
                    + " lm.active_line_no=l.food_line_no LEFT JOIN fnb_check_batch_membership bm ON"
                    + " bm.entity_no=b.entity_no AND bm.batch_no=b.batch_no AND bm.detached_at IS"
                    + " NULL AND bm.deleted=0 WHERE i.entity_no=? AND s.store_no=? AND"
                    + " (i.check_no=? OR COALESCE(lm.check_no,bm.check_no,b.check_no)=?) AND"
                    + " i.deleted=0 AND s.deleted=0 AND s.status IN ('FROZEN','COLLECTING')",
                Long.class,
                entity,
                store,
                check,
                check2);
    }

    public int checkBusinessGuardUpdateBusinessSharePlan(
            String value1, String actor, String entity, String store, String check) {
        return db.update(
                "UPDATE fnb_business_share_plan SET"
                    + " status='CANCELLED_BUSINESS_ONLY',active_check_no=NULL,cancellation_reason=?,updated_by=?,updated_at=UTC_TIMESTAMP(3),version=version+1"
                    + " WHERE entity_no=? AND store_no=? AND active_check_no=? AND status IN"
                    + " ('DRAFT_BUSINESS_ONLY','CONFIRMED_BUSINESS_ONLY')",
                value1,
                actor,
                entity,
                store,
                check);
    }

    public List<FoodBusinessOperationRow> checkCollaborationSelectOrderLine(
            String e, String store, String check) {
        return rows(
                db.queryForList(
                        "SELECT l.food_line_no,l.source_line_ref,l.batch_no,l.qty,l.unit,l.version"
                            + " AS line_version,l.seat_no,l.selection_version,b.status,b.version AS"
                            + " batch_version,b.quote_ref,b.quote_hash,bm.membership_no,bm.version"
                            + " AS membership_version,lm.membership_no AS"
                            + " line_membership_no,lm.version AS line_membership_version FROM"
                            + " fnb_order_line l JOIN fnb_order_batch b ON b.entity_no=l.entity_no"
                            + " AND b.batch_no=l.batch_no LEFT JOIN fnb_check_line_membership lm ON"
                            + " lm.tenant_no=l.tenant_no AND lm.entity_no=l.entity_no AND lm.deleted=0 AND lm.active_line_no=l.food_line_no LEFT"
                            + " JOIN fnb_check_batch_membership bm ON bm.tenant_no=b.tenant_no AND bm.entity_no=b.entity_no AND"
                            + " bm.batch_no=b.batch_no AND bm.detached_at IS NULL AND bm.deleted=0"
                            + " WHERE b.entity_no=? AND b.store_no=? AND l.deleted=0 AND"
                            + " b.deleted=0 AND COALESCE(lm.check_no,bm.check_no,b.check_no)=?"
                            + " ORDER BY l.batch_no,l.food_line_no FOR UPDATE",
                        e,
                        store,
                        check));
    }

    public List<FoodBusinessOperationRow> checkCollaborationSelectOrderLine2(
            String e, String store, String line) {
        return rows(
                db.queryForList(
                        "SELECT COALESCE(lm.check_no,bm.check_no,b.check_no) AS current_check_no"
                            + " FROM fnb_order_line l JOIN fnb_order_batch b ON"
                            + " b.entity_no=l.entity_no AND b.batch_no=l.batch_no LEFT JOIN"
                            + " fnb_check_line_membership lm ON lm.entity_no=l.entity_no AND"
                            + " lm.active_line_no=l.food_line_no LEFT JOIN"
                            + " fnb_check_batch_membership bm ON bm.entity_no=b.entity_no AND"
                            + " bm.batch_no=b.batch_no AND bm.detached_at IS NULL AND bm.deleted=0"
                            + " WHERE b.entity_no=? AND b.store_no=? AND l.food_line_no=? AND"
                            + " l.deleted=0 AND b.deleted=0",
                        e,
                        store,
                        line));
    }

    public List<FoodBusinessOperationRow> checkCollaborationSelectOrderLine3(
            String e, String store, String check) {
        return rows(
                db.queryForList(
                        "SELECT l.food_line_no FROM fnb_order_line l JOIN fnb_order_batch b ON"
                            + " b.entity_no=l.entity_no AND b.batch_no=l.batch_no LEFT JOIN"
                            + " fnb_check_line_membership lm ON lm.entity_no=l.entity_no AND"
                            + " lm.active_line_no=l.food_line_no LEFT JOIN"
                            + " fnb_check_batch_membership bm ON bm.entity_no=b.entity_no AND"
                            + " bm.batch_no=b.batch_no AND bm.detached_at IS NULL AND bm.deleted=0"
                            + " WHERE b.entity_no=? AND b.store_no=? AND l.deleted=0 AND"
                            + " b.deleted=0 AND COALESCE(lm.check_no,bm.check_no,b.check_no)=?"
                            + " ORDER BY l.food_line_no FOR UPDATE",
                        e,
                        store,
                        check));
    }

    public Long checkCollaborationCountOrderBatch(String e, String store, String check) {
        return db.queryForObject(
                "SELECT COUNT(*) FROM fnb_order_batch WHERE entity_no=? AND store_no=? AND"
                        + " check_no=? AND deleted=0 AND status NOT IN ('ACCEPTED','CANCELLED')",
                Long.class,
                e,
                store,
                check);
    }

    public Long checkCollaborationCountKitchenTask(String e, String line) {
        return db.queryForObject(
                "SELECT COUNT(*) FROM fnb_kitchen_task WHERE entity_no=? AND food_line_no=? AND"
                        + " deleted=0 AND status NOT IN ('SERVED','VOIDED')",
                Long.class,
                e,
                line);
    }

    public Long checkCollaborationCountReturnItem(String e, String line) {
        return db.queryForObject(
                "SELECT COUNT(*) FROM fnb_return_item i JOIN fnb_return r ON"
                    + " r.entity_no=i.entity_no AND r.return_no=i.return_no WHERE i.entity_no=? AND"
                    + " i.food_line_no=? AND i.deleted=0 AND r.deleted=0 AND r.status NOT IN"
                    + " ('REJECTED','CLOSED')",
                Long.class,
                e,
                line);
    }

    public Long checkCollaborationCountDiningEntitlement(String e, String check) {
        return db.queryForObject(
                "SELECT COUNT(*) FROM fnb_dining_entitlement WHERE entity_no=? AND check_no=? AND"
                        + " status='ACTIVE' AND deleted=0",
                Long.class,
                e,
                check);
    }

    public List<FoodBusinessOperationRow> checkCollaborationSelectTable(
            String e, String store, String check) {
        return rows(
                db.queryForList(
                        "SELECT t.table_no FROM fnb_table t JOIN fnb_table_occupancy o ON"
                            + " o.entity_no=t.entity_no AND o.table_no=t.table_no WHERE"
                            + " o.entity_no=? AND t.store_no=? AND o.check_no=? AND o.ended_at IS"
                            + " NULL AND o.deleted=0 AND t.deleted=0 ORDER BY t.table_no FOR"
                            + " UPDATE",
                        e,
                        store,
                        check));
    }

    public Long checkCollaborationCountTableOccupancy(String e, String check) {
        return db.queryForObject(
                "SELECT COUNT(*) FROM fnb_table_occupancy WHERE entity_no=? AND check_no=? AND"
                        + " ended_at IS NULL AND resource_claim_no IS NOT NULL",
                Long.class,
                e,
                check);
    }

    public int checkCollaborationUpdateTableOccupancy(String actor, String e, String check) {
        return db.update(
                "UPDATE fnb_table_occupancy SET"
                    + " ended_at=UTC_TIMESTAMP(3),version=version+1,updated_by=?,updated_at=UTC_TIMESTAMP(3)"
                    + " WHERE entity_no=? AND check_no=? AND ended_at IS NULL AND deleted=0",
                actor,
                e,
                check);
    }

    public int checkCollaborationUpdateTable(String actor, String e, String store, String tableNo) {
        return db.update(
                "UPDATE fnb_table SET version=version+1,updated_by=?,updated_at=UTC_TIMESTAMP(3)"
                        + " WHERE entity_no=? AND store_no=? AND table_no=?",
                actor,
                e,
                store,
                tableNo);
    }

    public int checkCollaborationUpdateGuestSession(
            String actor, String e, String store, String check) {
        return db.update(
                "UPDATE fnb_guest_session SET"
                    + " revoked_at=UTC_TIMESTAMP(3),version=version+1,updated_by=?,updated_at=UTC_TIMESTAMP(3)"
                    + " WHERE entity_no=? AND store_no=? AND check_no=? AND revoked_at IS NULL",
                actor,
                e,
                store,
                check);
    }

    public int checkCollaborationUpdateCheckCart(
            String actor, String e, String store, String check) {
        return db.update(
                "UPDATE fnb_check_cart SET"
                    + " status='CLOSED',version=version+1,updated_by=?,updated_at=UTC_TIMESTAMP(3)"
                    + " WHERE entity_no=? AND store_no=? AND check_no=? AND status='OPEN'",
                actor,
                e,
                store,
                check);
    }

    public int checkCollaborationUpdateCheck(String actor, String e, String store, String check) {
        return db.update(
                "UPDATE fnb_check SET"
                    + " service_state='RELEASED',version=version+1,updated_by=?,updated_at=UTC_TIMESTAMP(3)"
                    + " WHERE entity_no=? AND store_no=? AND check_no=?",
                actor,
                e,
                store,
                check);
    }

    public List<FoodBusinessOperationRow> checkCollaborationSelectOrderBatch(
            String entity, String store, String batch) {
        return rows(
                db.queryForList(
                        "SELECT check_no,status FROM fnb_order_batch WHERE tenant_no='MAIN' AND"
                            + " deleted=0 AND entity_no=? AND store_no=? AND batch_no=? FOR UPDATE",
                        entity,
                        store,
                        batch));
    }

    public Long checkCollaborationCountCheckLineMembership(String entity, String batch) {
        return db.queryForObject(
                "SELECT COUNT(*) FROM fnb_check_line_membership WHERE entity_no=? AND batch_no=?"
                        + " AND active_line_no IS NOT NULL",
                Long.class,
                entity,
                batch);
    }

    public List<FoodBusinessOperationRow> checkCollaborationSelectCheckBatchMembership(
            String entity, String batch) {
        return rows(
                db.queryForList(
                        "SELECT membership_no,check_no FROM fnb_check_batch_membership WHERE"
                            + " entity_no=? AND batch_no=? AND deleted=0 AND detached_at IS NULL"
                            + " FOR UPDATE",
                        entity,
                        batch));
    }

    public Long checkCollaborationCountReturn(String entity, String store, String batch) {
        return db.queryForObject(
                "SELECT COUNT(*) FROM fnb_return r JOIN fnb_return_item i ON"
                    + " i.entity_no=r.entity_no AND i.return_no=r.return_no JOIN fnb_order_line l"
                    + " ON l.entity_no=i.entity_no AND l.food_line_no=i.food_line_no WHERE"
                    + " r.entity_no=? AND r.store_no=? AND l.batch_no=? AND r.deleted=0 AND"
                    + " i.deleted=0 AND r.status<>'REJECTED'",
                Long.class,
                entity,
                store,
                batch);
    }

    public int checkCollaborationUpdateCheckBatchMembership(
            String actor, String entity, String batch) {
        return db.update(
                "UPDATE fnb_check_batch_membership SET"
                    + " detached_at=UTC_TIMESTAMP(3),updated_at=UTC_TIMESTAMP(3),updated_by=?,version=version+1"
                    + " WHERE entity_no=? AND batch_no=? AND detached_at IS NULL AND deleted=0",
                actor,
                entity,
                batch);
    }

    public int checkCollaborationInsertCheckBatchMembership(
            String entity,
            String value2,
            String checkNo,
            String batch,
            String op,
            String actor,
            String actor2) {
        return db.update(
                "INSERT INTO fnb_check_batch_membership"
                    + " (entity_no,membership_no,check_no,batch_no,attached_at,operation_no,created_at,updated_at,created_by,updated_by)"
                    + " VALUES (?,?,?,?,UTC_TIMESTAMP(3),?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),?,?)",
                entity,
                value2,
                checkNo,
                batch,
                op,
                actor,
                actor2);
    }

    public int checkCollaborationUpdateCheck2(
            String actor, String entity, String store, String checkNo, long version) {
        return db.update(
                "UPDATE fnb_check SET version=version+1,updated_by=?,updated_at=UTC_TIMESTAMP(3)"
                        + " WHERE entity_no=? AND store_no=? AND check_no=? AND version=?",
                actor,
                entity,
                store,
                checkNo,
                version);
    }

    public int checkCollaborationUpdateCheckCart2(
            String actor, String entity, String store, String checkNo) {
        return db.update(
                "UPDATE fnb_check_cart SET"
                    + " status='ARCHIVED',version=version+1,updated_by=?,updated_at=UTC_TIMESTAMP(3)"
                    + " WHERE entity_no=? AND store_no=? AND check_no=? AND status='OPEN'",
                actor,
                entity,
                store,
                checkNo);
    }

    public List<FoodBusinessOperationRow> checkCollaborationSelectOrderLine4(
            String e, String store, String line) {
        return rows(
                db.queryForList(
                        "SELECT l.food_line_no,l.batch_no,l.seat_no,l.qty,l.unit,b.check_no FROM"
                            + " fnb_order_line l JOIN fnb_order_batch b ON b.entity_no=l.entity_no"
                            + " AND b.batch_no=l.batch_no WHERE l.entity_no=? AND b.store_no=? AND"
                            + " l.food_line_no=? AND l.deleted=0 AND b.deleted=0 AND"
                            + " b.status='ACCEPTED' FOR UPDATE",
                        e,
                        store,
                        line));
    }

    public List<FoodBusinessOperationRow> checkCollaborationSelectCheckLineMembership(
            String e, String line) {
        return rows(
                db.queryForList(
                        "SELECT check_no FROM fnb_check_line_membership WHERE entity_no=? AND"
                                + " active_line_no=? FOR UPDATE",
                        e,
                        line));
    }

    public List<FoodBusinessOperationRow> checkCollaborationSelectCheckBatchMembership2(
            String e, String batchNo) {
        return rows(
                db.queryForList(
                        "SELECT check_no FROM fnb_check_batch_membership WHERE entity_no=? AND"
                                + " batch_no=? AND detached_at IS NULL AND deleted=0 FOR UPDATE",
                        e,
                        batchNo));
    }

    public Long checkCollaborationCountReturnItem2(String e, String line) {
        return db.queryForObject(
                "SELECT COUNT(*) FROM fnb_return_item i JOIN fnb_return r ON"
                    + " r.entity_no=i.entity_no AND r.return_no=i.return_no WHERE i.entity_no=? AND"
                    + " i.food_line_no=? AND i.deleted=0 AND r.deleted=0 AND r.status<>'REJECTED'",
                Long.class,
                e,
                line);
    }

    public Long checkCollaborationCountSettlementItem(String e, String batchNo) {
        return db.queryForObject(
                "SELECT COUNT(*) FROM fnb_settlement_item i JOIN fnb_settlement s ON"
                    + " s.entity_no=i.entity_no AND s.settlement_no=i.settlement_no JOIN"
                    + " fnb_core_order_binding b ON b.entity_no=i.entity_no AND"
                    + " b.payment_root_no=i.payment_root_no WHERE b.entity_no=? AND b.batch_no=?"
                    + " AND s.status IN ('FROZEN','COLLECTING') AND s.deleted=0",
                Long.class,
                e,
                batchNo);
    }

    public int checkCollaborationUpdateCheckLineMembership(String actor, String e, String line) {
        return db.update(
                "UPDATE fnb_check_line_membership SET"
                    + " active_line_no=NULL,detached_at=UTC_TIMESTAMP(3),version=version+1,updated_by=?,updated_at=UTC_TIMESTAMP(3)"
                    + " WHERE entity_no=? AND active_line_no=?",
                actor,
                e,
                line);
    }

    public int checkCollaborationInsertCheckLineMembership(
            String e,
            String store,
            String value3,
            String checkNo,
            String batchNo,
            String line,
            String line2,
            String seatNo,
            BigDecimal qty,
            String unit,
            String op,
            String actor,
            String actor2) {
        return db.update(
                "INSERT INTO fnb_check_line_membership"
                    + " (entity_no,store_no,membership_no,check_no,batch_no,food_line_no,active_line_no,source_seat_no,qty,unit,operation_no,attached_at,created_at,updated_at,created_by,updated_by)"
                    + " VALUES"
                    + " (?,?,?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),?,?)",
                e,
                store,
                value3,
                checkNo,
                batchNo,
                line,
                line2,
                seatNo,
                qty,
                unit,
                op,
                actor,
                actor2);
    }

    public int checkCollaborationUpdateCheck3(
            String actor, String e, String store, String checkNo, long version) {
        return db.update(
                "UPDATE fnb_check SET version=version+1,updated_by=?,updated_at=UTC_TIMESTAMP(3)"
                        + " WHERE entity_no=? AND store_no=? AND check_no=? AND version=?",
                actor,
                e,
                store,
                checkNo,
                version);
    }

    public int checkCollaborationUpdateCheckCart3(
            String actor, String e, String store, String checkNo) {
        return db.update(
                "UPDATE fnb_check_cart SET"
                    + " status='ARCHIVED',version=version+1,updated_by=?,updated_at=UTC_TIMESTAMP(3)"
                    + " WHERE entity_no=? AND store_no=? AND check_no=? AND status='OPEN'",
                actor,
                e,
                store,
                checkNo);
    }

    public int checkCollaborationInsertCheckFreeze(
            String entity,
            String store,
            String no,
            String check,
            String check2,
            String sourceHash,
            String reason,
            String actor,
            String actor2) {
        return db.update(
                "INSERT INTO fnb_check_freeze"
                    + " (entity_no,store_no,freeze_no,check_no,active_check_no,source_hash,status,reason,created_at,updated_at,created_by,updated_by)"
                    + " VALUES (?,?,?,?,?,?,'ACTIVE',?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),?,?)",
                entity,
                store,
                no,
                check,
                check2,
                sourceHash,
                reason,
                actor,
                actor2);
    }

    public List<FoodBusinessOperationRow> checkCollaborationSelectCheckFreeze(
            String entity, String store, String no) {
        return rows(
                db.queryForList(
                        "SELECT check_no FROM fnb_check_freeze WHERE entity_no=? AND store_no=? AND"
                                + " freeze_no=?",
                        entity,
                        store,
                        no));
    }

    public List<FoodBusinessOperationRow> checkCollaborationSelectCheckFreeze2(
            String entity, String store, String no) {
        return rows(
                db.queryForList(
                        "SELECT * FROM fnb_check_freeze WHERE entity_no=? AND store_no=? AND"
                                + " freeze_no=? FOR UPDATE",
                        entity,
                        store,
                        no));
    }

    public Long checkCollaborationCountSettlementItem2(String entity, String check, String store) {
        return db.queryForObject(
                "SELECT COUNT(*) FROM fnb_settlement_item i JOIN fnb_settlement s ON"
                        + " s.entity_no=i.entity_no AND s.settlement_no=i.settlement_no WHERE"
                        + " i.entity_no=? AND i.check_no=? AND s.store_no=? AND s.deleted=0 AND"
                        + " s.status<>'CANCELLED'",
                Long.class,
                entity,
                check,
                store);
    }

    public int checkCollaborationUpdateCheckFreeze(String actor, String no, String entity) {
        return db.update(
                "UPDATE fnb_check_freeze SET"
                    + " active_check_no=NULL,status='RELEASED',version=version+1,updated_at=UTC_TIMESTAMP(3),updated_by=?"
                    + " WHERE freeze_no=? AND entity_no=?",
                actor,
                no,
                entity);
    }

    public List<FoodBusinessOperationRow> checkCollaborationSelectCheck(
            String e, String s, String c) {
        return rows(
                db.queryForList(
                        "SELECT version FROM fnb_check WHERE entity_no=? AND store_no=? AND"
                                + " check_no=? AND deleted=0 FOR UPDATE",
                        e,
                        s,
                        c));
    }

    public int checkCollaborationInsertActionLog(
            String e,
            String s,
            String value3,
            String object,
            String action,
            String actor,
            String reason,
            String value8,
            String value9,
            String actor2,
            String actor3) {
        return db.update(
                "INSERT INTO fnb_action_log"
                    + " (entity_no,store_no,action_no,object_type,object_no,action,operator_no,reason,request_no,after_ref,created_at,updated_at,created_by,updated_by)"
                    + " VALUES (?,?,?,'CHECK',?,?,?,?,?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),?,?)",
                e,
                s,
                value3,
                object,
                action,
                actor,
                reason,
                value8,
                value9,
                actor2,
                actor3);
    }

    public Long reservationPreorderCountReservationPreorder(
            String e, String s, String reservation) {
        return db.queryForObject(
                "SELECT COUNT(*) FROM fnb_reservation_preorder WHERE entity_no=? AND store_no=? AND"
                        + " active_reservation_no=?",
                Long.class,
                e,
                s,
                reservation);
    }

    public int reservationPreorderInsertReservationPreorder(
            String e,
            String s,
            String no,
            String reservation,
            String reservation2,
            LocalDateTime value6,
            String value7,
            String a,
            String a2) {
        return db.update(
                "INSERT INTO fnb_reservation_preorder"
                    + " (entity_no,store_no,preorder_no,reservation_no,active_reservation_no,status,cutoff_at,selection_snapshot,created_at,updated_at,created_by,updated_by)"
                    + " VALUES (?,?,?,?,?,'DRAFT',?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),?,?)",
                e,
                s,
                no,
                reservation,
                reservation2,
                value6,
                value7,
                a,
                a2);
    }

    public int reservationPreorderUpdateReservationPreorder(
            String a, String a2, String e, String no) {
        return db.update(
                "UPDATE fnb_reservation_preorder SET"
                    + " status='PREPARING',prepare_actor=?,version=version+1,updated_by=?,updated_at=UTC_TIMESTAMP(3)"
                    + " WHERE entity_no=? AND preorder_no=?",
                a,
                a2,
                e,
                no);
    }

    public int reservationPreorderUpdateReservationPreorder2(
            String cartNo, String a, String e, String no) {
        return db.update(
                "UPDATE fnb_reservation_preorder SET"
                    + " status='READY',cart_no=?,version=version+1,updated_by=?,updated_at=UTC_TIMESTAMP(3)"
                    + " WHERE entity_no=? AND preorder_no=? AND status='PREPARING'",
                cartNo,
                a,
                e,
                no);
    }

    public int reservationPreorderUpdateReservationPreorder3(
            String hash, String value2, String a, String a2, String e, String no) {
        return db.update(
                "UPDATE fnb_reservation_preorder SET"
                    + " status='CONVERTING',conversion_hash=?,conversion_request=?,convert_actor=?,version=version+1,updated_by=?,updated_at=UTC_TIMESTAMP(3)"
                    + " WHERE entity_no=? AND preorder_no=?",
                hash,
                value2,
                a,
                a2,
                e,
                no);
    }

    public int reservationPreorderUpdateReservationPreorder4(
            String state, String batchNo, String state2, String a, String e, String no) {
        return db.update(
                "UPDATE fnb_reservation_preorder SET status=?,batch_no=?,active_reservation_no=CASE"
                        + " WHEN ?='CONVERTED' THEN NULL ELSE active_reservation_no"
                        + " END,version=version+1,updated_by=?,updated_at=UTC_TIMESTAMP(3) WHERE"
                        + " entity_no=? AND preorder_no=?",
                state,
                batchNo,
                state2,
                a,
                e,
                no);
    }

    public int reservationPreorderUpdateReservationPreorder5(
            String reason, String a, String e, String no) {
        return db.update(
                "UPDATE fnb_reservation_preorder SET"
                    + " status='CANCELLED',active_reservation_no=NULL,cancellation_reason=?,version=version+1,updated_by=?,updated_at=UTC_TIMESTAMP(3)"
                    + " WHERE entity_no=? AND preorder_no=?",
                reason,
                a,
                e,
                no);
    }

    public List<FoodBusinessOperationRow> reservationPreorderSelectReservation(
            String e, String s, String no, boolean lock) {
        return rows(
                db.queryForList(
                        "SELECT * FROM fnb_reservation WHERE entity_no=? AND store_no=? AND"
                                + " reservation_no=? AND deleted=0"
                                + (lock ? " FOR UPDATE" : ""),
                        e,
                        s,
                        no));
    }

    public List<FoodBusinessOperationRow> reservationPreorderSelectReservationPreorder(
            String e, String s, String reservation, String no, boolean lock) {
        return rows(
                db.queryForList(
                        "SELECT * FROM fnb_reservation_preorder WHERE entity_no=? AND store_no=?"
                                + " AND reservation_no=? AND preorder_no=? AND deleted=0"
                                + (lock ? " FOR UPDATE" : ""),
                        e,
                        s,
                        reservation,
                        no));
    }

    public List<FoodBusinessOperationRow> serviceSlotSelectStorePolicy(String e, String s) {
        return rows(
                db.queryForList(
                        "SELECT policy_no FROM fnb_store_policy WHERE entity_no=? AND store_no=?"
                                + " AND deleted=0 ORDER BY policy_no LIMIT 1 FOR UPDATE",
                        e,
                        s));
    }

    public int serviceSlotInsertServiceSlot(
            String e,
            String s,
            String no,
            LocalDateTime value4,
            LocalDateTime value5,
            LocalDateTime value6,
            LocalDateTime value7,
            BigDecimal capacity,
            String unit,
            String a,
            String a2) {
        return db.update(
                "INSERT INTO fnb_service_slot"
                    + " (entity_no,store_no,slot_no,starts_at,ends_at,order_cutoff_at,dispatch_at,capacity,reserved_qty,consumed_qty,unit,status,created_at,updated_at,created_by,updated_by)"
                    + " VALUES"
                    + " (?,?,?,?,?,?,?,?,0,0,?,'OPEN',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),?,?)",
                e,
                s,
                no,
                value4,
                value5,
                value6,
                value7,
                capacity,
                unit,
                a,
                a2);
    }

    public List<FoodBusinessOperationRow> serviceSlotSelectCapacityClaim(
            String e, String s, String sourceNo) {
        return rows(
                db.queryForList(
                        "SELECT * FROM fnb_capacity_claim WHERE entity_no=? AND store_no=? AND"
                                + " source_no=? FOR UPDATE",
                        e,
                        s,
                        sourceNo));
    }

    public int serviceSlotInsertCapacityClaim(
            String e,
            String s,
            String no,
            String slotNo,
            String sourceNo,
            BigDecimal quantity,
            String a,
            String a2) {
        return db.update(
                "INSERT INTO fnb_capacity_claim"
                    + " (entity_no,store_no,claim_no,slot_no,source_no,quantity,status,created_at,updated_at,created_by,updated_by)"
                    + " VALUES (?,?,?,?,?,?,'RESERVED',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),?,?)",
                e,
                s,
                no,
                slotNo,
                sourceNo,
                quantity,
                a,
                a2);
    }

    public int serviceSlotUpdateServiceSlot(
            BigDecimal quantity, String a, String e, String slotNo) {
        return db.update(
                "UPDATE fnb_service_slot SET"
                    + " reserved_qty=reserved_qty+?,version=version+1,updated_at=UTC_TIMESTAMP(3),updated_by=?"
                    + " WHERE entity_no=? AND slot_no=?",
                quantity,
                a,
                e,
                slotNo);
    }

    public List<FoodBusinessOperationRow> serviceSlotSelectCapacityClaim2(String e, String no) {
        return rows(
                db.queryForList(
                        "SELECT * FROM fnb_capacity_claim WHERE entity_no=? AND claim_no=?",
                        e,
                        no));
    }

    public List<FoodBusinessOperationRow> serviceSlotSelectCapacityClaim3(
            String e, String s, String no) {
        return rows(
                db.queryForList(
                        "SELECT * FROM fnb_capacity_claim WHERE entity_no=? AND store_no=? AND"
                                + " claim_no=?",
                        e,
                        s,
                        no));
    }

    public List<FoodBusinessOperationRow> serviceSlotSelectCapacityClaim4(
            String e, String s, String no) {
        return rows(
                db.queryForList(
                        "SELECT * FROM fnb_capacity_claim WHERE entity_no=? AND store_no=? AND"
                                + " claim_no=? FOR UPDATE",
                        e,
                        s,
                        no));
    }

    public int serviceSlotUpdateServiceSlot2(
            BigDecimal qty, BigDecimal value2, String a, String e, String slotNo, BigDecimal qty2) {
        return db.update(
                "UPDATE fnb_service_slot SET"
                    + " reserved_qty=reserved_qty-?,consumed_qty=consumed_qty+?,version=version+1,updated_by=?,updated_at=UTC_TIMESTAMP(3)"
                    + " WHERE entity_no=? AND slot_no=? AND reserved_qty>=?",
                qty,
                value2,
                a,
                e,
                slotNo,
                qty2);
    }

    public int serviceSlotUpdateCapacityClaim(String target, String a, String e, String no) {
        return db.update(
                "UPDATE fnb_capacity_claim SET"
                    + " status=?,version=version+1,updated_by=?,updated_at=UTC_TIMESTAMP(3) WHERE"
                    + " entity_no=? AND claim_no=?",
                target,
                a,
                e,
                no);
    }

    public List<FoodBusinessOperationRow> serviceSlotSelectCapacityClaim5(
            String e, String s, String source) {
        return rows(
                db.queryForList(
                        "SELECT c.status,s.dispatch_at FROM fnb_capacity_claim c JOIN"
                                + " fnb_service_slot s ON s.entity_no=c.entity_no AND"
                                + " s.slot_no=c.slot_no WHERE c.entity_no=? AND c.store_no=? AND"
                                + " (c.source_no=? OR c.batch_no=?)",
                        e,
                        s,
                        source,
                        source));
    }

    public List<FoodBusinessOperationRow> serviceSlotSelectServiceSlot(
            String e, String s, String no, boolean lock) {
        return rows(
                db.queryForList(
                        "SELECT * FROM fnb_service_slot WHERE entity_no=? AND store_no=? AND"
                                + " slot_no=? AND deleted=0"
                                + (lock ? " FOR UPDATE" : ""),
                        e,
                        s,
                        no));
    }

    public List<FoodBusinessOperationRow> businessOperationsSelectServiceResponsibility(
            String e, String s, String kind, String sourceNo) {
        return rows(
                db.queryForList(
                        "SELECT owner_no FROM fnb_service_responsibility WHERE entity_no=? AND"
                                + " store_no=? AND source_kind=? AND source_no=?",
                        e,
                        s,
                        kind,
                        sourceNo));
    }

    public int businessOperationsInsertServiceHandover(
            String e,
            String s,
            String no,
            String a,
            String receiverNo,
            String hash,
            String reason,
            String a2,
            String a3) {
        return db.update(
                "INSERT INTO fnb_service_handover"
                    + " (entity_no,store_no,handover_no,from_actor,receiver_no,status,source_hash,reason,created_at,updated_at,created_by,updated_by)"
                    + " VALUES (?,?,?,?,?,'PROPOSED',?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),?,?)",
                e,
                s,
                no,
                a,
                receiverNo,
                hash,
                reason,
                a2,
                a3);
    }

    public int businessOperationsInsertServiceHandoverItem(
            String e,
            String s,
            String value3,
            String no,
            String kind,
            String sourceNo,
            String sourceHash,
            String a,
            String a2) {
        return db.update(
                "INSERT INTO fnb_service_handover_item"
                    + " (entity_no,store_no,item_no,handover_no,source_kind,source_no,source_hash,created_at,updated_at,created_by,updated_by)"
                    + " VALUES (?,?,?,?,?,?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),?,?)",
                e,
                s,
                value3,
                no,
                kind,
                sourceNo,
                sourceHash,
                a,
                a2);
    }

    public List<FoodBusinessOperationRow> businessOperationsSelectServiceHandoverItem(
            String e, String no) {
        return rows(
                db.queryForList(
                        "SELECT * FROM fnb_service_handover_item WHERE entity_no=? AND"
                                + " handover_no=? ORDER BY source_kind,source_no",
                        e,
                        no));
    }

    public List<FoodBusinessOperationRow> businessOperationsSelectServiceResponsibility2(
            String e, String s, String kind, String sourceNo) {
        return rows(
                db.queryForList(
                        "SELECT owner_no FROM fnb_service_responsibility WHERE entity_no=? AND"
                                + " store_no=? AND source_kind=? AND source_no=? FOR UPDATE",
                        e,
                        s,
                        kind,
                        sourceNo));
    }

    public int businessOperationsInsertServiceResponsibility(
            String e,
            String s,
            String value3,
            String kind,
            String sourceNo,
            String a,
            String no,
            String a2,
            String a3) {
        return db.update(
                "INSERT INTO fnb_service_responsibility"
                    + " (entity_no,store_no,responsibility_no,source_kind,source_no,owner_no,handover_no,created_at,updated_at,created_by,updated_by)"
                    + " VALUES (?,?,?,?,?,?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),?,?)",
                e,
                s,
                value3,
                kind,
                sourceNo,
                a,
                no,
                a2,
                a3);
    }

    public int businessOperationsUpdateServiceResponsibility(
            String a, String no, String a2, String e, String s, String kind, String sourceNo) {
        return db.update(
                "UPDATE fnb_service_responsibility SET"
                    + " owner_no=?,handover_no=?,version=version+1,updated_by=?,updated_at=UTC_TIMESTAMP(3)"
                    + " WHERE entity_no=? AND store_no=? AND source_kind=? AND source_no=?",
                a,
                no,
                a2,
                e,
                s,
                kind,
                sourceNo);
    }

    public int businessOperationsUpdateCheck(
            String a, String a2, String e, String s, String sourceNo) {
        return db.update(
                "UPDATE fnb_check SET"
                    + " service_employee_no=?,version=version+1,updated_by=?,updated_at=UTC_TIMESTAMP(3)"
                    + " WHERE entity_no=? AND store_no=? AND check_no=?",
                a,
                a2,
                e,
                s,
                sourceNo);
    }

    public int businessOperationsUpdateServiceHandover(
            String value1, String a, String a2, String e, String no) {
        return db.update(
                "UPDATE fnb_service_handover SET"
                    + " status=?,version=version+1,decided_by=?,decided_at=UTC_TIMESTAMP(3),updated_by=?,updated_at=UTC_TIMESTAMP(3)"
                    + " WHERE entity_no=? AND handover_no=?",
                value1,
                a,
                a2,
                e,
                no);
    }

    public List<FoodBusinessOperationRow> businessOperationsSelectOperationCase(
            String e, String s, String active) {
        return rows(
                db.queryForList(
                        "SELECT * FROM fnb_operation_case WHERE entity_no=? AND store_no=? AND"
                                + " active_source=?",
                        e,
                        s,
                        active));
    }

    public int businessOperationsInsertOperationCase(
            String e,
            String s,
            String no,
            String kind,
            String sourceNo,
            String active,
            String ownerNo,
            String sourceHash,
            String reason,
            String a,
            String a2) {
        return db.update(
                "INSERT INTO fnb_operation_case"
                    + " (entity_no,store_no,case_no,source_kind,source_no,active_source,owner_no,status,source_hash,reason,created_at,updated_at,created_by,updated_by)"
                    + " VALUES (?,?,?,?,?,?,?,'OPEN',?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),?,?)",
                e,
                s,
                no,
                kind,
                sourceNo,
                active,
                ownerNo,
                sourceHash,
                reason,
                a,
                a2);
    }

    public int businessOperationsUpdateOperationCase(String a, String e, String no) {
        return db.update(
                "UPDATE fnb_operation_case SET"
                    + " status='RECOVERING',version=version+1,updated_by=?,updated_at=UTC_TIMESTAMP(3)"
                    + " WHERE entity_no=? AND case_no=?",
                a,
                e,
                no);
    }

    public int businessOperationsUpdateOperationCase2(
            String sourceHash, String reason, String a, String e, String no) {
        return db.update(
                "UPDATE fnb_operation_case SET"
                    + " status='RESOLVED',active_source=NULL,resolution_hash=?,resolution_reason=?,version=version+1,updated_by=?,updated_at=UTC_TIMESTAMP(3)"
                    + " WHERE entity_no=? AND case_no=?",
                sourceHash,
                reason,
                a,
                e,
                no);
    }

    public List<FoodBusinessOperationRow> businessOperationsSelectServiceHandover(
            String e, String s, String no, boolean lock) {
        return rows(
                db.queryForList(
                        "SELECT * FROM fnb_service_handover WHERE entity_no=? AND store_no=? AND"
                                + " handover_no=?"
                                + (lock ? " FOR UPDATE" : ""),
                        e,
                        s,
                        no));
    }

    public List<FoodBusinessOperationRow> businessOperationsSelectOperationCase2(
            String e, String s, String no, boolean lock) {
        return rows(
                db.queryForList(
                        "SELECT * FROM fnb_operation_case WHERE entity_no=? AND store_no=? AND"
                                + " case_no=?"
                                + (lock ? " FOR UPDATE" : ""),
                        e,
                        s,
                        no));
    }

    public int businessOperationsInsertActionLog(
            String e,
            String s,
            String value3,
            String no,
            String action,
            String a,
            String reason,
            String value8,
            String hash,
            String a2,
            String a3) {
        return db.update(
                "INSERT INTO fnb_action_log"
                    + " (entity_no,store_no,action_no,object_type,object_no,action,operator_no,reason,request_no,after_ref,created_at,updated_at,created_by,updated_by)"
                    + " VALUES"
                    + " (?,?,?,'OPERATIONS',?,?,?,?,?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),?,?)",
                e,
                s,
                value3,
                no,
                action,
                a,
                reason,
                value8,
                hash,
                a2,
                a3);
    }

    public int commandReceiptInsertBusinessCommand(
            String scopeHash, String keyHash, String actor, String hash) {
        return db.update(
                "INSERT INTO fnb_business_command"
                        + " (scope_hash,request_key_hash,actor_no,request_hash,created_at) VALUES"
                        + " (?,?,?,?,UTC_TIMESTAMP(3))",
                scopeHash,
                keyHash,
                actor,
                hash);
    }

    public int commandReceiptUpdateBusinessCommand(
            String value1, String scopeHash, String keyHash) {
        return db.update(
                "UPDATE fnb_business_command SET result_json=? WHERE scope_hash=? AND"
                        + " request_key_hash=?",
                value1,
                scopeHash,
                keyHash);
    }

    public List<FoodBusinessOperationRow> commandReceiptSelectBusinessCommand(
            String scope, String key) {
        return rows(
                db.queryForList(
                        "SELECT actor_no,request_hash,result_json FROM fnb_business_command WHERE"
                                + " scope_hash=? AND request_key_hash=? FOR UPDATE",
                        scope,
                        key));
    }

    public List<FoodBusinessOperationRow> scheduledOrderSlots(
            String e, String store, LocalDateTime starts) {
        return rows(
                db.queryForList(
                        "SELECT slot_no FROM fnb_service_slot WHERE entity_no=? AND store_no=? AND"
                            + " starts_at=? AND unit='ORDER' AND status='OPEN' AND deleted=0 ORDER"
                            + " BY slot_no FOR UPDATE",
                        e,
                        store,
                        starts));
    }

    public List<FoodBusinessOperationRow> capacityBySource(String e, String store, String source) {
        return rows(
                db.queryForList(
                        "SELECT * FROM fnb_capacity_claim WHERE entity_no=? AND store_no=? AND"
                                + " source_no=?",
                        e,
                        store,
                        source));
    }

    public List<FoodBusinessOperationRow> capacityByBatch(String e, String store, String batch) {
        return rows(
                db.queryForList(
                        "SELECT * FROM fnb_capacity_claim WHERE entity_no=? AND store_no=? AND"
                                + " batch_no=?",
                        e,
                        store,
                        batch));
    }

    public List<FoodBusinessOperationRow> capacityOriginalBatch(
            String e, String store, String batch) {
        return rows(
                db.queryForList(
                        "SELECT cart_no,status FROM fnb_order_batch WHERE entity_no=? AND"
                                + " store_no=? AND batch_no=? AND deleted=0 FOR UPDATE",
                        e,
                        store,
                        batch));
    }

    public int bindCapacityBatch(
            String e, String store, String source, String batch, String actor) {
        return db.update(
                "UPDATE fnb_capacity_claim SET"
                    + " batch_no=?,version=version+1,updated_at=UTC_TIMESTAMP(3),updated_by=? WHERE"
                    + " entity_no=? AND store_no=? AND source_no=? AND (batch_no IS NULL OR"
                    + " batch_no=?)",
                batch,
                actor,
                e,
                store,
                source,
                batch);
    }

    public int releaseConsumedCapacity(String e, String slot, BigDecimal qty, String actor) {
        return db.update(
                "UPDATE fnb_service_slot SET"
                    + " consumed_qty=consumed_qty-?,version=version+1,updated_at=UTC_TIMESTAMP(3),updated_by=?"
                    + " WHERE entity_no=? AND slot_no=? AND consumed_qty>=?",
                qty,
                actor,
                e,
                slot,
                qty);
    }

    public int markCapacityReleased(String e, String claim, String actor) {
        return db.update(
                "UPDATE fnb_capacity_claim SET"
                    + " status='RELEASED',version=version+1,updated_at=UTC_TIMESTAMP(3),updated_by=?"
                    + " WHERE entity_no=? AND claim_no=? AND status='CONSUMED'",
                actor,
                e,
                claim);
    }

    public int createScheduledDispatch(
            String entity, String store, String batch, LocalDateTime dispatchAt, String actor) {
        return db.update(
                "INSERT INTO fnb_scheduled_dispatch"
                    + " (entity_no,store_no,batch_no,status,dispatch_at,next_attempt_at,created_at,updated_at,created_by,updated_by)"
                    + " VALUES (?,?,?,'PENDING',?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),?,?) ON"
                    + " DUPLICATE KEY UPDATE batch_no=VALUES(batch_no)",
                entity,
                store,
                batch,
                dispatchAt,
                dispatchAt,
                actor,
                actor);
    }

    public List<FoodBusinessOperationRow> dueScheduledDispatch(LocalDateTime now, int limit) {
        return rows(
                db.queryForList(
                        "SELECT * FROM fnb_scheduled_dispatch WHERE status IN ('PENDING','RETRY')"
                                + " AND dispatch_at<=? AND next_attempt_at<=? ORDER BY"
                                + " next_attempt_at,id LIMIT ?",
                        now,
                        now,
                        limit));
    }

    public int claimScheduledDispatch(
            String entity,
            String store,
            String batch,
            long version,
            LocalDateTime now,
            LocalDateTime lease) {
        return db.update(
                "UPDATE fnb_scheduled_dispatch SET"
                    + " next_attempt_at=?,version=version+1,attempts=attempts+1,updated_at=UTC_TIMESTAMP(3),updated_by='SYSTEM'"
                    + " WHERE entity_no=? AND store_no=? AND batch_no=? AND version=? AND"
                    + " next_attempt_at<=? AND status IN ('PENDING','RETRY')",
                lease,
                entity,
                store,
                batch,
                version,
                now);
    }

    public int finishScheduledDispatch(
            String entity,
            String store,
            String batch,
            long version,
            String status,
            String error,
            LocalDateTime next) {
        return db.update(
                "UPDATE fnb_scheduled_dispatch SET"
                    + " status=?,last_error=?,next_attempt_at=?,version=version+1,updated_at=UTC_TIMESTAMP(3),updated_by='SYSTEM'"
                    + " WHERE entity_no=? AND store_no=? AND batch_no=? AND version=?",
                status,
                error,
                next,
                entity,
                store,
                batch,
                version);
    }

    public List<FoodBusinessOperationRow> reservationActivePreorders(
            String entity, String store, String reservation) {
        return rows(
                db.queryForList(
                        "SELECT * FROM fnb_reservation_preorder WHERE entity_no=? AND store_no=?"
                                + " AND active_reservation_no=? ORDER BY preorder_no FOR UPDATE",
                        entity,
                        store,
                        reservation));
    }

    public int cancelReservationPreorder(
            String entity, String preorder, String reason, String actor) {
        return db.update(
                "UPDATE fnb_reservation_preorder SET"
                    + " status='CANCELLED',active_reservation_no=NULL,cancellation_reason=?,version=version+1,updated_at=UTC_TIMESTAMP(3),updated_by=?"
                    + " WHERE entity_no=? AND preorder_no=? AND status IN ('DRAFT','READY')",
                reason,
                actor,
                entity,
                preorder);
    }

    public List<FoodBusinessOperationRow> closeOperationCases(
            String entity, String store, LocalDateTime from, LocalDateTime until) {
        return rows(
                db.queryForList(
                        "SELECT"
                            + " case_no,source_kind,source_no,status,source_hash,resolution_hash,version"
                            + " FROM fnb_operation_case WHERE entity_no=? AND store_no=? AND"
                            + " created_at>=? AND created_at<? ORDER BY case_no",
                        entity,
                        store,
                        from,
                        until));
    }

    public List<FoodBusinessOperationRow> closePendingHandovers(
            String entity, String store, LocalDateTime from, LocalDateTime until) {
        return rows(
                db.queryForList(
                        "SELECT handover_no,status,source_hash,version FROM fnb_service_handover"
                            + " WHERE entity_no=? AND store_no=? AND created_at>=? AND created_at<?"
                            + " ORDER BY handover_no",
                        entity,
                        store,
                        from,
                        until));
    }

    public List<FoodBusinessOperationRow> closeSourceReference(
            String entity, String store, String reference, LocalDate date) {
        return rows(
                db.queryForList(
                        "SELECT"
                            + " k.binding_no,k.payment_root_no,k.store_order_no,k.status,k.version,b.batch_no,b.business_date"
                            + " FROM fnb_core_order_binding k JOIN fnb_order_batch b ON"
                            + " b.entity_no=k.entity_no AND b.batch_no=k.batch_no WHERE"
                            + " b.entity_no=? AND b.store_no=? AND b.business_date=? AND"
                            + " k.deleted=0 AND b.deleted=0 AND (k.binding_no=? OR"
                            + " k.payment_root_no=? OR k.store_order_no=?) ORDER BY k.binding_no",
                        entity,
                        store,
                        date,
                        reference,
                        reference,
                        reference));
    }

    public int recordQueueNotice(
            String e,
            String store,
            String no,
            String ticket,
            long version,
            String contact,
            String actor) {
        return db.update(
                "INSERT INTO fnb_queue_notice"
                    + " (entity_no,store_no,notice_no,ticket_no,ticket_version,contact_ref,status,next_attempt_at,created_at,updated_at,created_by,updated_by)"
                    + " VALUES"
                    + " (?,?,?,?,?,?,'PENDING',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),?,?)"
                    + " ON DUPLICATE KEY UPDATE ticket_no=VALUES(ticket_no)",
                e,
                store,
                no,
                ticket,
                version,
                contact,
                actor,
                actor);
    }

    public List<FoodBusinessOperationRow> queueNotices(String e, String store, String ticket) {
        return rows(
                db.queryForList(
                        "SELECT * FROM fnb_queue_notice WHERE entity_no=? AND store_no=? AND"
                                + " ticket_no=? ORDER BY ticket_version",
                        e,
                        store,
                        ticket));
    }

    public List<FoodBusinessOperationRow> queueNotice(String e, String store, String no) {
        return rows(
                db.queryForList(
                        "SELECT * FROM fnb_queue_notice WHERE entity_no=? AND store_no=? AND"
                                + " notice_no=?",
                        e,
                        store,
                        no));
    }

    public List<FoodBusinessOperationRow> dueQueueNotices(LocalDateTime now, int limit) {
        return rows(
                db.queryForList(
                        "SELECT * FROM fnb_queue_notice WHERE status IN"
                            + " ('PENDING','ACCEPTED','UNKNOWN') AND next_attempt_at<=? ORDER BY"
                            + " next_attempt_at,id LIMIT ?",
                        now,
                        limit));
    }

    public int claimQueueNotice(
            String e,
            String store,
            String no,
            long version,
            LocalDateTime now,
            LocalDateTime lease) {
        return db.update(
                "UPDATE fnb_queue_notice SET"
                        + " next_attempt_at=?,version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE"
                        + " entity_no=? AND store_no=? AND notice_no=? AND version=? AND"
                        + " next_attempt_at<=? AND status IN ('PENDING','ACCEPTED','UNKNOWN')",
                lease,
                e,
                store,
                no,
                version,
                now);
    }

    public int finishQueueNotice(
            String e,
            String store,
            String no,
            long version,
            String status,
            String receipt,
            String error,
            LocalDateTime next) {
        return db.update(
                "UPDATE fnb_queue_notice SET"
                    + " status=?,provider_ref=?,last_error=?,next_attempt_at=?,version=version+1,updated_at=UTC_TIMESTAMP(3)"
                    + " WHERE entity_no=? AND store_no=? AND notice_no=? AND version=?",
                status,
                receipt,
                error,
                next,
                e,
                store,
                no,
                version);
    }

    public List<FoodBusinessOperationRow> feeBindings(String e, String store, String check) {
        return rows(
                db.queryForList(
                        "SELECT * FROM fnb_fee_source_binding WHERE entity_no=? AND store_no=? AND"
                                + " (check_no IS NULL OR check_no=?) ORDER BY rule_no,check_no",
                        e,
                        store,
                        check));
    }

    public List<FoodBusinessOperationRow> feeBinding(
            String e, String store, String rule, String scope, boolean lock) {
        return rows(
                db.queryForList(
                        "SELECT * FROM fnb_fee_source_binding WHERE entity_no=? AND store_no=? AND"
                                + " rule_no=? AND binding_scope=?"
                                + (lock ? " FOR UPDATE" : ""),
                        e,
                        store,
                        rule,
                        scope));
    }

    public int insertFeeBinding(
            String e,
            String store,
            String no,
            String check,
            String rule,
            String revision,
            String scope,
            String kind,
            String unit,
            BigDecimal quantity,
            String refs,
            String waiver,
            boolean enabled,
            String actor) {
        return db.update(
                "INSERT INTO fnb_fee_source_binding"
                    + " (entity_no,store_no,binding_no,check_no,rule_no,revision_no,binding_scope,kind,unit,package_quantity,basis_source_refs,waiver_evidence,enabled,version,created_at,updated_at,created_by,updated_by)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),?,?)",
                e,
                store,
                no,
                check,
                rule,
                revision,
                scope,
                kind,
                unit,
                quantity,
                refs,
                waiver,
                enabled,
                actor,
                actor);
    }

    public int updateFeeBinding(
            String e,
            String store,
            String rule,
            String scope,
            String revision,
            String kind,
            String unit,
            BigDecimal quantity,
            String refs,
            String waiver,
            boolean enabled,
            long version,
            String actor) {
        return db.update(
                "UPDATE fnb_fee_source_binding SET"
                    + " revision_no=?,kind=?,unit=?,package_quantity=?,basis_source_refs=?,waiver_evidence=?,enabled=?,version=version+1,updated_at=UTC_TIMESTAMP(3),updated_by=?"
                    + " WHERE entity_no=? AND store_no=? AND rule_no=? AND binding_scope=? AND"
                    + " version=?",
                revision,
                kind,
                unit,
                quantity,
                refs,
                waiver,
                enabled,
                actor,
                e,
                store,
                rule,
                scope,
                version);
    }

    public List<FoodBusinessOperationRow> feeCheck(String e, String store, String check) {
        return rows(
                db.queryForList(
                        "SELECT party_size FROM fnb_check WHERE entity_no=? AND store_no=? AND"
                                + " check_no=? AND deleted=0 AND status='OPEN' AND"
                                + " service_state='OPEN'",
                        e,
                        store,
                        check));
    }

    public int resizeReservedCapacity(
            String e,
            String store,
            String source,
            BigDecimal quantity,
            long version,
            String actor) {
        return db.update(
                "UPDATE fnb_capacity_claim SET"
                    + " quantity=?,version=version+1,updated_at=UTC_TIMESTAMP(3),updated_by=? WHERE"
                    + " entity_no=? AND store_no=? AND source_no=? AND version=? AND"
                    + " status='RESERVED'",
                quantity,
                actor,
                e,
                store,
                source,
                version);
    }

    public int changeReservedCapacity(String e, String slot, BigDecimal delta, String actor) {
        return db.update(
                "UPDATE fnb_service_slot SET"
                    + " reserved_qty=reserved_qty+?,version=version+1,updated_at=UTC_TIMESTAMP(3),updated_by=?"
                    + " WHERE entity_no=? AND slot_no=? AND reserved_qty+?>=0 AND"
                    + " reserved_qty+consumed_qty+?<=capacity",
                delta,
                actor,
                e,
                slot,
                delta,
                delta);
    }

    public Long activeServiceForms(String entity, String store, String check) {
        return db.queryForObject(
                "SELECT COUNT(*) FROM fnb_service_form WHERE entity_no=? AND store_no=? AND"
                        + " active_check_no=? AND status='ACTIVE'",
                Long.class,
                entity,
                store,
                check);
    }

    public List<FoodBusinessOperationRow> checkFormEvidence(
            String entity, String store, String check) {
        return rows(
                db.queryForList(
                        "SELECT form_no,kind,status,guaranteed_persons,actual_persons,version FROM"
                            + " fnb_service_form WHERE entity_no=? AND store_no=? AND check_no=?"
                            + " ORDER BY form_no",
                        entity,
                        store,
                        check));
    }

    public List<FoodBusinessOperationRow> closeServiceForms(
            String entity, String store, LocalDateTime from, LocalDateTime until) {
        return rows(
                db.queryForList(
                        "SELECT"
                            + " form_no,kind,status,menu_revision_no,guaranteed_persons,actual_persons,check_no,version"
                            + " FROM fnb_service_form WHERE entity_no=? AND store_no=? AND"
                            + " starts_at>=? AND starts_at<? ORDER BY form_no",
                        entity,
                        store,
                        from,
                        until));
    }

    public List<FoodBusinessOperationRow> currentCoreBindings(
            String e, String store, String check) {
        return rows(
                db.queryForList(
                        "SELECT DISTINCT b.batch_no,k.payment_root_no,k.store_order_no FROM"
                            + " fnb_order_batch b JOIN fnb_core_order_binding k ON"
                            + " k.entity_no=b.entity_no AND k.batch_no=b.batch_no AND"
                            + " k.status='BOUND' AND k.deleted=0 JOIN fnb_order_line l ON"
                            + " l.entity_no=b.entity_no AND l.batch_no=b.batch_no AND l.deleted=0"
                            + " LEFT JOIN fnb_check_line_membership lm ON lm.entity_no=l.entity_no"
                            + " AND lm.active_line_no=l.food_line_no LEFT JOIN"
                            + " fnb_check_batch_membership bm ON bm.entity_no=b.entity_no AND"
                            + " bm.batch_no=b.batch_no AND bm.detached_at IS NULL AND bm.deleted=0"
                            + " WHERE b.entity_no=? AND b.store_no=? AND b.deleted=0 AND"
                            + " COALESCE(lm.check_no,bm.check_no,b.check_no)=? ORDER BY b.batch_no",
                        e,
                        store,
                        check));
    }

    public List<FoodBusinessOperationRow> inheritedFeeContracts(
            String e, String store, String check, String rule) {
        return rows(
                db.queryForList(
                        "SELECT DISTINCT c.scope_no,c.quantity,c.kind,c.revision_no,c.id FROM"
                            + " fnb_fee_contract_claim c JOIN fnb_order_batch b ON"
                            + " b.entity_no=c.entity_no AND b.store_no=c.store_no AND"
                            + " b.cart_no=c.cart_no AND b.cart_version=c.cart_version JOIN"
                            + " fnb_order_line l ON l.entity_no=b.entity_no AND"
                            + " l.batch_no=b.batch_no AND l.deleted=0 LEFT JOIN"
                            + " fnb_check_line_membership lm ON lm.entity_no=l.entity_no AND"
                            + " lm.active_line_no=l.food_line_no LEFT JOIN"
                            + " fnb_check_batch_membership bm ON bm.entity_no=b.entity_no AND"
                            + " bm.batch_no=b.batch_no AND bm.detached_at IS NULL AND bm.deleted=0"
                            + " WHERE c.entity_no=? AND c.store_no=? AND c.rule_no=? AND"
                            + " b.status='ACCEPTED' AND b.deleted=0 AND"
                            + " COALESCE(lm.check_no,bm.check_no,b.check_no)=? ORDER BY c.id DESC",
                        e,
                        store,
                        rule,
                        check));
    }

    public List<FoodBusinessOperationRow> cartFeeContract(
            String e, String store, String cart, long version, String rule) {
        return rows(
                db.queryForList(
                        "SELECT * FROM fnb_fee_contract_claim WHERE entity_no=? AND store_no=? AND"
                                + " cart_no=? AND cart_version=? AND rule_no=?",
                        e,
                        store,
                        cart,
                        version,
                        rule));
    }

    public int cartFeeContract(
            String e,
            String store,
            String cart,
            long version,
            String rule,
            String revision,
            String scope,
            String kind,
            BigDecimal quantity,
            String hash) {
        return db.update(
                "INSERT INTO"
                    + " fnb_fee_contract_claim(entity_no,store_no,cart_no,cart_version,rule_no,revision_no,scope_no,kind,quantity,source_hash)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?)",
                e,
                store,
                cart,
                version,
                rule,
                revision,
                scope,
                kind,
                quantity,
                hash);
    }

    public int bindingContract(String e, String store, String rule, String check, String contract) {
        return db.update(
                "UPDATE fnb_fee_source_binding SET contract_scope_no=? WHERE entity_no=? AND"
                        + " store_no=? AND rule_no=? AND binding_scope=?",
                contract,
                e,
                store,
                rule,
                check);
    }
}
