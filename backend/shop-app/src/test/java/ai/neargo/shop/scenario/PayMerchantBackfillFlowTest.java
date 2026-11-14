package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.paybridge.PayMerchantBackfillRunner;
import ai.neargo.shop.paybridge.PayMerchantBackfillRunner.Report;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * AC4：回填 B1–B4（TDD-支付服务-I4进件与收款商户 S3）。规则见表结构说明 §8.1 / §8.2：
 * 主体级号落到默认门店、并逐店展开成绑定；两个来源给同一门店 × 渠道不同的号 → 冲突报告，不自动取舍；
 * 找不到默认门店 → 冲突报告（Q2）；映射不到支付产品的方式 → 冲突报告。再跑一遍什么都不多。
 */
// 支付服务 I1–I4 的场景共用一个 H2 库与上下文（见 TDD-支付服务-I3 偏差 15）
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:pay-i3;MODE=MySQL;"
        + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class PayMerchantBackfillFlowTest {

    @Autowired private PayMerchantBackfillRunner runner;
    @Autowired private JdbcTemplate jdbc;

    private String e;
    private String noDefault;
    private String s1;
    private String s2;
    private String s3;
    private String s4;
    private String pm1;
    private String pm2;
    private String pm3;

    private String id() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String entity(String funds) {
        String n = "E-BF-" + id();
        jdbc.update("INSERT INTO mch_entity (entity_no, name, market, funds_mode, created_at, updated_at)"
                + " VALUES (?, ?, 'CN', ?, NOW(), NOW())", n, "主体 " + n, funds);
        return n;
    }

    private String store(String entityNo, boolean isDefault, String payMerchantNo) {
        String n = "S-BF-" + id();
        jdbc.update("INSERT INTO mch_store (entity_no, store_no, name, is_default, pay_merchant_no, created_at, updated_at)"
                + " VALUES (?, ?, ?, ?, ?, NOW(), NOW())", entityNo, n, "门店 " + n, isDefault ? 1 : 0, payMerchantNo);
        return n;
    }

    private String legacy(String entityNo, String storeNo, String status, String sub, String methods, long quota) {
        String no = "ACTIVE".equals(status) ? "PM-BF-" + id() : null;
        jdbc.update("INSERT INTO mch_payment_merchant (entity_no, pay_merchant_no, pay_channel, store_no, sub_mchid,"
                + " channel_apply_no, apply_status, pay_methods, quota_limit_minor, created_at, updated_at)"
                + " VALUES (?, ?, 'WECHAT', ?, ?, ?, ?, ?, ?, NOW(), NOW())",
                entityNo, no, storeNo, sub, "APPLY-" + id(), status, methods, quota);
        return no;
    }

    @BeforeEach
    void world() {
        e = entity("DIRECT");
        s1 = store(e, true, null);
        pm1 = legacy(e, "", "ACTIVE", "SUB-" + id(), "[\"JSAPI\"]", 100_000);
        s2 = store(e, false, null);
        pm2 = legacy(e, s2, "ACTIVE", "SUB-" + id(), "[\"JSAPI\",\"H5\"]", 0);
        jdbc.update("UPDATE mch_store SET pay_merchant_no=? WHERE store_no=?", pm2, s2);
        s3 = store(e, false, null);
        s4 = store(e, false, pm1);
        pm3 = legacy(e, s4, "ACTIVE", "SUB-" + id(), "[\"JSAPI\"]", 0);
        noDefault = entity("AGGREGATED");
        store(noDefault, false, null);
        legacy(noDefault, "", "ACTIVE", "SUB-" + id(), "[\"JSAPI\"]", 0);
    }

    private Map<String, Object> merchant(String no) {
        return jdbc.queryForMap("SELECT * FROM pay_merchant WHERE pay_merchant_no=?", no);
    }

    private Long storeId(String storeNo) {
        return jdbc.queryForObject("SELECT id FROM mch_store WHERE store_no=?", Long.class, storeNo);
    }

    private String boundTo(String storeNo) {
        List<String> r = jdbc.queryForList("SELECT m.pay_merchant_no FROM pay_merchant_binding b JOIN pay_merchant m"
                + " ON m.id = b.pay_merchant_id WHERE b.store_id=? AND b.pay_channel='WECHAT' AND b.status='ACTIVE'",
                String.class, storeId(storeNo));
        return r.isEmpty() ? null : r.get(0);
    }

    @Test
    @DisplayName("★★★ B2：主体级号落到默认门店；原号保留、资金路径取主体、服务商模式挂平台级父商户（B1）")
    void merchantsMigrated() {
        Report report = runner.run(List.of(e, noDefault));
        Map<String, Object> m = merchant(pm1);
        assertThat(m).containsEntry("owner_type", "STORE").containsEntry("store_id", storeId(s1))
                .containsEntry("funds_path", "DIRECT").containsEntry("status", "ACTIVE").containsEntry("market", "CN")
                .containsEntry("currency", "CNY");
        assertThat(m.get("access_mode")).isIn("SERVICE_PROVIDER", "MERCHANT_OWN");
        if ("SERVICE_PROVIDER".equals(m.get("access_mode"))) {
            assertThat(jdbc.queryForObject("SELECT owner_type FROM pay_merchant WHERE id=?", String.class,
                    m.get("parent_pay_merchant_id"))).isEqualTo("PLATFORM");
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_onboarding WHERE store_id=? AND status='APPROVED'",
                Integer.class, storeId(s1))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT quota_limit_minor FROM pay_merchant_product p JOIN pay_merchant m"
                + " ON m.id = p.pay_merchant_id WHERE m.pay_merchant_no=?", Long.class, pm1)).isEqualTo(100_000L);
        assertThat(report.merchants()).isEqualTo(3);
    }

    @Test
    @DisplayName("★★★ B3：门店号与行上门店号合并；主体级号逐店展开到没有绑定的门店；两来源不一致 → 冲突、不绑")
    void bindingsExpandedAndConflictsReported() {
        Report report = runner.run(List.of(e, noDefault));
        assertThat(boundTo(s1)).as("默认门店").isEqualTo(pm1);
        assertThat(boundTo(s2)).as("门店自己的号").isEqualTo(pm2);
        assertThat(boundTo(s3)).as("主体级号展开").isEqualTo(pm1);
        assertThat(boundTo(s4)).as("门店说 pm1、行说 pm3：不自动取舍").isNull();
        assertThat(report.conflicts()).anySatisfy(c -> assertThat(c).startsWith("Q3").contains(s4))
                .anySatisfy(c -> assertThat(c).startsWith("Q2").contains(noDefault))
                .anySatisfy(c -> assertThat(c).startsWith("METHOD").contains("H5"));
    }

    @Test
    @DisplayName("★★★ 幂等：再跑一遍商户、进件、绑定、产品一行都不多")
    void idempotent() {
        runner.run(List.of(e, noDefault));
        Map<String, Object> before = counts();
        runner.run(List.of(e, noDefault));
        assertThat(counts()).isEqualTo(before);
    }

    private Map<String, Object> counts() {
        return jdbc.queryForMap("SELECT (SELECT COUNT(*) FROM pay_merchant) m, (SELECT COUNT(*) FROM pay_onboarding) o,"
                + " (SELECT COUNT(*) FROM pay_onboarding_version) v, (SELECT COUNT(*) FROM pay_merchant_binding) b,"
                + " (SELECT COUNT(*) FROM pay_merchant_product) p");
    }
}
