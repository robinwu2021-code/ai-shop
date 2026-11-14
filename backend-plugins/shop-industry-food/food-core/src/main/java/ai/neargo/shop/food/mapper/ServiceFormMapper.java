package ai.neargo.shop.food.mapper;

import ai.neargo.shop.food.entity.FoodBusinessOperationRow;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

public class ServiceFormMapper {
    private final JdbcTemplate db;

    public ServiceFormMapper(DataSource source) {
        db = new JdbcTemplate(source);
    }

    private List<FoodBusinessOperationRow> rows(List<Map<String, Object>> values) {
        return values.stream().map(FoodBusinessOperationRow::new).toList();
    }

    public int registerOrganization(
            String e,
            String store,
            String no,
            String name,
            String contact,
            String external,
            String actor) {
        return db.update(
                "INSERT INTO"
                    + " fnb_business_organization(entity_no,store_no,organization_no,name,contact_ref,external_ref,status,created_at,updated_at,created_by,updated_by)"
                    + " VALUES (?,?,?,?,?,?,'ACTIVE',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),?,?)",
                e,
                store,
                no,
                name,
                contact,
                external,
                actor,
                actor);
    }

    public List<FoodBusinessOperationRow> organization(String e, String store, String no) {
        return rows(
                db.queryForList(
                        "SELECT * FROM fnb_business_organization WHERE entity_no=? AND store_no=?"
                                + " AND organization_no=? AND status='ACTIVE' AND deleted=0",
                        e,
                        store,
                        no));
    }

    public List<FoodBusinessOperationRow> menu(String e, String revision) {
        return rows(
                db.queryForList(
                        "SELECT config_no,definition_snapshot,content_hash,status FROM"
                                + " fnb_config_revision WHERE entity_no=? AND revision_no=? AND"
                                + " config_kind='MENU' AND status IN ('EFFECTIVE','SCHEDULED')",
                        e,
                        revision));
    }

