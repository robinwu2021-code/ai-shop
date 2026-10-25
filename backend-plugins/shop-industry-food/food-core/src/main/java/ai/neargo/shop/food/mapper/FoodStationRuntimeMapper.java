package ai.neargo.shop.food.mapper;

import ai.neargo.shop.food.dto.FoodStationRuntimeContracts.ControlReq;
import ai.neargo.shop.food.dto.FoodStationRuntimeContracts.StateVO;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Keeps the FoodStationRuntimeMapper business boundary scoped to original restaurant facts and
 * lifecycle authority.
 */
public class FoodStationRuntimeMapper {
    private final JdbcTemplate jdbc;

    public FoodStationRuntimeMapper(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean stationExists(String e, String s, String no) {
        return jdbc.queryForObject(
                        "SELECT COUNT(*) FROM fnb_station WHERE entity_no=? AND store_no=? AND"
                                + " station_no=? AND deleted=0",
                        Long.class,
                        e,
                        s,
                        no)
                > 0;
    }

    public StateVO state(String e, String s, String station, boolean lock) {
        var rows =
                jdbc.query(
                        "SELECT station_no,status,version FROM fnb_station_runtime WHERE"
                                + " entity_no=? AND store_no=? AND station_no=?"
                                + (lock ? " FOR UPDATE" : ""),
                        (r, n) -> new StateVO(r.getString(1), r.getString(2), r.getLong(3)),
                        e,
                        s,
                        station);
        return rows.isEmpty() ? new StateVO(station, "RUNNING", 0) : rows.getFirst();
    }

    public void initialize(String e, String s, String station) {
        jdbc.update(
                "INSERT INTO fnb_station_runtime(entity_no,store_no,station_no,status,version)"
                        + " VALUES(?,?,?,'RUNNING',0) ON DUPLICATE KEY UPDATE entity_no=entity_no",
                e,
                s,
                station);
    }

    public void change(String e, String s, String no, String status, long version) {
        jdbc.update(
                "UPDATE fnb_station_runtime SET status=?,version=? WHERE entity_no=? AND store_no=?"
                        + " AND station_no=?",
                status,
                version,
                e,
                s,
                no);
    }

    public Map<String, Object> command(String e, String s, String no, boolean lock) {
        var rows =
                jdbc.queryForList(
                        "SELECT * FROM fnb_kds_voice_command WHERE entity_no=? AND store_no=? AND"
                                + " command_no=?"
                                + (lock ? " FOR UPDATE" : ""),
                        e,
                        s,
                        no);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public Map<String, Object> original(String e, String s, String actor, String key) {
        var rows =
                jdbc.queryForList(
                        "SELECT * FROM fnb_kds_voice_command WHERE entity_no=? AND store_no=? AND"
                                + " requested_by=? AND request_key=?",
                        e,
                        s,
                        actor,
                        key);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public void insert(
            String e,
            String s,
            String station,
            String no,
            ControlReq req,
            String actor,
            String key,
            String hash,
            long version,
            Instant at) {
        jdbc.update(
                "INSERT INTO fnb_kds_voice_command(entity_no,store_no,station_no,command_no,action,"
                    + "status,source_version,reason,device_no,voice_text,requested_by,request_key,"
                    + "request_hash,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                e,
                s,
                station,
                no,
                req.action(),
                "CALL".equals(req.action()) ? "QUEUED" : "APPLIED",
                version,
                req.reason(),
                req.deviceNo(),
                req.voiceText(),
                actor,
                key,
                hash,
                Timestamp.from(at));
    }

    public List<Map<String, Object>> expired(String e, String s, String device, Instant now) {
        return jdbc.queryForList(
                "SELECT * FROM fnb_kds_voice_command WHERE entity_no=? AND store_no=? AND"
                        + " device_no=? AND status='SENT' AND lease_until<=? FOR UPDATE",
                e,
                s,
                device,
                Timestamp.from(now));
    }

    public boolean outstanding(String e, String s, String device) {
        return jdbc.queryForObject(
                        "SELECT COUNT(*) FROM fnb_kds_voice_command WHERE entity_no=? AND"
                                + " store_no=? AND device_no=? AND status='SENT'",
                        Long.class,
                        e,
                        s,
                        device)
                > 0;
    }

    public Map<String, Object> next(String e, String s, String device, List<String> stations) {
        var args = new ArrayList<Object>(List.of(e, s, device));
        args.addAll(stations);
        var rows =
                jdbc.queryForList(
                        "SELECT * FROM fnb_kds_voice_command WHERE entity_no=? AND store_no=? AND"
                                + " device_no=? AND status='QUEUED' AND station_no IN ("
                                + String.join(",", Collections.nCopies(stations.size(), "?"))
                                + ") ORDER BY created_at,command_no LIMIT 1 FOR UPDATE",
                        args.toArray());
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public void claim(String e, String s, String no, String actor, String token, Instant until) {
        jdbc.update(
                "UPDATE fnb_kds_voice_command SET"
                    + " status='SENT',claim_actor=?,lease_token=?,lease_until=? WHERE entity_no=?"
                    + " AND store_no=? AND command_no=?",
                actor,
                token,
                Timestamp.from(until),
                e,
                s,
                no);
    }

    public void receipt(String e, String s, String no, String status, String code) {
        jdbc.update(
                "UPDATE fnb_kds_voice_command SET status=?,response_code=? WHERE entity_no=? AND"
                        + " store_no=? AND command_no=?",
                status,
                code,
                e,
                s,
                no);
    }

    public static String text(Map<String, Object> row, String name) {
        Object result = row.get(name);
        if (result == null) {
            result = row.get(name.toUpperCase(Locale.ROOT));
        }
        return result == null ? null : result.toString();
    }

    public static long number(Map<String, Object> row, String name) {
        return Long.parseLong(text(row, name));
    }
}
