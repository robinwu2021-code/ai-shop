package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import ai.neargo.shop.merchant.service.MerchantGovernService;
import ai.neargo.shop.merchant.service.MerchantPaymentService;
import ai.neargo.shop.merchant.service.StoreAdminService;
import ai.neargo.shop.paybridge.PayMerchantBackfillRunner;
import ai.neargo.shop.paybridge.port.PayMerchantMirrorPortImpl;
import ai.neargo.shop.spi.pay.PayApplymentGateway;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * AC5：双写 {@code shop.pay.merchant.dual-write}（TDD-支付服务-I4进件与收款商户 S4）。
 * 走真的旧入口（进件回查、门店指定收款号、切资金路径），旧表提交后新表跟上；旧表说了算（改绑、状态、资金路径都跟）；
 * 新表写失败不挡旧路径。开关在共用上下文里，用例自己开、自己关。
 */
// 支付服务 I1–I4 的场景共用一个 H2 库与上下文（见 TDD-支付服务-I3 偏差 15）
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:pay-i3;MODE=MySQL;"
        + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class PayMerchantDualWriteFlowTest {

    @Autowired private MerchantPaymentService payments;
    @Autowired private StoreAdminService storeAdmin;
    @Autowired private MerchantGovernService govern;
    @Autowired private PayMerchantMirrorPortImpl mirror;
    @Autowired private List<PayApplymentGateway> gateways;
    @Autowired private JdbcTemplate jdbc;

    private String e;
    private String s1;
    private String s2;

    private String id() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String store(boolean isDefault) {
        String n = "S-DW-" + id();
        jdbc.update("INSERT INTO mch_store (entity_no, store_no, name, is_default, created_at, updated_at)"
                + " VALUES (?, ?, ?, ?, NOW(), NOW())", e, n, "门店 " + n, isDefault ? 1 : 0);
        return n;
    }

    /** 主体级 STUB 进件，停在审核中；申请单号是桩认得的，回查即 ACTIVE */
    @BeforeEach
    void world() {
        e = "E-DW-" + id();
        jdbc.update("INSERT INTO mch_entity (entity_no, name, market, funds_mode, created_at, updated_at)"
                + " VALUES (?, ?, 'CN', 'AGGREGATED', NOW(), NOW())", e, "双写 " + e);
        s1 = store(true);
        s2 = store(false);
        String applyNo = gateways.stream().filter(g -> "STUB".equals(g.payChannel())).findFirst().orElseThrow()
                .submit(new PayApplymentGateway.SubmitCommand(e, "双写 " + e, "INDIVIDUAL", "张三", "13800138000",
                        List.of(), "PERSONAL_BANK", "6222000000000000", "", e + "-E-1"));
        jdbc.update("INSERT INTO mch_payment_merchant (entity_no, store_no, pay_channel, apply_status, channel_apply_no,"
                + " applied_at, created_at, updated_at) VALUES (?, '', 'STUB', 'APPLYING', ?, ?, NOW(), NOW())",
                e, applyNo, System.currentTimeMillis());
        ReflectionTestUtils.setField(mirror, "enabled", true);
    }

    @AfterEach
    void off() {
        ReflectionTestUtils.setField(mirror, "enabled", false);
    }

    private String legacyNo() {
        return jdbc.queryForObject("SELECT pay_merchant_no FROM mch_payment_merchant WHERE entity_no=? AND store_no=''",
                String.class, e);
    }

    private Long storeId(String storeNo) {
        return jdbc.queryForObject("SELECT id FROM mch_store WHERE store_no=?", Long.class, storeNo);
    }

    private String boundTo(String storeNo) {
        List<String> r = jdbc.queryForList("SELECT m.pay_merchant_no FROM pay_merchant_binding b JOIN pay_merchant m"
                + " ON m.id = b.pay_merchant_id WHERE b.store_id=? AND b.pay_channel='STUB' AND b.status='ACTIVE'",
                String.class, storeId(storeNo));
        return r.isEmpty() ? null : r.get(0);
    }

    private Map<String, Object> onboarding() {
        return jdbc.queryForMap("SELECT o.* FROM pay_onboarding o JOIN mch_payment_merchant p"
                + " ON o.onboarding_no = CONCAT('LEGACY-', p.id) WHERE p.entity_no=? AND p.store_no=''", e);
    }

    @Test
    @DisplayName("★★★ 进件结果：审核中先镜像成在途进件单；回查开户后进件单 APPROVED、收款商户 ACTIVE、两家店都绑上")
    void onboardingResultMirrored() {
        govern.setFundsMode(e, "DIRECT", "OP-DW");
        assertThat(onboarding()).containsEntry("status", "CHANNEL_REVIEW").containsEntry("inflight_key", storeId(s1) + "|STUB");

        payments.refresh(e, "STUB", null);

        String no = legacyNo();
        assertThat(no).isNotBlank();
        assertThat(jdbc.queryForMap("SELECT status, funds_path, store_id FROM pay_merchant WHERE pay_merchant_no=?", no))
                .containsEntry("status", "ACTIVE").containsEntry("funds_path", "DIRECT").containsEntry("store_id", storeId(s1));
        assertThat(onboarding()).containsEntry("status", "APPROVED").containsEntry("inflight_key", null);
        assertThat(boundTo(s1)).isEqualTo(no);
        assertThat(boundTo(s2)).as("主体级号展开").isEqualTo(no);
    }

    @Test
    @DisplayName("★★★ 门店指定收款号：旧表说了算，新模型跟着改绑（同一门店 × 渠道仍只一条 ACTIVE）")
    void storeBindingMirrored() {
        payments.refresh(e, "STUB", null);
        assertThat(boundTo(s2)).isEqualTo(legacyNo());
        String own = "PM-DW-" + id();
        jdbc.update("INSERT INTO mch_payment_merchant (entity_no, pay_merchant_no, store_no, pay_channel, apply_status,"
                + " sub_mchid, created_at, updated_at) VALUES (?, ?, ?, 'STUB', 'ACTIVE', ?, NOW(), NOW())",
                e, own, s2, "SUB-" + id());

        storeAdmin.setPayment(e, s2, own);

        assertThat(boundTo(s2)).isEqualTo(own);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_merchant_binding WHERE store_id=? AND pay_channel='STUB'"
                + " AND status='ACTIVE'", Integer.class, storeId(s2))).isEqualTo(1);
        assertThat(boundTo(s1)).as("别的店不受影响").isEqualTo(legacyNo());
    }

    @Test
    @DisplayName("★★★ 资金路径与状态：切资金路径、旧号冻结都跟到收款商户上，并各留一条状态日志")
    void fundsModeAndStatusMirrored() {
        payments.refresh(e, "STUB", null);
        String no = legacyNo();
        jdbc.update("UPDATE mch_payment_merchant SET apply_status='FROZEN' WHERE pay_merchant_no=?", no);

        govern.setFundsMode(e, "DIRECT", "OP-DW");

        assertThat(jdbc.queryForMap("SELECT status, funds_path FROM pay_merchant WHERE pay_merchant_no=?", no))
                .containsEntry("status", "SUSPENDED").containsEntry("funds_path", "DIRECT");
        assertThat(jdbc.queryForList("SELECT CONCAT(l.from_status, '>', l.to_status) FROM pay_merchant_status_log l"
                + " JOIN pay_merchant m ON m.id = l.pay_merchant_id WHERE m.pay_merchant_no=?", String.class, no))
                .contains("ACTIVE>SUSPENDED");
        assertThat(jdbc.queryForList("SELECT reason FROM pay_merchant_status_log l JOIN pay_merchant m"
                + " ON m.id = l.pay_merchant_id WHERE m.pay_merchant_no=?", String.class, no))
                .anySatisfy(r -> assertThat(r).contains("AGGREGATED").contains("DIRECT"));
    }

    @Test
    @DisplayName("★★ 开关关着：旧路径照走，新表一行不写")
    void switchOffWritesNothing() {
        ReflectionTestUtils.setField(mirror, "enabled", false);
        payments.refresh(e, "STUB", null);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_merchant WHERE pay_merchant_no=?", Integer.class,
                legacyNo())).isZero();
    }

    @Test
    @DisplayName("★★★ 新表写失败不挡旧路径：开户照样落旧表、调用方拿到结果，由比对（S5）兜底")
    void mirrorFailureDoesNotBlockLegacy() {
        Object real = ReflectionTestUtils.getField(mirror, "runner");
        PayMerchantBackfillRunner broken = Mockito.mock(PayMerchantBackfillRunner.class);
        Mockito.when(broken.sync(Mockito.anyString())).thenThrow(new IllegalStateException("pay 库不可写"));
        ReflectionTestUtils.setField(mirror, "runner", broken);
        try {
            assertThatCode(() -> payments.refresh(e, "STUB", null)).doesNotThrowAnyException();
            assertThat(legacyNo()).isNotBlank();
            assertThat(jdbc.queryForObject("SELECT apply_status FROM mch_payment_merchant WHERE entity_no=? AND store_no=''",
                    String.class, e)).isEqualTo("ACTIVE");
        } finally {
            ReflectionTestUtils.setField(mirror, "runner", real);
        }
    }
}
