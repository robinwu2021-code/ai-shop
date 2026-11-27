package ai.neargo.shop.food.mapper;

import ai.neargo.shop.food.entity.FoodBusinessOperationRow;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

/**
 * 餐饮业务操作里只碰行业库的那部分（ADR-068）：服务交接与责任、定时出品、门店策略、桌台、堂食权益。
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
}
