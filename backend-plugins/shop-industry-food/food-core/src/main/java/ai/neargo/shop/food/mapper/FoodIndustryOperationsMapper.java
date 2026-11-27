package ai.neargo.shop.food.mapper;

import ai.neargo.shop.food.entity.FoodBusinessOperationRow;

import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

/**
 * 餐饮业务操作里只碰行业库的那部分（ADR-068 及修订一、二）：服务交接与责任、定时出品、门店策略、桌台、
 * 堂食权益、占台、时段与容量、订位、制作任务。
 * 原在 {@link FoodBusinessOperationsMapper}（交易句柄）；拆出来后只走行业句柄，交易表不在这里出现。
 */
public class FoodIndustryOperationsMapper {
    private final JdbcTemplate db;

    public FoodIndustryOperationsMapper(DataSource source) {
        db = new JdbcTemplate(source);
    }

    private static List<FoodBusinessOperationRow> rows(List<Map<String, Object>> rows) {
        return rows.stream().map(FoodBusinessOperationRow::new).toList();
    }

    public Long checkCollaborationCountDiningEntitlement(String e, String check) {
        return db.queryForObject(
                "SELECT COUNT(*) FROM fnb_dining_entitlement WHERE entity_no=? AND check_no=? AND"
                        + " status='ACTIVE' AND deleted=0",
                Long.class,
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

    public List<FoodBusinessOperationRow> serviceSlotSelectStorePolicy(String e, String s) {
        return rows(
                db.queryForList(
                        // 只确认门店已开餐饮；时段写在交易库，这里不跨库加锁。
                        "SELECT policy_no FROM fnb_store_policy WHERE entity_no=? AND store_no=?"
                                + " AND deleted=0 ORDER BY policy_no LIMIT 1",
                        e,
                        s));
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

    /** 账单上的堂食权益（不论状态）：账单释放前要求为零。 */
    public long diningEntitlementFacts(String e, String check) {
        Long count =
                db.queryForObject(
                        "SELECT COUNT(*) FROM fnb_dining_entitlement WHERE entity_no=? AND check_no=?",
                        Long.class,
                        e,
                        check);
        return count == null ? 0 : count;
    }

    // 以下原在 FoodBusinessOperationsMapper：占台、时段与容量、订位、制作任务都归行业库（ADR-068 修订一、修订二）。

    public Long checkCollaborationCountKitchenTask(String e, String line) {
        return db.queryForObject(
                "SELECT COUNT(*) FROM fnb_kitchen_task WHERE entity_no=? AND food_line_no=? AND"
                        + " deleted=0 AND status NOT IN ('SERVED','VOIDED')",
                Long.class,
                e,
                line);
    }

    public List<FoodBusinessOperationRow> checkCollaborationSelectOccupiedTables(
            String e, String check) {
        return rows(
                db.queryForList(
                        // 只读占台（门店交易库）；桌台在行业库，不再联表。账单号已按门店校验过。
                        "SELECT o.table_no FROM fnb_table_occupancy o WHERE o.entity_no=? AND"
                            + " o.check_no=? AND o.ended_at IS NULL AND o.deleted=0 ORDER BY"
                            + " o.table_no FOR UPDATE",
                        e,
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

    /**
     * 已绑定批次、计划出品时间落在窗口内的时段占用：定时出品单（行业库）在交易提交后才建，
     * 定时出品任务用它补建漏掉的。
     */
    public List<FoodBusinessOperationRow> boundScheduledBatches(
            LocalDateTime since, LocalDateTime until, int limit) {
        return rows(
                db.queryForList(
                        "SELECT c.entity_no,c.store_no,c.batch_no,s.dispatch_at FROM"
                            + " fnb_capacity_claim c JOIN fnb_service_slot s ON"
                            + " s.entity_no=c.entity_no AND s.slot_no=c.slot_no WHERE c.batch_no IS"
                            + " NOT NULL AND c.status='CONSUMED' AND s.dispatch_at>=? AND"
                            + " s.dispatch_at<=? ORDER BY s.dispatch_at,c.id LIMIT ?",
                        since,
                        until,
                        limit));
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

    // 以下原在 ServiceFormMapper（交易句柄）：菜单发布修订、订位在行业库。

    public List<FoodBusinessOperationRow> serviceFormMenuRevision(String e, String revision) {
        return rows(
                db.queryForList(
                        "SELECT config_no,definition_snapshot,content_hash,status FROM"
                                + " fnb_config_revision WHERE entity_no=? AND revision_no=? AND"
                                + " config_kind='MENU' AND status IN ('EFFECTIVE','SCHEDULED')",
                        e,
                        revision));
    }

    public List<FoodBusinessOperationRow> serviceFormReservation(
            String e, String store, String no, boolean lock) {
        return rows(
                db.queryForList(
                        "SELECT * FROM fnb_reservation WHERE entity_no=? AND store_no=? AND"
                                + " reservation_no=? AND deleted=0"
                                + (lock ? " FOR UPDATE" : ""),
                        e,
                        store,
                        no));
    }

    /** 开台时间落在窗口内、仍未结束的占台（按账单号去重，带门店）：开台补偿扫描用。 */
    public List<FoodBusinessOperationRow> activeOccupanciesStartedBetween(
            LocalDateTime since, LocalDateTime until, int limit) {
        return rows(
                db.queryForList(
                        "SELECT DISTINCT o.entity_no,t.store_no,o.check_no FROM fnb_table_occupancy o"
                            + " JOIN fnb_table t ON t.entity_no=o.entity_no AND t.table_no=o.table_no"
                            + " WHERE o.ended_at IS NULL AND o.deleted=0 AND o.started_at>=? AND"
                            + " o.started_at<? ORDER BY o.check_no LIMIT ?",
                        since,
                        until,
                        limit));
    }
}
