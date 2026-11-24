package ai.neargo.shop.portal.biz.feature;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

/** Reads the active tenant pointer and caches validated, immutable release contents. */
@Component
@ConditionalOnProperty(name = "commerce.enabled", havingValue = "true")
public class PosFeatureCatalogStore {
    private final JdbcTemplate jdbc;
    private final boolean database;
    private final ConcurrentHashMap<String, PosFeatureCatalog> releases = new ConcurrentHashMap<>();

    public PosFeatureCatalogStore(DataSource dataSource,
            @Value("${shop.pos.feature-catalog.database-enabled:false}") boolean database) {
        jdbc = new JdbcTemplate(dataSource);
        this.database = database;
        PosFeatureCatalog.bundled();
    }

    /** Tenants without a published pointer use the release shipped with the current deployment. */
    public PosFeatureCatalog current(String tenantNo) {
        if (!database) return PosFeatureCatalog.bundled();
        var current = jdbc.queryForList("SELECT release_no FROM sys_pos_feature_catalog_current WHERE tenant_no = ?",
                String.class, tenantNo);
        if (current.isEmpty()) return PosFeatureCatalog.bundled();
        String release = current.getFirst();
        return releases.computeIfAbsent(release, id -> load(tenantNo, id));
    }

    private PosFeatureCatalog load(String tenantNo, String releaseNo) {
        String json = jdbc.queryForObject("""
                SELECT manifest_canonical FROM sys_pos_feature_catalog_release WHERE tenant_no = ? AND release_no = ?
                """, String.class, tenantNo, releaseNo);
        try {
            return PosFeatureCatalog.fromPublishedJson(json, releaseNo);
        } catch (IOException e) {
            throw new IllegalStateException("Invalid published POS catalog " + releaseNo, e);
        }
    }
}
