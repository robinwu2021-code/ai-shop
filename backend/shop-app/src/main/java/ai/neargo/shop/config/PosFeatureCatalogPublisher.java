package ai.neargo.shop.config;

import ai.neargo.shop.portal.biz.feature.PosFeatureCatalog;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;

/** Explicit operator profile for publishing one reviewed bundled release to one tenant. */
@Component
@Profile("pos-catalog-publish")
public class PosFeatureCatalogPublisher implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final String tenant;
    private final String expected;
    private final String revision;
    private final String operator;
    private final String note;

    public PosFeatureCatalogPublisher(DataSource dataSource,
            @Value("${shop.pos.feature-catalog.publish-tenant}") String tenant,
            @Value("${shop.pos.feature-catalog.expected-release}") String expected,
            @Value("${shop.pos.feature-catalog.source-revision}") String revision,
            @Value("${shop.pos.feature-catalog.published-by}") String operator,
            @Value("${shop.pos.feature-catalog.change-note}") String note) {
        jdbc = new JdbcTemplate(dataSource);
        this.tenant = tenant;
        this.expected = expected;
        this.revision = revision;
        this.operator = operator;
        this.note = note;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (tenant.isBlank() || revision.isBlank() || operator.isBlank() || note.isBlank()
                || !(expected.equals("NONE") || expected.matches("[0-9a-f]{64}"))) {
            throw new IllegalArgumentException("Invalid POS catalog publication metadata");
        }
        var catalog = PosFeatureCatalog.bundled();
        String release = catalog.releaseNo();
        var rows = jdbc.queryForList("""
                SELECT release_no FROM sys_pos_feature_catalog_current WHERE tenant_no = ? FOR UPDATE
                """, String.class, tenant);
        String current = rows.isEmpty() ? "NONE" : rows.getFirst();
        if (current.equals(release)) return;
        if (!current.equals(expected)) throw new IllegalStateException("POS catalog active version changed");

        jdbc.update("""
                INSERT INTO sys_pos_feature_catalog_release
                    (tenant_no, release_no, schema_version, manifest, manifest_canonical, source_revision,
                     published_by, change_note)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE release_no = release_no
                """, tenant, release, catalog.schemaVersion(), catalog.canonicalJson(), catalog.canonicalJson(),
                revision, operator, note);
        if (current.equals("NONE")) {
            jdbc.update("""
                    INSERT INTO sys_pos_feature_catalog_current (tenant_no, release_no, changed_by) VALUES (?, ?, ?)
                    """, tenant, release, operator);
        } else {
            jdbc.update("""
                    UPDATE sys_pos_feature_catalog_current SET release_no = ?, changed_by = ?,
                        changed_at = CURRENT_TIMESTAMP(3) WHERE tenant_no = ? AND release_no = ?
                    """, release, operator, tenant, expected);
        }
    }
}
