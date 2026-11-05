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

    public record Changed(String storeNo, String entityNo, long revision) {
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
                r.getString("address_detail"), r.getString("status"), r.getInt("deleted") != 0, millis(r.getTimestamp("changed_at"))),
                storeNo).stream().findFirst();
    }

    /** 自 since 起变化的门店（含已删除），按变化时间升序 */
    public List<Changed> changedSince(LocalDateTime since, int limit) {
        return jdbc.query("SELECT store_no, entity_no, changed_at FROM (" + SELECT + ") x WHERE x.changed_at >= ? ORDER BY x.changed_at LIMIT ?",
                (r, i) -> new Changed(r.getString("store_no"), r.getString("entity_no"), millis(r.getTimestamp("changed_at"))),
                Timestamp.valueOf(since), limit);
    }

    public List<Changed> all(List<String> storeNos) {
        String where = storeNos == null || storeNos.isEmpty() ? "" : " WHERE s.store_no IN (" + String.join(",", storeNos.stream().map(x -> "?").toList()) + ")";
        return jdbc.query(SELECT + where + " ORDER BY s.id", (r, i) -> new Changed(r.getString("store_no"), r.getString("entity_no"),
                millis(r.getTimestamp("changed_at"))), storeNos == null ? new Object[0] : storeNos.toArray());
    }

    static long millis(Timestamp t) {
        return t == null ? 1L : Math.max(1L, t.toLocalDateTime().toInstant(ZoneOffset.UTC).toEpochMilli());
    }
}
