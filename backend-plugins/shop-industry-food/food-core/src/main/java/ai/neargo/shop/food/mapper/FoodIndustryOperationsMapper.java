package ai.neargo.shop.food.mapper;

import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.food.entity.FoodBusinessOperationRow;
import ai.neargo.shop.food.policy.FoodCommandFingerprint;
import ai.neargo.shop.food.service.support.CommandBusinessKey;

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
                        "SELECT policy_no FROM ind_store_policy WHERE tenant_no='MAIN' AND entity_no=?"
                                + " AND store_no=? AND industry_code='FNB' AND deleted=0 LIMIT 1",
                        e,
                        s));
    }

    // ---- 服务交接与责任 → ind_assignment（TDD §2.5 合并 ⑦）----
    // 责任：assignment_kind=RESPONSIBLE、status=ACTIVE、ended_at 为空的那一条；一个对象同一时刻只有一条。
    // 交接：每个对象一条 PENDING 指派（personnel_no=接收人，created_by=发起人），按 handover_no 归组；
    // 接收后旧责任 ENDED、这条 ACTIVE（previous_assignment_no 指向旧条）；取消为 CANCELLED。

    private static final String RESPONSIBLE = "assignment_kind='RESPONSIBLE'";

    /** 交接单的来源摘要：各明细来源摘要按对象排序后的摘要。 */
    public static String handoverHash(List<String> itemHashes) {
        return FoodCommandFingerprint.hash(itemHashes.toArray(String[]::new));
    }

    /** 对象当前的负责人（ACTIVE 责任）。 */
    public List<FoodBusinessOperationRow> businessOperationsSelectServiceResponsibility(
            String e, String s, String kind, String sourceNo) {
        return rows(
                db.queryForList(
                        "SELECT assignment_no,personnel_no AS owner_no FROM ind_assignment WHERE tenant_no='MAIN'"
                                + " AND entity_no=? AND store_no=? AND " + RESPONSIBLE + " AND target_type=? AND"
                                + " target_no=? AND status='ACTIVE' AND ended_at IS NULL AND deleted=0",
                        e,
                        s,
                        kind,
                        sourceNo));
    }

    /**
     * 锁住对象的全部责任指派（含各交接的待接收条），同一对象上的接收因此串行；返回其中在负责的那条。
     */
    public List<FoodBusinessOperationRow> businessOperationsSelectServiceResponsibility2(
            String e, String s, String kind, String sourceNo) {
        return rows(
                        db.queryForList(
                                "SELECT assignment_no,personnel_no AS owner_no,status,ended_at FROM ind_assignment"
                                        + " WHERE tenant_no='MAIN' AND entity_no=? AND store_no=? AND " + RESPONSIBLE
                                        + " AND target_type=? AND target_no=? AND deleted=0 FOR UPDATE",
                                e,
                                s,
                                kind,
                                sourceNo))
                .stream()
                .filter(r -> "ACTIVE".equals(r.text("status")) && r.get("ended_at") == null)
                .toList();
    }

    /** 发起交接：每个对象一条待接收指派。指派号由交接单号与对象派生，同一请求并发重放撞唯一键。 */
    public void businessOperationsInsertServiceHandover(
            String e,
            String s,
            String no,
            String from,
            String receiverNo,
            String reason,
            List<String[]> items) {
        for (String[] item : items)
            db.update(
                    "INSERT INTO ind_assignment (tenant_no,entity_no,store_no,assignment_no,target_type,target_no,"
                            + "assignment_kind,personnel_no,handover_no,handover_reason,source_hash,status,deleted,"
                            + "version,created_at,updated_at,created_by,updated_by) VALUES ('MAIN',?,?,?,?,?,"
                            + "'RESPONSIBLE',?,?,?,?,'PENDING',0,0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),?,?)",
                    e,
                    s,
                    CommandBusinessKey.derive("FASG", "SERVICE_HANDOVER", e, s, no, item[0] + ":" + item[1]),
                    item[0],
                    item[1],
                    receiverNo,
                    no,
                    reason,
                    item[2],
                    from,
                    from);
    }

    /** 交接明细（按对象排序），列名沿用旧表。 */
    public List<FoodBusinessOperationRow> businessOperationsSelectServiceHandoverItem(
            String e, String no) {
        return rows(
                db.queryForList(
                        "SELECT assignment_no AS item_no,handover_no,target_type AS source_kind,target_no AS"
                                + " source_no,source_hash FROM ind_assignment WHERE tenant_no='MAIN' AND entity_no=?"
                                + " AND handover_no=? AND deleted=0 ORDER BY target_type,target_no",
                        e,
                        no));
    }

    /** 接收一条：结束旧责任，这条待接收指派生效。 */
    public int businessOperationsAcceptHandoverItem(
            String e, String s, String no, String kind, String sourceNo, String previous, String actor) {
        if (previous != null
                && db.update(
                                "UPDATE ind_assignment SET status='ENDED',ended_at=UTC_TIMESTAMP(3),version=version+1,"
                                        + "updated_at=UTC_TIMESTAMP(3),updated_by=? WHERE tenant_no='MAIN' AND"
                                        + " entity_no=? AND assignment_no=? AND status='ACTIVE'",
                                actor,
                                e,
                                previous)
                        != 1) return 0;
        return db.update(
                "UPDATE ind_assignment SET status='ACTIVE',started_at=UTC_TIMESTAMP(3),previous_assignment_no=?,"
                        + "version=version+1,updated_at=UTC_TIMESTAMP(3),updated_by=? WHERE tenant_no='MAIN' AND"
                        + " entity_no=? AND store_no=? AND handover_no=? AND target_type=? AND target_no=? AND"
                        + " status='PENDING'",
                previous,
                actor,
                e,
                s,
                no,
                kind,
                sourceNo);
    }

    /** 取消：全部待接收指派置为已取消。 */
    public int businessOperationsCancelHandover(String e, String s, String no, String actor) {
        return db.update(
                "UPDATE ind_assignment SET status='CANCELLED',version=version+1,updated_at=UTC_TIMESTAMP(3),"
                        + "updated_by=? WHERE tenant_no='MAIN' AND entity_no=? AND store_no=? AND handover_no=?"
                        + " AND status='PENDING'",
                actor,
                e,
                s,
                no);
    }

    /**
     * 交接单（由明细汇总，列名沿用旧表）：任一条待接收为 PROPOSED，已取消为 CANCELLED，否则 ACCEPTED；
     * 发起人取 created_by，接收人取 personnel_no，决定人按状态推出（接收是接收人、取消是发起人）。
     */
    public List<FoodBusinessOperationRow> businessOperationsSelectServiceHandover(
            String e, String s, String no, boolean lock) {
        var items =
                db.queryForList(
                        "SELECT handover_no,target_type,target_no,created_by,personnel_no,handover_reason,source_hash,"
                                + "status,version,created_at FROM ind_assignment WHERE tenant_no='MAIN' AND entity_no=?"
                                + " AND store_no=? AND handover_no=? AND deleted=0 ORDER BY target_type,target_no"
                                + (lock ? " FOR UPDATE" : ""),
                        e,
                        s,
                        no);
        return items.isEmpty() ? List.of() : List.of(handoverOf(rows(items)));
    }

    private static FoodBusinessOperationRow handoverOf(List<FoodBusinessOperationRow> items) {
        var first = items.getFirst();
        var statuses = items.stream().map(r -> r.text("status")).toList();
        String status =
                statuses.contains("PENDING")
                        ? "PROPOSED"
                        : statuses.contains("CANCELLED") ? "CANCELLED" : "ACCEPTED";
        String from = first.text("created_by");
        String receiver = first.text("personnel_no");
        var row = new java.util.LinkedHashMap<String, Object>();
        row.put("handover_no", first.text("handover_no"));
        row.put("from_actor", from);
        row.put("receiver_no", receiver);
        row.put("status", status);
        row.put("source_hash", handoverHash(items.stream().map(r -> r.text("source_hash")).toList()));
        row.put("reason", first.text("handover_reason"));
        row.put("decided_by", switch (status) {
            case "ACCEPTED" -> receiver;
            case "CANCELLED" -> from;
            default -> null;
        });
        row.put("version", items.stream().mapToLong(r -> r.number("version")).min().orElseThrow());
        row.put("created_at", first.get("created_at"));
        return new FoodBusinessOperationRow(row);
    }

    /** 出品单的下达防重键：接单建单与定时出品同一个键，同一批次只会有一张制作单。 */
    public static String kitchenDispatchKey(String entity, String store, String batch) {
        return FoodCommandFingerprint.hash("kitchen-dispatch:v1", entity, store, batch);
    }

    /**
     * 定时出品登记为出品单的 {@code SCHEDULED} 状态（TDD §2.5 合并 ⑤，原 {@code fnb_scheduled_dispatch}）。
     * 按下达防重键可重复：已登记或已下达的批次不再建。
     */
    public int createScheduledDispatch(
            String entity, String store, String batch, LocalDateTime dispatchAt, String actor) {
        return db.update(
                "INSERT INTO fnb_kitchen_ticket (tenant_no,entity_no,store_no,ticket_no,batch_no,dispatch_key,kind,"
                        + "status,dispatch_at,next_attempt_at,dispatch_attempts,created_at,updated_at,created_by,"
                        + "updated_by,version,deleted) VALUES ('MAIN',?,?,?,?,?,'MAKE','SCHEDULED',?,?,0,"
                        + "UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),?,?,0,0) ON DUPLICATE KEY UPDATE batch_no=VALUES(batch_no)",
                entity,
                store,
                BizKey.next("FKIT"),
                batch,
                kitchenDispatchKey(entity, store, batch),
                dispatchAt,
                dispatchAt,
                actor,
                actor);
    }

    /** 到点待下达的定时出品：{@code next_attempt_at} 非空即待下达（下达成功后清空）。 */
    public List<FoodBusinessOperationRow> dueScheduledDispatch(LocalDateTime now, int limit) {
        return rows(
                db.queryForList(
                        "SELECT entity_no,store_no,batch_no,ticket_no,next_attempt_at FROM fnb_kitchen_ticket"
                                + " WHERE next_attempt_at<=? AND dispatch_at<=? AND kind='MAKE' AND deleted=0"
                                + " ORDER BY next_attempt_at,id LIMIT ?",
                        now,
                        now,
                        limit));
    }

    /**
     * 认领：以读到的 {@code next_attempt_at} 做比较交换，推到租约到期时刻。不加出品单版本号，
     * 认领期间厨房动作改出品单不受影响。
     */
    public int claimScheduledDispatch(
            String entity, String ticket, LocalDateTime seen, LocalDateTime lease) {
        return db.update(
                "UPDATE fnb_kitchen_ticket SET next_attempt_at=?,"
                        + "dispatch_attempts=COALESCE(dispatch_attempts,0)+1,updated_at=UTC_TIMESTAMP(3),"
                        + "updated_by='SYSTEM' WHERE entity_no=? AND ticket_no=? AND next_attempt_at=?",
                lease,
                entity,
                ticket,
                seen);
    }

    /** 下达完成：清空待下达标记；出品单状态已由建单改为排队。 */
    public int completeScheduledDispatch(String entity, String ticket, LocalDateTime lease) {
        return db.update(
                "UPDATE fnb_kitchen_ticket SET next_attempt_at=NULL,last_error=NULL,updated_at=UTC_TIMESTAMP(3),"
                        + "updated_by='SYSTEM' WHERE entity_no=? AND ticket_no=? AND next_attempt_at=?",
                entity,
                ticket,
                lease);
    }

    /** 原批次已取消：还没下达的定时出品单直接完结。 */
    public int cancelScheduledDispatch(String entity, String ticket, LocalDateTime lease) {
        return db.update(
                "UPDATE fnb_kitchen_ticket SET status='CLOSED',next_attempt_at=NULL,version=version+1,"
                        + "updated_at=UTC_TIMESTAMP(3),updated_by='SYSTEM' WHERE entity_no=? AND ticket_no=?"
                        + " AND status='SCHEDULED' AND next_attempt_at=?",
                entity,
                ticket,
                lease);
    }

    /** 下达失败：记原因，到点重试。 */
    public int retryScheduledDispatch(
            String entity, String ticket, LocalDateTime lease, String error, LocalDateTime next) {
        return db.update(
                "UPDATE fnb_kitchen_ticket SET last_error=?,next_attempt_at=?,updated_at=UTC_TIMESTAMP(3),"
                        + "updated_by='SYSTEM' WHERE entity_no=? AND ticket_no=? AND next_attempt_at=?",
                error,
                next,
                entity,
                ticket,
                lease);
    }

    /** 营业日内发起的交接（日结看有没有未决的），列为交接单号、状态、来源摘要、版本。 */
    public List<FoodBusinessOperationRow> closePendingHandovers(
            String entity, String store, LocalDateTime from, LocalDateTime until) {
        var groups = new java.util.TreeMap<String, List<FoodBusinessOperationRow>>();
        for (var row :
                rows(
                        db.queryForList(
                                "SELECT handover_no,target_type,target_no,created_by,personnel_no,handover_reason,"
                                        + "source_hash,status,version,created_at FROM ind_assignment WHERE"
                                        + " tenant_no='MAIN' AND entity_no=? AND store_no=? AND handover_no IS NOT NULL"
                                        + " AND created_at>=? AND created_at<? AND deleted=0 ORDER BY"
                                        + " handover_no,target_type,target_no",
                                entity,
                                store,
                                from,
                                until)))
            groups.computeIfAbsent(row.text("handover_no"), k -> new java.util.ArrayList<>()).add(row);
        return groups.values().stream()
                .map(FoodIndustryOperationsMapper::handoverOf)
                .map(h -> {
                    // 有序：日结摘要按 JSON 计算，列序必须稳定。
                    var row = new java.util.LinkedHashMap<String, Object>();
                    for (String column : List.of("handover_no", "status", "source_hash", "version"))
                        row.put(column, h.get(column));
                    return new FoodBusinessOperationRow(row);
                })
                .toList();
    }

    /**
     * 这些到店单上的放题类人头权益数（堂食权益已并入 fnb_service_entitlement，TDD §2.5）。
     * {@code activeOnly} 为真时只数生效中的。到店单号由交易库按账单取。
     */
    public long buffetEntitlements(String e, java.util.Collection<String> forms, boolean activeOnly) {
        if (forms.isEmpty()) return 0;
        String marks = String.join(",", java.util.Collections.nCopies(forms.size(), "?"));
        var args = new java.util.ArrayList<Object>();
        args.add(e);
        args.addAll(forms);
        Long count =
                db.queryForObject(
                        "SELECT COUNT(*) FROM fnb_service_entitlement WHERE entity_no=? AND"
                                + " entitlement_kind='BUFFET' AND form_no IN (" + marks + ")"
                                + (activeOnly ? " AND status='ACTIVE'" : ""),
                        Long.class,
                        args.toArray());
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
                        "SELECT policy_no AS config_no,definition_snapshot,snapshot_hash AS content_hash,"
                                + "status FROM ind_config_revision WHERE tenant_no='MAIN' AND entity_no=? AND"
                                + " policy_version_no=? AND config_kind='MENU' AND status IN"
                                + " ('EFFECTIVE','SCHEDULED')",
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

    /** 仍有在用占台的账单（带门店）：清台补做扫描用；在用占台的数量受桌台数限制。 */
    public List<FoodBusinessOperationRow> activeOccupancyChecks(int limit) {
        return rows(
                db.queryForList(
                        "SELECT DISTINCT o.entity_no,t.store_no,o.check_no FROM fnb_table_occupancy o"
                            + " JOIN fnb_table t ON t.entity_no=o.entity_no AND t.table_no=o.table_no"
                            + " WHERE o.ended_at IS NULL AND o.deleted=0 ORDER BY o.check_no LIMIT ?",
                        limit));
    }

    /** 早于截止时间仍是预占、没绑批次的容量占用：接单后补做绑定用。 */
    public List<FoodBusinessOperationRow> reservedUnboundClaims(LocalDateTime before, int limit) {
        return rows(
                db.queryForList(
                        "SELECT entity_no,store_no,source_no,claim_no,slot_no,version FROM fnb_capacity_claim"
                            + " WHERE status='RESERVED' AND batch_no IS NULL AND created_at<? ORDER BY id LIMIT ?",
                        before,
                        limit));
    }
}
