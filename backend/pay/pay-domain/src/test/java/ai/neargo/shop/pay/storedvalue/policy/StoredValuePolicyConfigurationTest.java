package ai.neargo.shop.pay.storedvalue.policy;

import ai.neargo.shop.pay.storedvalue.policy.impl.StoredValuePolicyConfigurationServiceImpl;

import ai.neargo.shop.pay.storedvalue.policy.impl.StoredValueIssuePolicyServiceImpl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository;
import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

class StoredValuePolicyConfigurationTest {
    @Test void immutableVersionsOriginalReplayAndStaleEdit() throws Exception {
        var f = fixture(); var root = authority("SPACE", "M1", null);
        var first = f.service.configure(root, new PolicyEdit(rules(2000), 0), "FIRST");
        assertThat(first.revision()).isZero();
        assertThat(f.service.configure(root, new PolicyEdit(rules(2000), 0), "FIRST")).isEqualTo(first);
        assertThatThrownBy(() -> f.service.configure(root, new PolicyEdit(rules(1000), 0), "FIRST"))
                .isInstanceOf(BizException.class);
        var second = f.service.configure(root, new PolicyEdit(rules(1000), 0), "SECOND");
        assertThat(second.policyNo()).isEqualTo(first.policyNo());
        assertThat(second.versionNo()).isNotEqualTo(first.versionNo());
        assertThat(second.revision()).isEqualTo(1);
        assertThatThrownBy(() -> f.service.configure(root, new PolicyEdit(rules(500), 0), "STALE"))
                .isInstanceOf(BizException.class);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM sv_issue_policy_version", Long.class)).isEqualTo(2);
        assertThat(f.service.configure(root, new PolicyEdit(rules(2000), 0), "FIRST")).isEqualTo(first);
    }

    @Test void requiresParentAndChildCanOnlyTighten() throws Exception {
        var f = fixture(); var child = authority("STORE", "S1", "B1");
        assertThatThrownBy(() -> f.service.configure(child, new PolicyEdit(rules(1000), 0), "NO_ROOT"))
                .isInstanceOf(BizException.class);
        f.service.configure(authority("SPACE", "M1", null), new PolicyEdit(rules(3000), 0), "ROOT");
        f.service.configure(authority("BRAND_STORES", "B1", null), new PolicyEdit(rules(2000), 0), "BRAND");
        assertThatThrownBy(() -> f.service.configure(child, new PolicyEdit(rules(2500), 0), "LOOSER"))
                .isInstanceOf(BizException.class);
        f.service.configure(child, new PolicyEdit(rules(1000), 0), "STORE");
        var effective = f.query.evaluate(new StoreScope("MAIN", "M1", "S1", "B1", "CNY"));
        assertThat(effective.configured()).isTrue();
        assertThat(effective.rules().maxBonusBps()).isEqualTo(1000);
        assertThat(effective.sources()).hasSize(3);
    }

    @Test void lockedEvaluationRequiresActualPayTransaction() throws Exception {
        var f = fixture(); var scope = new StoreScope("MAIN", "M1", "S1", null, "CNY");
        assertThatThrownBy(() -> f.query.evaluateLocked(scope)).isInstanceOf(IllegalStateException.class);
        f.service.configure(authority("SPACE", "M1", null), new PolicyEdit(rules(2000), 0), "ROOT");
        assertThat(f.db.write(() -> f.query.evaluateLocked(scope)).configured()).isTrue();
        assertThat(f.query.evaluate(new StoreScope("MAIN", "OTHER", "S1", null, "CNY")).configured()).isFalse();
    }

    private PolicyAuthority authority(String type, String no, String brand) {
        return new PolicyAuthority("MAIN", "OWNER", "M1", type, no, brand, "CNY", 7);
    }
    private Rules rules(int bps) { return new Rules(true, true, bps, 100000, 100000, 365, 1000000); }
    private Fixture fixture() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(ds);
        var ddl = Files.readString(Path.of("../../shop-app/src/main/resources/db/migration/V564__stored_value_issue_policy.sql"));
        ddl=ddl.replace("CHECK (target_type IN ('SPACE','BRAND_STORES','STORE'))", "CHECK (CASE target_type WHEN 'SPACE' THEN TRUE WHEN 'BRAND_STORES' THEN TRUE WHEN 'STORE' THEN TRUE ELSE FALSE END)").replace("CHECK (status IN ('ACTIVE','INACTIVE'))", "CHECK (CASE status WHEN 'ACTIVE' THEN TRUE WHEN 'INACTIVE' THEN TRUE ELSE FALSE END)").replace("allow_issue IN (0,1)","allow_issue BETWEEN 0 AND 1").replace("require_approval IN (0,1)","require_approval BETWEEN 0 AND 1").replace("deleted IN (0,1)","deleted BETWEEN 0 AND 1").replace(" JSON"," VARCHAR(20000)").replaceAll(" ENGINE=InnoDB[^;]*", "");
        for (String sql : ddl.split(";")) if (!sql.isBlank()) jdbc.execute(sql);
        var db = new IssuerRepository(new DataSourceTransactionManager(ds), new ObjectMapper());
        var evaluator = new StoredValueIssuePolicyEvaluator();
        return new Fixture(new StoredValuePolicyConfigurationServiceImpl(db, evaluator),
                new StoredValueIssuePolicyServiceImpl(db, evaluator), db, jdbc);
    }
    private record Fixture(StoredValuePolicyConfigurationService service, StoredValueIssuePolicyService query,
            IssuerRepository db, JdbcTemplate jdbc) { }
}
