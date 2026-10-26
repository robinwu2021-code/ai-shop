package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.paybridge.PayMerchantBackfillRunner;
import ai.neargo.shop.paybridge.port.PayMerchantReadSwitchImpl;
import ai.neargo.shop.spi.user.MerchantAdminPort;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * AC7：读切 {@code shop.pay.merchant.read-from}（TDD-支付服务-I4进件与收款商户 S6）。旧端口的支付解析在读切后由门店绑定回答
 * （没绑定 = 没有，不兜底 R01），切回 legacy 即恢复；配置要 pay 但比对门禁没放行 → 仍按旧解析。
 * 为看得出是谁在回答，回填后绕开校验直接把 s2 的新绑定改到 s3 的号上 —— 新旧两边对 s2 给出不同答案。
 */
// 支付服务 I1–I4 的场景共用一个 H2 库与上下文（见 TDD-支付服务-I3 偏差 15）
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:pay-i3;MODE=MySQL;"
        + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class PayMerchantReadSwitchFlowTest {

    @Autowired private MerchantQueryPort merchants;
    @Autowired private MerchantAdminPort merchantAdmin;
    @Autowired private PayMerchantReadSwitchImpl readSwitch;
    @Autowired private PayMerchantBackfillRunner backfill;
    @Autowired private JdbcTemplate jdbc;

    private String e;
    private String s1;
    private String s2;
    private String s3;
    private String s4;
    private String pm1;
    private String pm3;

    private String id() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String store(boolean isDefault) {
        String n = "S-RS-" + id();
        jdbc.update("INSERT INTO mch_store (entity_no, store_no, name, is_default, status, created_at, updated_at)"
                + " VALUES (?, ?, ?, ?, 'ACTIVE', NOW(), NOW())", e, n, "门店 " + n, isDefault ? 1 : 0);
        return n;
    }

    private String legacy(String storeNo, String settleCycle, String feeBearer) {
        String no = "PM-RS-" + id();
        jdbc.update("INSERT INTO mch_payment_merchant (entity_no, pay_merchant_no, pay_channel, store_no, sub_mchid,"
                + " apply_status, pay_methods, settle_cycle, fee_bearer, created_at, updated_at)"
                + " VALUES (?, ?, 'WECHAT', ?, ?, 'ACTIVE', '[\"JSAPI\"]', ?, ?, NOW(), NOW())",
                e, no, storeNo, "SUB-" + id(), settleCycle, feeBearer);
        return no;
    }

    @BeforeEach
    void world() {
        e = "E-RS-" + id();
        jdbc.update("INSERT INTO mch_entity (entity_no, name, market, funds_mode, status, created_at, updated_at)"
                + " VALUES (?, ?, 'CN', 'DIRECT', 'ACTIVE', NOW(), NOW())", e, "读切 " + e);
        s1 = store(true);
        s2 = store(false);
        s3 = store(false);
        pm1 = legacy("", "T+1", "MERCHANT");
        pm3 = legacy(s3, "T+7", "PLATFORM");
        assertThat(backfill.run(List.of(e)).conflicts()).isEmpty();
        jdbc.update("UPDATE pay_merchant_binding SET pay_merchant_id=(SELECT id FROM pay_merchant WHERE pay_merchant_no=?)"
                + " WHERE store_id=(SELECT id FROM mch_store WHERE store_no=?) AND status='ACTIVE'", pm3, s2);
        s4 = store(false);
    }

    @AfterEach
    void legacyAgain() {
        ReflectionTestUtils.setField(readSwitch, "requested", "legacy");
        ReflectionTestUtils.setField(readSwitch, "gateOpen", false);
    }

    private void switchTo(String mode, boolean gateOpen) {
        ReflectionTestUtils.setField(readSwitch, "requested", mode);
        ReflectionTestUtils.setField(readSwitch, "gateOpen", gateOpen);
    }

    private long quotaUsed(String no) {
        return jdbc.queryForObject("SELECT quota_used_minor FROM mch_payment_merchant WHERE pay_merchant_no=?", Long.class, no);
    }

    @Test
    @DisplayName("★★★ 读切后取号、账期、费用承担、额度都按门店绑定；没绑定的店解析不到（R01，不兜底主体级号）")
    void payModeAnswersFromBindings() {
        switchTo("pay", true);
        assertThat(readSwitch.readFromPay()).isTrue();

        assertThat(merchants.payMerchantNoOf(e, s2, "WECHAT")).contains(pm3);
        assertThat(merchants.payMerchantNoOf(e, s4, "WECHAT")).as("新店没绑定").isEmpty();
        assertThat(merchants.payMerchantNoOf(e, null, "WECHAT")).as("没门店 = 默认门店的绑定").contains(pm1);
        assertThat(merchants.settleCycleOf(e, s2, "WECHAT")).isEqualTo("T+7");
        assertThat(merchants.feeBearerOf(e, s2, "WECHAT")).isEqualTo("PLATFORM");
        assertThat(merchants.paymentRouteOf(e, s2, "WECHAT")).get()
                .extracting(MerchantQueryPort.PaymentRoute::payMerchantNo).isEqualTo(pm3);
        assertThat(merchants.activeChannelsOf(e, s4)).isEmpty();

        merchantAdmin.accruePayQuota(e, s2, "WECHAT", 100);
        assertThat(quotaUsed(pm3)).isEqualTo(100);
        assertThat(quotaUsed(pm1)).isZero();
    }

    @Test
    @DisplayName("★★★ 切回 legacy 即恢复旧解析：s2 回到主体级号、新店也照旧兜底")
    void switchBackRestoresLegacy() {
        switchTo("pay", true);
        assertThat(merchants.payMerchantNoOf(e, s2, "WECHAT")).contains(pm3);
        switchTo("legacy", true);
        assertThat(merchants.payMerchantNoOf(e, s2, "WECHAT")).contains(pm1);
        assertThat(merchants.payMerchantNoOf(e, s4, "WECHAT")).contains(pm1);
        assertThat(merchants.settleCycleOf(e, s2, "WECHAT")).isEqualTo("T+1");
        assertThat(merchants.paymentRouteOf(e, s2, "WECHAT")).as("旧口径只认门店显式绑定").isEmpty();
        assertThat(merchants.activeChannelsOf(e, s4)).containsExactly("WECHAT");
    }

    @Test
    @DisplayName("★★★ 配置要 pay、门禁没放行 → 仍按旧解析")
    void gateClosedStaysLegacy() {
        switchTo("pay", false);
        assertThat(readSwitch.readFromPay()).isFalse();
        assertThat(merchants.payMerchantNoOf(e, s2, "WECHAT")).contains(pm1);
    }

    @Test
    @DisplayName("★★★ 门禁跟着全量比对走：有未确认差异（本用例造的 s2 / s4 就是）→ 刷新后关上")
    void gateFollowsFullCompare() {
        switchTo("pay", true);
        assertThat(readSwitch.refreshGate()).isFalse();
        assertThat(readSwitch.readFromPay()).isFalse();
    }

    @Test
    @DisplayName("★★ 线下 / 免单 / 桩 / 测试渠道不对应收款号，读切后照旧口径")
    void channelFreeStaysLegacy() {
        switchTo("pay", true);
        assertThat(merchants.payMerchantNoOf(e, s4, "TEST")).contains(pm1);
    }
}
