package ai.neargo.shop.food.mapper;

import ai.neargo.shop.food.entity.FoodBusinessOperationRow;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import javax.sql.DataSource;

/** Explicit primary mapper for append-only evidence and serialized draft replay progress. */
public final class OfflineDraftMapper {
    private final JdbcTemplate db;

    public OfflineDraftMapper(DataSource source) {
        db = new JdbcTemplate(source);
    }

    private List<FoodBusinessOperationRow> rows(String query, Object... args) {
        return db.queryForList(query, args).stream().map(FoodBusinessOperationRow::new).toList();
    }

    public FoodBusinessOperationRow one(
            String table, String entity, String store, String column, Object value, boolean lock) {
        if (!java.util.Set.of("signer", "challenge", "writer", "draft").contains(table)
                || !java.util.Set.of("key_no", "challenge_no", "store_no", "draft_no")
                        .contains(column))
            throw new IllegalArgumentException("Unsupported offline mapper identity");
        var rows =
                rows(
                        "SELECT * FROM fnb_offline_"
                                + table
                                + " WHERE entity_no=? AND store_no=? AND "
                                + column
                                + "=?"
                                + (lock ? " FOR UPDATE" : ""),
                        entity,
                        store,
                        value);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public void writer(String e, String s, Instant now) {
        update(
                "INSERT IGNORE INTO"
                    + " fnb_offline_writer(entity_no,store_no,created_at,updated_at,created_by,updated_by)"
                    + " VALUES(?,?,?,?,'SYSTEM','SYSTEM')",
                e,
                s,
                time(now),
                time(now));
    }

    private int update(String sql, Object... args) {
        return db.update(sql, args);
    }

    public static Timestamp time(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    public FoodBusinessOperationRow signer(String e, String s, String d, long g) {
        var r =
                rows(
                        "SELECT * FROM fnb_offline_signer WHERE entity_no=? AND store_no=? AND"
                                + " device_no=? AND device_generation=?",
                        e,
                        s,
                        d,
                        g);
        return r.isEmpty() ? null : r.getFirst();
    }

    public FoodBusinessOperationRow audit(String e, String s, String d, String kind, String key) {
        var r =
                rows(
                        "SELECT * FROM fnb_offline_audit WHERE entity_no=? AND store_no=? AND"
                                + " device_no=? AND operation_kind=? AND request_key=?",
                        e,
                        s,
                        d,
                        kind,
                        key);
        return r.isEmpty() ? null : r.getFirst();
    }

    public FoodBusinessOperationRow sequence(String e, String s, String lease, long seq) {
        var r =
                rows(
                        "SELECT * FROM fnb_offline_draft WHERE entity_no=? AND store_no=? AND"
                                + " lease_no=? AND sequence_no=?",
                        e,
                        s,
                        lease,
                        seq);
        return r.isEmpty() ? null : r.getFirst();
    }

    public long capturedThrough(String e, String s, String lease) {
        return db.queryForObject(
                "SELECT COALESCE(MAX(sequence_no),0) FROM fnb_offline_draft WHERE entity_no=? AND"
                        + " store_no=? AND lease_no=?",
                Long.class,
                e,
                s,
                lease);
    }

    public void insert(String table, java.util.Map<String, Object> values) {
        if (!java.util.Set.of("signer", "challenge", "draft", "line", "audit").contains(table))
            throw new IllegalArgumentException("Invalid offline table");
        var names = new java.util.ArrayList<>(values.keySet());
        if (names.stream().anyMatch(n -> !n.matches("[a-z_]+")))
            throw new IllegalArgumentException("Invalid offline column");
        update(
                "INSERT INTO fnb_offline_"
                        + table
                        + "("
                        + String.join(",", names)
                        + ") VALUES("
                        + String.join(",", java.util.Collections.nCopies(names.size(), "?"))
                        + ")",
                names.stream().map(values::get).toArray());
    }

    public void completeChallenge(String e, String s, String no, String key) {
        update(
                "UPDATE fnb_offline_challenge SET key_no=?,version=version+1 WHERE entity_no=? AND"
                        + " store_no=? AND challenge_no=?",
                key,
                e,
                s,
                no);
    }

    public void lease(
            String e,
            String s,
            String device,
            long generation,
            String key,
            String no,
            long fence,
            Instant expires,
            Instant now) {
        update(
                "UPDATE fnb_offline_writer SET"
                    + " device_no=?,device_generation=?,key_no=?,lease_no=?,fence=?,expires_at=?,accepted_through=0,version=version+1,updated_at=?"
                    + " WHERE entity_no=? AND store_no=?",
                device,
                generation,
                key,
                no,
                fence,
                time(expires),
                time(now),
                e,
                s);
    }

    public void acknowledge(String e, String s, long seq) {
        update(
                "UPDATE fnb_offline_writer SET accepted_through=?,version=version+1 WHERE"
                        + " entity_no=? AND store_no=?",
                seq,
                e,
                s);
    }

    public void begin(String e, String s, String no, String session) {
        update(
                "UPDATE fnb_offline_draft SET session_no=?,status='REPLAYING',version=version+1"
                        + " WHERE entity_no=? AND store_no=? AND draft_no=?",
                session,
                e,
                s,
                no);
    }

    public void cart(String e, String s, String no, String cart) {
        update(
                "UPDATE fnb_offline_draft SET cart_no=?,version=version+1 WHERE entity_no=? AND"
                        + " store_no=? AND draft_no=?",
                cart,
                e,
                s,
                no);
    }

    public void progress(String e, String s, String no, int count) {
        update(
                "UPDATE fnb_offline_draft SET applied_lines=?,version=version+1 WHERE entity_no=?"
                        + " AND store_no=? AND draft_no=?",
                count,
                e,
                s,
                no);
    }

    public void finish(
            String e, String s, String no, String status, String reason, String receipt) {
        update(
                "UPDATE fnb_offline_draft SET status=?,reason=?,receipt_no=?,version=version+1"
                        + " WHERE entity_no=? AND store_no=? AND draft_no=?",
                status,
                reason,
                receipt,
                e,
                s,
                no);
    }
}
