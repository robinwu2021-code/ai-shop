package ai.neargo.shop.commercebridge;

import ai.neargo.shop.media.MediaRefColumn;
import ai.neargo.shop.media.MediaRefSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Keeps every reserved or submitted offline payment evidence key referenced for media GC.
 *
 * <p>Registered unconditionally: the scan job may run in a process where the mall HTTP beans are
 * off, and an unregistered column means real payment evidence would be judged orphaned. The column
 * is listed only once the commerce table exists, because a missing table aborts the whole scan.
 * Reservations count as references so an upload-then-crash window never loses bytes.
 */
@Component
public class ShopMallEvidenceMediaRefs implements MediaRefSource {
    static final MediaRefColumn EVIDENCE =
            MediaRefColumn.of("mall_offline_evidence", "asset_key", "商城线下付款凭证", "order_no");

    private final JdbcTemplate jdbc;

    /** 媒体引用守卫（MediaRefCoverageTest）按 DataSource 构造每个 MediaRefSource。 */
    public ShopMallEvidenceMediaRefs(javax.sql.DataSource database) {
        this(new JdbcTemplate(database));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ShopMallEvidenceMediaRefs(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<MediaRefColumn> columns() {
        Integer tables =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.tables WHERE"
                                + " table_schema=DATABASE() AND table_name='mall_offline_evidence'",
                        Integer.class);
        return tables != null && tables > 0 ? List.of(EVIDENCE) : List.of();
    }
}