    public List<FoodBusinessOperationRow> reservation(
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

    public List<FoodBusinessOperationRow> check(String e, String store, String no, boolean lock) {
        return rows(
                db.queryForList(
                        "SELECT * FROM fnb_check WHERE entity_no=? AND store_no=? AND check_no=?"
                                + " AND deleted=0"
                                + (lock ? " FOR UPDATE" : ""),
                        e,
                        store,
                        no));
    }

    public List<FoodBusinessOperationRow> form(String e, String store, String no, boolean lock) {
        return rows(
                db.queryForList(
                        "SELECT * FROM fnb_service_form WHERE entity_no=? AND store_no=? AND"
                                + " form_no=? AND deleted=0"
                                + (lock ? " FOR UPDATE" : ""),
                        e,
                        store,
                        no));
    }

    public int book(
            String e,
            String store,
            String no,
            String kind,
            String reservation,
            String slot,
            String menu,
            String revision,
            String snapshot,
            int persons,
            LocalDateTime from,
            LocalDateTime until,
            String org,
            String contact,
            int max,
            String actor) {
        return db.update(
                "INSERT INTO"
                    + " fnb_service_form(entity_no,store_no,form_no,kind,status,reservation_no,slot_no,menu_no,menu_revision_no,menu_snapshot,guaranteed_persons,starts_at,ends_at,organization_no,contact_ref,max_entries_per_person,created_at,updated_at,created_by,updated_by)"
                    + " VALUES"
                    + " (?,?,?,?,'BOOKED',?,?,?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),?,?)",
                e,
                store,
                no,
                kind,
                reservation,
                slot,
                menu,
                revision,
                snapshot,
                persons,
                from,
                until,
                org,
                contact,
                max,
                actor,
                actor);
    }

    public int revise(
            String e,
            String store,
            String no,
            long version,
            int persons,
            String menu,
            String revision,
            String snapshot,
            String actor) {
        return db.update(
                "UPDATE fnb_service_form SET"
                    + " guaranteed_persons=?,menu_no=?,menu_revision_no=?,menu_snapshot=?,version=version+1,updated_at=UTC_TIMESTAMP(3),updated_by=?"
                    + " WHERE entity_no=? AND store_no=? AND form_no=? AND version=? AND"
                    + " status='BOOKED'",
                persons,
                menu,
                revision,
                snapshot,
                actor,
                e,
                store,
                no,
                version);
    }

    public int enter(
            String e,
            String store,
            String no,
            long version,
            String check,
            int persons,
            String actor) {
        return db.update(
                "UPDATE fnb_service_form SET"
                    + " status='ACTIVE',check_no=?,active_check_no=?,actual_persons=?,entered_at=UTC_TIMESTAMP(3),version=version+1,updated_at=UTC_TIMESTAMP(3),updated_by=?"
                    + " WHERE entity_no=? AND store_no=? AND form_no=? AND version=? AND"
                    + " status='BOOKED'",
                check,
                check,
                persons,
                actor,
                e,
                store,
                no,
                version);
    }

    public int addAttendee(
            String e,
            String store,
            String no,
            String form,
            String guest,
            String seat,
            String personClass,
            String actor) {
        return db.update(
                "INSERT INTO"
                    + " fnb_service_attendee(entity_no,store_no,attendee_no,form_no,guest_ref,seat_no,person_class,status,entry_count,created_at,updated_at,created_by,updated_by)"
                    + " VALUES (?,?,?,?,?,?,?,'IN',1,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),?,?)",
                e,
                store,
                no,
                form,
                guest,
                seat,
                personClass,
                actor,
                actor);
    }

    public Long seatCount(String e, String check, String seat) {
        return db.queryForObject(
                "SELECT COUNT(*) FROM fnb_seat WHERE entity_no=? AND check_no=? AND seat_no=? AND"
                        + " deleted=0",
                Long.class,
                e,
                check,
                seat);
    }

    public List<FoodBusinessOperationRow> attendees(
            String e, String store, String form, boolean lock) {
        return rows(
                db.queryForList(
                        "SELECT * FROM fnb_service_attendee WHERE entity_no=? AND store_no=? AND"
                                + " form_no=? AND deleted=0 ORDER BY attendee_no"
                                + (lock ? " FOR UPDATE" : ""),
                        e,
                        store,
                        form));
    }

    public int attendeeTransition(
            String e,
            String store,
            String form,
            String no,
            long version,
            String state,
            boolean enter,
            String actor) {
        return db.update(
                "UPDATE fnb_service_attendee SET"
                    + " status=?,entry_count=entry_count+?,version=version+1,updated_at=UTC_TIMESTAMP(3),updated_by=?"
                    + " WHERE entity_no=? AND store_no=? AND form_no=? AND attendee_no=? AND"
                    + " version=?",
                state,
                enter ? 1 : 0,
                actor,
                e,
                store,
                form,
                no,
                version);
    }

    public int leave(String e, String store, String no, long version, String actor) {
        return db.update(
                "UPDATE fnb_service_form SET"
                    + " status='LEFT',active_check_no=NULL,left_at=UTC_TIMESTAMP(3),version=version+1,updated_at=UTC_TIMESTAMP(3),updated_by=?"
                    + " WHERE entity_no=? AND store_no=? AND form_no=? AND version=? AND"
                    + " status='ACTIVE'",
                actor,
                e,
                store,
                no,
                version);
    }

    public int closeAttendees(String e, String store, String no, String actor) {
        return db.update(
                "UPDATE fnb_service_attendee SET"
                        + " status='OUT',version=version+1,updated_at=UTC_TIMESTAMP(3),updated_by=?"
                        + " WHERE entity_no=? AND store_no=? AND form_no=? AND status='IN'",
                actor,
                e,
                store,
                no);
    }

    public int cancelIntent(
            String e,
            String store,
            String no,
            long version,
            String target,
            String reason,
            String actor) {
        return db.update(
                "UPDATE fnb_service_form SET"
                    + " status='CANCELLING',cancel_target=?,cancel_reason=?,version=version+1,updated_at=UTC_TIMESTAMP(3),updated_by=?"
                    + " WHERE entity_no=? AND store_no=? AND form_no=? AND version=? AND"
                    + " status='BOOKED'",
                target,
                reason,
                actor,
                e,
                store,
                no,
                version);
    }

    public int cancelled(
            String e, String store, String no, String target, String source, String actor) {
        return db.update(
                "UPDATE fnb_service_form SET"
                    + " status=?,source_transition=?,active_check_no=NULL,version=version+1,updated_at=UTC_TIMESTAMP(3),updated_by=?"
                    + " WHERE entity_no=? AND store_no=? AND form_no=? AND status IN"
                    + " ('BOOKED','CANCELLING')",
                target,
                source,
                actor,
                e,
                store,
                no);
    }

    public List<FoodBusinessOperationRow> reservationForms(
            String e, String store, String reservation) {
        return rows(
                db.queryForList(
                        "SELECT * FROM fnb_service_form WHERE entity_no=? AND store_no=? AND"
                                + " reservation_no=? AND status IN ('BOOKED','CANCELLING') ORDER BY"
                                + " form_no FOR UPDATE",
                        e,
                        store,
                        reservation));
    }

    public List<FoodBusinessOperationRow> batch(String e, String store, String batch) {
        return rows(
                db.queryForList(
                        "SELECT b.* FROM fnb_order_batch b WHERE b.entity_no=? AND b.store_no=? AND"
                            + " b.batch_no=? AND b.deleted=0 AND b.status='ACCEPTED' FOR UPDATE",
                        e,
                        store,
                        batch));
    }

    public List<FoodBusinessOperationRow> batchLineOwners(String e, String store, String batch) {
        return rows(
                db.queryForList(
                        "SELECT l.food_line_no,COALESCE(lm.check_no,bm.check_no,b.check_no) AS"
                            + " current_check_no FROM fnb_order_line l JOIN fnb_order_batch b ON"
                            + " b.entity_no=l.entity_no AND b.batch_no=l.batch_no LEFT JOIN"
                            + " fnb_check_line_membership lm ON lm.entity_no=l.entity_no AND"
                            + " lm.active_line_no=l.food_line_no LEFT JOIN"
                            + " fnb_check_batch_membership bm ON bm.entity_no=b.entity_no AND"
                            + " bm.batch_no=b.batch_no AND bm.detached_at IS NULL AND bm.deleted=0"
                            + " WHERE b.entity_no=? AND b.store_no=? AND b.batch_no=? AND"
                            + " l.deleted=0",
                        e,
                        store,
                        batch));
    }

    public int bindBatch(
            String e, String store, String no, String batch, String hash, String actor) {
        return db.update(
                "INSERT INTO"
                    + " fnb_service_form_batch(entity_no,store_no,form_no,batch_no,source_hash,created_at,updated_at,created_by,updated_by)"
                    + " VALUES (?,?,?,?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),?,?) ON DUPLICATE KEY"
                    + " UPDATE batch_no=VALUES(batch_no)",
                e,
                store,
                no,
                batch,
                hash,
                actor,
                actor);
    }

    public int advance(String e, String store, String no, long version, String actor) {
        return db.update(
                "UPDATE fnb_service_form SET"
                        + " version=version+1,updated_at=UTC_TIMESTAMP(3),updated_by=? WHERE"
                        + " entity_no=? AND store_no=? AND form_no=? AND version=?",
                actor,
                e,
                store,
                no,
                version);
    }

    public List<FoodBusinessOperationRow> batchBinding(String e, String store, String batch) {
        return rows(
                db.queryForList(
                        "SELECT form_no,source_hash FROM fnb_service_form_batch WHERE entity_no=?"
                                + " AND store_no=? AND batch_no=?",
                        e,
                        store,
                        batch));
    }

    public int advanceCheck(String e, String store, String check, long version) {
        return db.update(
                "UPDATE fnb_check SET version=version+1 WHERE entity_no=? AND store_no=? AND"
                        + " check_no=? AND version=? AND deleted=0",
                e,
                store,
                check,
                version);
    }
}
