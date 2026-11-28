package ai.neargo.shop.food.mapper;

import ai.neargo.shop.food.entity.FnbKdsSubscription;
import ai.neargo.shop.food.entity.IndStoreDevice;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * 门店设备与厨显订阅（行业库，TDD-餐饮域迁移新库 §2.8 D-5）。每条语句都带主体与门店范围；新行显式写
 * {@code tenant_no='MAIN'}。
 */
public class FoodKdsMapper {
    private final JdbcTemplate jdbc;

    public FoodKdsMapper(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String DEVICE_COLUMNS =
            "device_no,entity_no,store_no,device_type,display_name,serial_no,status,version";
    private static final RowMapper<IndStoreDevice> DEVICE =
            (r, n) ->
                    new IndStoreDevice(
                            r.getString("device_no"),
                            r.getString("entity_no"),
                            r.getString("store_no"),
                            r.getString("device_type"),
                            r.getString("display_name"),
                            r.getString("serial_no"),
                            r.getString("status"),
                            r.getLong("version"));
    private static final RowMapper<FnbKdsSubscription> SUB =
            (r, n) ->
                    new FnbKdsSubscription(
                            r.getString("entity_no"),
                            r.getString("store_no"),
                            r.getString("device_no"),
                            r.getString("stations"),
                            r.getLong("version"));

    /** 本店在册（未删除）的一台设备。 */
    public IndStoreDevice device(String entity, String store, String no, boolean lock) {
        return first(
                jdbc.query(
                        "SELECT " + DEVICE_COLUMNS + " FROM ind_store_device WHERE entity_no=? AND store_no=?"
                                + " AND device_no=? AND deleted=0"
                                + (lock ? " FOR UPDATE" : ""),
                        DEVICE,
                        entity,
                        store,
                        no));
    }

    /** 按设备号查（不限门店）：登记重放时比对首次的请求。 */
    public IndStoreDevice deviceByNo(String no) {
        return first(
                jdbc.query(
                        "SELECT " + DEVICE_COLUMNS + " FROM ind_store_device WHERE device_no=? AND deleted=0",
                        DEVICE,
                        no));
    }

    public List<IndStoreDevice> devices(String entity, String store) {
        return jdbc.query(
                "SELECT " + DEVICE_COLUMNS + " FROM ind_store_device WHERE entity_no=? AND store_no=?"
                        + " AND deleted=0 ORDER BY device_type,device_no",
                DEVICE,
                entity,
                store);
    }

    public void insertDevice(IndStoreDevice row, String actor, Instant now) {
        var at = Timestamp.from(now);
        jdbc.update(
                "INSERT INTO ind_store_device(tenant_no,entity_no,store_no,device_no,device_type,display_name,"
                        + "serial_no,status,generation,deleted,version,created_at,created_by,updated_at,updated_by)"
                        + " VALUES('MAIN',?,?,?,?,?,?,?,0,0,0,?,?,?,?)",
                row.entityNo(),
                row.storeNo(),
                row.deviceNo(),
                row.deviceType(),
                row.displayName(),
                row.serialNo(),
                row.status(),
                at,
                actor,
                at,
                actor);
    }

    /** 按读到的版本改状态；返回改到的行数。 */
    public int changeStatus(
            String entity, String store, String no, String status, long version, String actor, Instant now) {
        return jdbc.update(
                "UPDATE ind_store_device SET status=?,version=version+1,updated_at=?,updated_by=? WHERE entity_no=?"
                        + " AND store_no=? AND device_no=? AND deleted=0 AND version=?",
                status,
                Timestamp.from(now),
                actor,
                entity,
                store,
                no,
                version);
    }

    public FnbKdsSubscription subscription(String entity, String store, String device, boolean lock) {
        return first(
                jdbc.query(
                        "SELECT entity_no,store_no,device_no,stations,version FROM fnb_kds_subscription"
                                + " WHERE entity_no=? AND store_no=? AND device_no=?"
                                + (lock ? " FOR UPDATE" : ""),
                        SUB,
                        entity,
                        store,
                        device));
    }

    public List<FnbKdsSubscription> subscriptions(String entity, String store) {
        return jdbc.query(
                "SELECT entity_no,store_no,device_no,stations,version FROM fnb_kds_subscription"
                        + " WHERE entity_no=? AND store_no=?",
                SUB,
                entity,
                store);
    }

    public void insertSubscription(
            String entity, String store, String device, String stations, String actor, Instant now) {
        var at = Timestamp.from(now);
        jdbc.update(
                "INSERT INTO fnb_kds_subscription(tenant_no,entity_no,store_no,device_no,stations,version,"
                        + "created_at,created_by,updated_at,updated_by) VALUES('MAIN',?,?,?,?,0,?,?,?,?)",
                entity,
                store,
                device,
                stations,
                at,
                actor,
                at,
                actor);
    }

    /** 按读到的版本改工位；返回改到的行数。 */
    public int updateStations(
            String entity, String store, String device, String stations, long version, String actor, Instant now) {
        return jdbc.update(
                "UPDATE fnb_kds_subscription SET stations=?,version=version+1,updated_at=?,updated_by=?"
                        + " WHERE entity_no=? AND store_no=? AND device_no=? AND version=?",
                stations,
                Timestamp.from(now),
                actor,
                entity,
                store,
                device,
                version);
    }

    /** 这些工位里本店在册（未删除）的有几个。 */
    public int stationCount(String entity, String store, Collection<String> stations) {
        if (stations.isEmpty()) return 0;
        String marks = String.join(",", stations.stream().map(x -> "?").toList());
        var args = new java.util.ArrayList<Object>(List.of(entity, store));
        args.addAll(stations);
        Integer count =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM fnb_station WHERE entity_no=? AND store_no=? AND deleted=0"
                                + " AND station_no IN (" + marks + ")",
                        Integer.class,
                        args.toArray());
        return count == null ? 0 : count;
    }

    private static <T> T first(List<T> rows) {
        return rows.isEmpty() ? null : rows.getFirst();
    }
}
