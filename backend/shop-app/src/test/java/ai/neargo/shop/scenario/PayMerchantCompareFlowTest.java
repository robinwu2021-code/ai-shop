package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.paybridge.PayMerchantBackfillRunner;
import ai.neargo.shop.paybridge.PayMerchantComparator;
import ai.neargo.shop.paybridge.PayMerchantComparator.Diff;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * AC6：比对 C07（TDD-支付服务-I4进件与收款商户 S5）。每个门店 × 渠道，旧解析（{@code payMerchantNoOf}）与新
 * {@code routeOf} 逐条比：号、子商户号、资金路径。每条差异带门店、渠道、新旧两个值；人工确认按「新旧两个值」认，
 * 数据再变确认就作废；只有全量比对且未确认差异为 0 才放行读切。漂移一律绕开双写直接改库造出来。
 */
// 支付服务 I1–I4 的场景共用一个 H2 库与上下文（见 TDD-支付服务-I3 偏差 15）
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:pay-i3;MODE=MySQL;"
        + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class PayMerchantCompareFlowTest {

    @Autowired private PayMerchantComparator comparator;
    @Autowired private PayMerchantBackfillRunner backfill;
    @Autowired private JdbcTemplate jdbc;

    private String e;
    private String s1;
    private String s2;
    private String s3;
    private String pm1;
    private String pm2;

    private String id() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String store(boolean isDefault) {
        String n = "S-CMP-" + id();
        jdbc.update("INSERT INTO mch_store (entity_no, store_no, name, is_default, created_at, updated_at)"
                + " VALUES (?, ?, ?, ?, NOW(), NOW())", e, n, "门店 " + n, isDefault ? 1 : 0);
        return n;
    }

    private String legacy(String storeNo) {
        String no = "PM-CMP-" + id();
        jdbc.update("INSERT INTO mch_payment_merchant (entity_no, pay_merchant_no, pay_channel, store_no, sub_mchid,"
                + " apply_status, pay_methods, created_at, updated_at)"
                + " VALUES (?, ?, 'WECHAT', ?, ?, 'ACTIVE', '[\"JSAPI\"]', NOW(), NOW())", e, no, storeNo, "SUB-" + id());
        return no;
    }

    /** 主体级 pm1（s1 默认、s3 靠展开）+ s2 专属 pm2，回填后两边一致 */
    @BeforeEach
    void world() {
        e = "E-CMP-" + id();
        jdbc.update("INSERT INTO mch_entity (entity_no, name, market, funds_mode, created_at, updated_at)"
                + " VALUES (?, ?, 'CN', 'DIRECT', NOW(), NOW())", e, "比对 " + e);
        s1 = store(true);
        s2 = store(false);
        s3 = store(false);
        pm1 = legacy("");
        pm2 = legacy(s2);
        assertThat(backfill.run(List.of(e)).conflicts()).isEmpty();
    }

    private PayMerchantComparator.Result compare(Set<String> confirmed) {
        return comparator.compare(List.of(e), confirmed);
    }

    private Diff diffOf(PayMerchantComparator.Result r, String storeNo) {
        return r.diffs().stream().filter(d -> d.storeNo().equals(storeNo)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("★★★ 回填后逐店一致：3 店 × 微信比过、差异 0；但只比了一个主体，不放行读切")
    void consistentAfterBackfill() {
        PayMerchantComparator.Result r = compare(Set.of());
        assertThat(r.checked()).isEqualTo(3);
        assertThat(r.diffs()).isEmpty();
        assertThat(r.passed()).isTrue();
        assertThat(r.readSwitchAllowed()).as("部分比对不能当门禁").isFalse();
    }

    @Test
    @DisplayName("★★★ 漂移逐条报：新店新模型没绑（NO_PAY）、新模型改绑了别的号（NUMBER），各带门店、渠道、新旧两个号")
    void driftsReportedWithBothNumbers() {
        String s4 = store(false);
        jdbc.update("UPDATE pay_merchant_binding SET pay_merchant_id=(SELECT id FROM pay_merchant WHERE pay_merchant_no=?)"
                + " WHERE store_id=(SELECT id FROM mch_store WHERE store_no=?) AND status='ACTIVE'", pm1, s2);

        PayMerchantComparator.Result r = compare(Set.of());

        assertThat(r.diffs()).hasSize(2);
        assertThat(diffOf(r, s4)).extracting(Diff::kind, Diff::channel, Diff::legacyValue, Diff::payValue)
                .containsExactly("NO_PAY", "WECHAT", pm1, null);
        assertThat(diffOf(r, s2)).extracting(Diff::kind, Diff::legacyValue, Diff::payValue)
                .containsExactly("NUMBER", pm2, pm1);
        assertThat(diffOf(r, s2).toString()).contains(s2).contains("WECHAT").contains(pm1).contains(pm2);
        assertThat(r.passed()).isFalse();
    }

    @Test
    @DisplayName("★★★ 旧号冻结新模型还在收（NO_LEGACY）；主体切了资金路径新模型没跟（FUNDS_PATH）")
    void statusAndFundsPathDrift() {
        jdbc.update("UPDATE mch_payment_merchant SET apply_status='FROZEN' WHERE pay_merchant_no=?", pm1);
        jdbc.update("UPDATE mch_entity SET funds_mode='AGGREGATED' WHERE entity_no=?", e);

        PayMerchantComparator.Result r = compare(Set.of());

        assertThat(diffOf(r, s1)).extracting(Diff::kind, Diff::legacyValue, Diff::payValue)
                .containsExactly("NO_LEGACY", null, pm1);
        assertThat(diffOf(r, s3).kind()).isEqualTo("NO_LEGACY");
        assertThat(diffOf(r, s2)).extracting(Diff::kind, Diff::legacyValue, Diff::payValue)
                .containsExactly("FUNDS_PATH", "AGGREGATED", "DIRECT");
    }

    @Test
    @DisplayName("★★★ 子商户号不一致也算差异：号对上了、实际收款的渠道子商户不是同一个")
    void subMerchantDrift() {
        jdbc.update("UPDATE mch_payment_merchant SET sub_mchid='SUB-CHANGED' WHERE pay_merchant_no=?", pm2);
        Diff d = diffOf(compare(Set.of()), s2);
        assertThat(d).extracting(Diff::kind, Diff::legacyValue).containsExactly("SUB_MCHID", "SUB-CHANGED");
    }

    @Test
    @DisplayName("★★★ 人工确认按新旧两个值认：确认后通过；数据再变，旧确认作废")
    void confirmationIsBoundToBothValues() {
        String s4 = store(false);
        Diff d = diffOf(compare(Set.of()), s4);

        assertThat(compare(Set.of(d.key())).passed()).isTrue();
        assertThat(compare(Set.of(d.key())).diffs()).allMatch(Diff::confirmed);

        jdbc.update("UPDATE mch_payment_merchant SET pay_merchant_no=? WHERE pay_merchant_no=?",
                "PM-CMP-X" + id(), pm1);
        Diff after = diffOf(compare(Set.of(d.key())), s4);
        assertThat(after.legacyValue()).isNotEqualTo(d.legacyValue());
        assertThat(after.confirmed()).as("s4 旧解析已变成另一个号，旧确认不再算数").isFalse();
    }
}
