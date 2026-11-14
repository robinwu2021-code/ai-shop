package ai.neargo.shop.merchant.paysync;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 读门店 + 商户当前展示信息。修订号 = 门店与商户 updated_at（毫秒）中较大者，单调递增。 */
@Component
public class StoreSnapshotReader {

    public record Snapshot(long storeId, String storeNo, String entityNo, String entityName, String entityLogo, String market,
                           String name, String slug, String bannerUrl, String openHours, String address, String addressDetail,
                           String status, boolean deleted, long revision) {
    }

    /** {@code changedAt} 原样取自数据库（与 {@link #dbNow()} 同一口径），只拿来做游标，不换算时区。 */
    public record Changed(long id, String storeNo, String entityNo, LocalDateTime changedAt, long revision) {
    }

    /** 扫描游标：(变更时间, 门店 id) 复合，同一时刻的大批变更也能逐批前进。 */
    public record Cursor(LocalDateTime changedAt, long id) {
    }

    private static final String SELECT = "SELECT s.id, s.store_no, s.entity_no, e.name AS entity_name, e.logo, e.market, s.name, s.slug,"
            + " s.banner_url, s.open_hours, s.address, s.address_detail, s.status, s.deleted,"
            + " GREATEST(COALESCE(s.updated_at, s.created_at), COALESCE(e.updated_at, e.created_at)) AS changed_at"
            + " FROM mch_store s JOIN mch_entity e ON e.entity_no = s.entity_no";

    private final JdbcTemplate jdbc;

    public StoreSnapshotReader(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    public Optional<Snapshot> read(String storeNo) {
        return jdbc.query(SELECT + " WHERE s.store_no = ?", (r, i) -> new Snapshot(r.getLong("id"), r.getString("store_no"),
                r.getString("entity_no"), r.getString("entity_name"), r.getString("logo"), r.getString("market"), r.getString("name"),
                r.getString("slug"), r.getString("banner_url"), r.getString("open_hours"), r.getString("address"),
                r.getString("address_detail"), r.getString("status"), r.getInt("deleted") != 0, millis(r.getTimestamp("changed_at").toLocalDateTime())),
                storeNo).stream().findFirst();
    }

    /** 主体资料：申请支付服务时作 sourceEntityRef / entityName；revision = updated_at 毫秒（与门店修订号同一口径） */
    public record Entity(String entityNo, String name, String market, long revision) {
    }

    public Optional<Entity> entity(String entityNo) {
        return jdbc.query("SELECT entity_no, name, market, COALESCE(updated_at, created_at) AS changed_at FROM mch_entity"
                        + " WHERE entity_no = ? AND deleted = 0",
                (r, i) -> new Entity(r.getString("entity_no"), r.getString("name"), r.getString("market"),
                        millis(r.getTimestamp("changed_at") == null ? null : r.getTimestamp("changed_at").toLocalDateTime())),
                entityNo).stream().findFirst();
    }

    /** 主体名下全部门店的当前快照（未删除），按门店 id 排序 */
    public List<Snapshot> storesOfEntity(String entityNo) {
        return jdbc.query(SELECT + " WHERE s.entity_no = ? AND s.deleted = 0 ORDER BY s.id", (r, i) -> new Snapshot(r.getLong("id"),
                r.getString("store_no"), r.getString("entity_no"), r.getString("entity_name"), r.getString("logo"),
                r.getString("market"), r.getString("name"), r.getString("slug"), r.getString("banner_url"),
                r.getString("open_hours"), r.getString("address"), r.getString("address_detail"), r.getString("status"),
                r.getInt("deleted") != 0, millis(r.getTimestamp("changed_at").toLocalDateTime())), entityNo);
    }

    /** 游标之后变化的门店（含已删除），按 (变更时间, 门店 id) 升序 */
    public List<Changed> changedAfter(Cursor after, int limit) {
        Timestamp at = Timestamp.valueOf(after.changedAt());
        return jdbc.query("SELECT id, store_no, entity_no, changed_at FROM (" + SELECT + ") x"
                        + " WHERE x.changed_at > ? OR (x.changed_at = ? AND x.id > ?) ORDER BY x.changed_at, x.id LIMIT ?",
                (r, i) -> changed(r), at, at, after.id(), limit);
    }

    public List<Changed> all(List<String> storeNos) {
        String where = storeNos == null || storeNos.isEmpty() ? "" : " WHERE s.store_no IN (" + String.join(",", storeNos.stream().map(x -> "?").toList()) + ")";
        return jdbc.query(SELECT + where + " ORDER BY s.id", (r, i) -> changed(r), storeNos == null ? new Object[0] : storeNos.toArray());
    }

    /** 数据库当前时间，和 updated_at 同一时区口径（不用本机时钟，避免 JVM 与库时区不一致时水位漂移）。 */
    public LocalDateTime dbNow() {
        return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", Timestamp.class).toLocalDateTime();
    }

    private static Changed changed(java.sql.ResultSet r) throws java.sql.SQLException {
        LocalDateTime at = r.getTimestamp("changed_at").toLocalDateTime();
        return new Changed(r.getLong("id"), r.getString("store_no"), r.getString("entity_no"), at, millis(at));
    }

    /** 修订号：变更时间的毫秒值。只用于单调比较与事件号，不代表真实 UTC 时刻。 */
    static long millis(LocalDateTime t) {
        return t == null ? 1L : Math.max(1L, t.toInstant(ZoneOffset.UTC).toEpochMilli());
    }
}
