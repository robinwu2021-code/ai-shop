package ai.neargo.shop.food.mapper;

import ai.neargo.shop.food.entity.FoodBusinessOperationRow;

import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import javax.sql.DataSource;

/**
 * 人头权益、权益事件、宴会方案修订与到场确认：都在行业库（ADR-068），只走行业句柄。
 * 到店单、账单、购物车、点菜行在门店交易库，经 {@link ServiceFormMapper} 读写。
 */
public final class ServiceEntitlementMapper {
    private final JdbcTemplate db;

    public ServiceEntitlementMapper(DataSource source) {
        db = new JdbcTemplate(source);
    }

    private List<FoodBusinessOperationRow> rows(String sql, Object... values) {
        return db.queryForList(sql, values).stream().map(FoodBusinessOperationRow::new).toList();
    }

    public List<FoodBusinessOperationRow> entitlement(String e, String s, String no, boolean lock) {
        return rows(
                "SELECT * FROM fnb_service_entitlement WHERE entity_no=? AND store_no=? AND"
                        + " entitlement_no=?"
                        + (lock ? " FOR UPDATE" : ""),
                e,
                s,
                no);
    }

    public List<FoodBusinessOperationRow> entitlements(String e, String s, String form) {
        return rows(
                "SELECT * FROM fnb_service_entitlement WHERE entity_no=? AND store_no=? AND"
                        + " form_no=? ORDER BY entitlement_no",
                e,
                s,
                form);
    }

    public void grant(
            String e,
            String s,
            String no,
            String form,
            String attendee,
            String goods,
            String sku,
            String item,
            String unit,
            BigDecimal granted,
            LocalDateTime from,
            LocalDateTime until,
            String ownerSnapshot,
            String quote,
            String quoteHash,
            String sourceHash,
            String actor) {
        db.update(
                "INSERT INTO"
                    + " fnb_service_entitlement(entity_no,store_no,entitlement_no,form_no,attendee_no,goods_no,sku_no,"
                    + "menu_item_no,unit,granted_qty,consumed_qty,valid_from,valid_until,owner_snapshot,quote_no,quote_hash,source_hash,created_at,updated_at,created_by,updated_by)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?,0,?,?,?,?,?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),?,?)",
                e,
                s,
                no,
                form,
                attendee,
                goods,
                sku,
                item,
                unit,
                granted,
                from,
                until,
                ownerSnapshot,
                quote,
                quoteHash,
                sourceHash,
                actor,
                actor);
    }

    public int consume(
            String e, String s, String no, long version, BigDecimal quantity, String actor) {
        return db.update(
                "UPDATE fnb_service_entitlement SET"
                    + " consumed_qty=consumed_qty+?,version=version+1,updated_at=UTC_TIMESTAMP(3),updated_by=?"
                    + " WHERE entity_no=? AND store_no=? AND entitlement_no=? AND version=? AND"
                    + " consumed_qty+?<=granted_qty",
                quantity,
                actor,
                e,
                s,
                no,
                version,
                quantity);
    }

    public List<FoodBusinessOperationRow> eventByLine(
            String e, String s, String batch, String line) {
        return rows(
                "SELECT * FROM fnb_service_entitlement_event WHERE entity_no=? AND store_no=? AND"
                        + " event_kind='CONSUME' AND batch_no=? AND food_line_no=?",
                e,
                s,
                batch,
                line);
    }

    /** 按事件号查回：延长等由请求键派生事件号的命令，用它判断这次请求是否已经写过。 */
    public List<FoodBusinessOperationRow> eventByNo(String e, String s, String no) {
        return rows(
                "SELECT * FROM fnb_service_entitlement_event WHERE entity_no=? AND store_no=? AND"
                        + " event_no=?",
                e,
                s,
                no);
    }

    /** 按确认号查回：到场确认号由请求键派生。 */
    public List<FoodBusinessOperationRow> confirmationByNo(String e, String s, String no) {
        return rows(
                "SELECT * FROM fnb_banquet_attendance_confirmation WHERE entity_no=? AND"
                        + " store_no=? AND confirmation_no=?",
                e,
                s,
                no);
    }

    public void event(
            String e,
            String s,
            String no,
            String form,
            String entitlement,
            String kind,
            BigDecimal quantity,
            String batch,
            String line,
            String quote,
            String quoteHash,
            String snapshot,
            String actor,
            String reason) {
        db.update(
                "INSERT INTO"
                    + " fnb_service_entitlement_event(entity_no,store_no,event_no,form_no,entitlement_no,event_kind,"
                    + "quantity,batch_no,food_line_no,quote_no,quote_hash,evidence_snapshot,actor_no,reason,created_at)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP(3))",
                e,
                s,
                no,
                form,
                entitlement,
                kind,
                quantity,
                batch,
                line,
                quote,
                quoteHash,
                snapshot,
                actor,
                reason);
    }

    public void extendEntitlements(
            String e, String s, String form, LocalDateTime oldUntil, LocalDateTime until) {
        db.update(
                "UPDATE fnb_service_entitlement SET"
                        + " valid_until=?,version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE"
                        + " entity_no=? AND store_no=? AND form_no=? AND valid_until=?",
                until,
                e,
                s,
                form,
                oldUntil);
    }

    public long nextPlanRevision(String e, String s, String form) {
        Long current =
                db.queryForObject(
                        "SELECT COALESCE(MAX(revision),0) FROM fnb_banquet_plan_revision WHERE"
                                + " entity_no=? AND store_no=? AND form_no=?",
                        Long.class,
                        e,
                        s,
                        form);
        return Math.addExact(current, 1);
    }

    public List<FoodBusinessOperationRow> plan(String e, String s, String form, String no) {
        return rows(
                "SELECT * FROM fnb_banquet_plan_revision WHERE entity_no=? AND store_no=? AND"
                        + " form_no=? AND plan_no=?",
                e,
                s,
                form,
                no);
    }

    public void plan(
            String e,
            String s,
            String form,
            String no,
            long revision,
            String currency,
            long budget,
            int persons,
            String snapshot,
            String hash,
            String actor,
            String reason) {
        db.update(
                "INSERT INTO"
                    + " fnb_banquet_plan_revision(entity_no,store_no,form_no,plan_no,revision,currency,intended_budget_minor,planned_persons,plan_snapshot,content_hash,actor_no,reason,created_at)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP(3))",
                e,
                s,
                form,
                no,
                revision,
                currency,
                budget,
                persons,
                snapshot,
                hash,
                actor,
                reason);
    }

    public void confirmation(
            String e,
            String s,
            String form,
            String no,
            String plan,
            long formVersion,
            int actual,
            String roster,
            String rosterHash,
            String quote,
            String quoteHash,
            String snapshot,
            String actor,
            String reason) {
        db.update(
                "INSERT INTO"
                    + " fnb_banquet_attendance_confirmation(entity_no,store_no,form_no,confirmation_no,plan_no,form_version,"
                    + "actual_persons,roster_snapshot,roster_hash,quote_no,quote_hash,quote_snapshot,actor_no,reason,created_at)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP(3))",
                e,
                s,
                form,
                no,
                plan,
                formVersion,
                actual,
                roster,
                rosterHash,
                quote,
                quoteHash,
                snapshot,
                actor,
                reason);
    }
}
