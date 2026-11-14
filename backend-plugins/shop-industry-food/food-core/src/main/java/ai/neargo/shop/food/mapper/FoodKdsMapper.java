package ai.neargo.shop.food.mapper;

import ai.neargo.shop.food.entity.FnbKdsEvent;
import ai.neargo.shop.food.entity.FnbKdsSubscription;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * JDBC mapper uses explicit tenant/business scope in every statement; no implicit security-thread
 * scope.
 */
public class FoodKdsMapper {
    private final JdbcTemplate jdbc;

    public FoodKdsMapper(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<FnbKdsSubscription> SUB =
            (r, n) ->
                    new FnbKdsSubscription(
                            r.getString("subscription_no"),
                            r.getString("entity_no"),
                            r.getString("store_no"),
                            r.getString("device_no"),
                            r.getString("actor_no"),
                            r.getString("stations"),
                            r.getLong("device_version"),
                            r.getTimestamp("expires_at").toInstant(),
                            r.getString("status"),
                            r.getLong("version"),
                            r.getString("requested_by"),
                            r.getString("request_key"),
                            r.getString("request_hash"),
                            r.getString("revoke_key"),
                            r.getString("revoke_reason"),
                            r.getString("revoked_by"));
    private static final RowMapper<FnbKdsEvent> EVENT =
            (r, n) ->
                    new FnbKdsEvent(
                            r.getString("entity_no"),
                            r.getString("store_no"),
                            r.getLong("sequence_no"),
                            r.getString("event_no"),
                            r.getString("station_no"),
                            r.getString("event_type"),
                            r.getString("source_no"),
                            r.getLong("source_version"),
                            r.getString("payload"),
                            r.getTimestamp("occurred_at").toInstant());

    public void ensureCursor(String entity, String store) {
        jdbc.update(
                "INSERT INTO fnb_kds_cursor(entity_no,store_no,sequence_no) VALUES(?,?,0) ON"
                        + " DUPLICATE KEY UPDATE entity_no=entity_no",
                entity,
                store);
    }

    public long head(String entity, String store, boolean lock) {
        var rows =
                jdbc.query(
                        "SELECT sequence_no FROM fnb_kds_cursor WHERE entity_no=? AND store_no=?"
                                + (lock ? " FOR UPDATE" : ""),
                        (r, n) -> r.getLong(1),
                        entity,
                        store);
        return rows.isEmpty() ? 0 : rows.getFirst();
    }

    public void advance(String entity, String store, long sequence) {
        jdbc.update(
                "UPDATE fnb_kds_cursor SET sequence_no=? WHERE entity_no=? AND store_no=?",
                sequence,
                entity,
                store);
    }

    public FnbKdsSubscription subscription(String entity, String store, String no, boolean lock) {
        return first(
                jdbc.query(
                        "SELECT * FROM fnb_kds_subscription WHERE entity_no=? AND store_no=? AND"
                                + " subscription_no=?"
                                + (lock ? " FOR UPDATE" : ""),
                        SUB,
                        entity,
                        store,
                        no));
    }

    public FnbKdsSubscription originalGrant(String entity, String store, String actor, String key) {
        return first(
                jdbc.query(
                        "SELECT * FROM fnb_kds_subscription WHERE entity_no=? AND store_no=? AND"
                                + " requested_by=? AND request_key=?",
                        SUB,
                        entity,
                        store,
                        actor,
                        key));
    }

    public void insert(FnbKdsSubscription row) {
        jdbc.update(
                "INSERT INTO"
                        + " fnb_kds_subscription(subscription_no,entity_no,store_no,device_no,"
                        + "actor_no,stations,device_version,expires_at,status,version,requested_by,"
                        + "request_key,request_hash)"
                        + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                row.subscriptionNo(),
                row.entityNo(),
                row.storeNo(),
                row.deviceNo(),
                row.actorNo(),
                row.stations(),
                row.deviceVersion(),
                Timestamp.from(row.expiresAt()),
                row.status(),
                row.version(),
                row.requestedBy(),
                row.requestKey(),
                row.requestHash());
    }

    public void revoke(
            String entity,
            String store,
            String no,
            long version,
            String actor,
            String reason,
            String key) {
        int changed =
                jdbc.update(
                        "UPDATE fnb_kds_subscription SET"
                            + " status='REVOKED',version=version+1,revoked_by=?,revoke_reason=?,revoke_key=?"
                            + " WHERE entity_no=? AND store_no=? AND subscription_no=? AND"
                            + " version=?",
                        actor,
                        reason,
                        key,
                        entity,
                        store,
                        no,
                        version);
        if (changed != 1) {
            throw new OptimisticLockingFailureException("KDS grant changed");
        }
    }

    public FnbKdsEvent originalEvent(String entity, String store, String no) {
        return first(
                jdbc.query(
                        "SELECT * FROM fnb_kds_event WHERE entity_no=? AND store_no=? AND"
                                + " event_no=?",
                        EVENT,
                        entity,
                        store,
                        no));
    }

    public void insert(FnbKdsEvent row) {
        jdbc.update(
                "INSERT INTO"
                        + " fnb_kds_event(entity_no,store_no,sequence_no,event_no,station_no,"
                        + "event_type,source_no,source_version,payload,occurred_at)"
                        + " VALUES(?,?,?,?,?,?,?,?,?,?)",
                row.entityNo(),
                row.storeNo(),
                row.sequenceNo(),
                row.eventNo(),
                row.stationNo(),
                row.eventType(),
                row.sourceNo(),
                row.sourceVersion(),
                row.payload(),
                Timestamp.from(row.occurredAt()));
    }

    public List<FnbKdsEvent> replay(
            String entity, String store, List<String> stations, long after, long head, int limit) {
        var params = new ArrayList<Object>(List.of(entity, store, after, head));
        params.addAll(stations);
        params.add(limit);
        return jdbc.query(
                "SELECT * FROM fnb_kds_event WHERE entity_no=? AND store_no=? AND sequence_no>? AND"
                        + " sequence_no<=? AND (station_no IS NULL OR station_no IN ("
                        + String.join(",", Collections.nCopies(stations.size(), "?"))
                        + ")) ORDER BY sequence_no LIMIT ?",
                EVENT,
                params.toArray());
    }

    private static <T> T first(List<T> rows) {
        return rows.isEmpty() ? null : rows.getFirst();
    }
}
