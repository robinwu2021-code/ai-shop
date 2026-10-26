package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.channel.StubApplymentGateway;
import ai.neargo.shop.pay.onboarding.service.OnboardingService;
import ai.neargo.shop.pay.onboarding.service.OnboardingService.Materials;
import ai.neargo.shop.paybridge.PayMerchantBackfillRunner;
import ai.neargo.shop.spi.pay.PayApplymentGateway;
import ai.neargo.shop.spi.pay.PayMerchantQueryPort;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * AC8：进件迁入支付（TDD-支付服务-I4进件与收款商户 S7）。走真的 {@code StubApplymentGateway}：提交过的申请单回 ACTIVE，
 * 主体名带「驳回」回 REJECTED。同门店同渠道在途只有一张（R06）；每次提交外部单号不同（R04）；驳回原因留在那一版、
 * 重提后当前原因清空（R23）；通过即产出收款商户并绑上门店；轮询只推进支付自己的进件单，回填来的 LEGACY 单归旧流程。
 */
// 支付服务 I1–I4 的场景共用一个 H2 库与上下文（见 TDD-支付服务-I3 偏差 15）
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:pay-i3;MODE=MySQL;"
        + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class PayOnboardingFlowTest {

    private static final String ACCOUNT = "6222020200112233445";

    @Autowired private OnboardingService onboardings;
    @Autowired private PayMerchantQueryPort routes;
    @Autowired private PayMerchantBackfillRunner backfill;
    @Autowired private StubApplymentGateway stub;
    @Autowired private JdbcTemplate jdbc;

    private String e;
    private String s;

    private String id() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private void world(String entityName) {
        e = "E-ONB-" + id();
        jdbc.update("INSERT INTO mch_entity (entity_no, name, market, funds_mode, created_at, updated_at)"
                + " VALUES (?, ?, 'CN', 'DIRECT', NOW(), NOW())", e, entityName);
        s = "S-ONB-" + id();
        jdbc.update("INSERT INTO mch_store (entity_no, store_no, name, is_default, created_at, updated_at)"
                + " VALUES (?, ?, ?, 1, NOW(), NOW())", e, s, "门店 " + s);
    }

    @BeforeEach
    void defaultWorld() {
        world("进件 " + UUID.randomUUID());
    }

    @AfterEach
    void hooks() {
        stub.clearTestHooks();
    }

    private Long storeId() {
        return jdbc.queryForObject("SELECT id FROM mch_store WHERE store_no=?", Long.class, s);
    }

    private Materials materials() {
        return new Materials("INDIVIDUAL", "张三", "13800138000", List.of("LIC-1"), "PERSONAL_BANK", ACCOUNT);
    }

    private Map<String, Object> onboarding(String no) {
        return jdbc.queryForMap("SELECT * FROM pay_onboarding WHERE onboarding_no=?", no);
    }

    private Map<String, Object> version(String no, int index) {
        return jdbc.queryForMap("SELECT v.* FROM pay_onboarding_version v JOIN pay_onboarding o ON o.id = v.pay_onboarding_id"
                + " WHERE o.onboarding_no=? AND v.version_index=?", no, index);
    }

    @Test
    @DisplayName("★★★ 提交 → 审核中（在途键、v1 外部单号、资料一字段一行、结算账号不落明文）→ 回查通过：产出收款商户并绑上门店")
    void submitThenApprove() {
        String no = onboardings.open(storeId(), "STUB", "BIZ", "U-1");
        onboardings.submit(no, materials(), "U-1");

        assertThat(onboarding(no)).containsEntry("status", "CHANNEL_REVIEW").containsEntry("inflight_key", storeId() + "|STUB");
        assertThat(version(no, 1)).containsEntry("out_request_no", no + "-V1").containsEntry("status", "CHANNEL_REVIEW");
        assertThat(jdbc.queryForList("SELECT m.field_code FROM pay_onboarding_material m JOIN pay_onboarding_version v"
                + " ON v.id = m.pay_onboarding_version_id JOIN pay_onboarding o ON o.id = v.pay_onboarding_id"
                + " WHERE o.onboarding_no=?", String.class, no)).contains("CONTACT_NAME", "CONTACT_MOBILE", "SETTLE_ACCOUNT_MASKED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_onboarding_material WHERE value_text LIKE ?"
                + " OR value_masked LIKE ?", Integer.class, "%" + ACCOUNT + "%", "%" + ACCOUNT + "%")).isZero();

        onboardings.refresh(no);

        Map<String, Object> o = onboarding(no);
        assertThat(o).containsEntry("status", "APPROVED").containsEntry("inflight_key", null);
        assertThat(o.get("pay_merchant_id")).isNotNull();
        assertThat(version(no, 1)).containsEntry("status", "APPROVED");
        assertThat(jdbc.queryForMap("SELECT status, owner_type, store_id, pay_onboarding_id FROM pay_merchant WHERE id=?",
                o.get("pay_merchant_id"))).containsEntry("status", "ACTIVE").containsEntry("owner_type", "STORE")
                .containsEntry("store_id", storeId()).containsEntry("pay_onboarding_id", o.get("id"));
        assertThat(routes.routeOf(storeId(), "STUB")).get().extracting(PayMerchantQueryPort.PayRoute::payMerchantId)
                .isEqualTo(((Number) o.get("pay_merchant_id")).longValue());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_merchant_status_log WHERE pay_merchant_id=?", Integer.class,
                o.get("pay_merchant_id"))).isEqualTo(1);
    }

    @Test
    @DisplayName("★★★ R06：同门店同渠道在途只有一张 —— 第二张提交被拒、不建版本、仍是草稿")
    void onlyOneInflight() {
        String first = onboardings.open(storeId(), "STUB", "BIZ", "U-1");
        String second = onboardings.open(storeId(), "STUB", "OPS", "OP-1");
        onboardings.submit(first, materials(), "U-1");

        assertThatThrownBy(() -> onboardings.submit(second, materials(), "OP-1"))
                .isInstanceOf(BizException.class).extracting("errorCode").isEqualTo(ErrorCode.PAY_ONBOARDING_INFLIGHT);
        assertThat(onboarding(second)).containsEntry("status", "DRAFT").containsEntry("inflight_key", null);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_onboarding_version WHERE pay_onboarding_id=?",
                Integer.class, onboarding(second).get("id"))).isZero();
    }

    @Test
    @DisplayName("★★★ R23 + R04：驳回原因留在 v1；重提另起 v2、外部单号不同、当前原因清空、在途键重新占上")
    void rejectedThenResubmitted() {
        world("驳回 " + UUID.randomUUID());
        String no = onboardings.open(storeId(), "STUB", "BIZ", "U-1");
        onboardings.submit(no, materials(), "U-1");
        onboardings.refresh(no);
        assertThat(onboarding(no)).containsEntry("status", "REJECTED").containsEntry("inflight_key", null);
        Object reason = onboarding(no).get("reject_reason");
        assertThat(reason).isNotNull();

        onboardings.submit(no, materials(), "U-1");

        Map<String, Object> v1 = version(no, 1);
        Map<String, Object> v2 = version(no, 2);
        assertThat(v1).containsEntry("reject_reason", reason).containsEntry("status", "SUPERSEDED");
        assertThat(v2.get("out_request_no")).isEqualTo(no + "-V2").isNotEqualTo(v1.get("out_request_no"));
        assertThat(v2.get("channel_apply_no")).isNotEqualTo(v1.get("channel_apply_no"));
        assertThat(onboarding(no)).containsEntry("reject_reason", null).containsEntry("current_version", 2)
                .containsEntry("status", "CHANNEL_REVIEW").containsEntry("inflight_key", storeId() + "|STUB");
    }

    @Test
    @DisplayName("★★ 通道驳回却不给原因：如实写「通道未说明」，不留空")
    void missingReasonFallsBack() {
        String name = "无因 " + UUID.randomUUID();
        world(name);
        stub.rejectWithoutReasonFor(name);
        String no = onboardings.open(storeId(), "STUB", "BIZ", "U-1");
        onboardings.submit(no, materials(), "U-1");
        onboardings.refresh(no);
        assertThat((String) onboarding(no).get("reject_reason")).contains("通道未说明");
    }

    @Test
    @DisplayName("★★★ 轮询只推进支付自己的进件单：回填来的 LEGACY 单归旧流程，不重复开户；手动回查也拒绝")
    void pollLeavesLegacyOnboardingsAlone() {
        String applyNo = stub.submit(new PayApplymentGateway.SubmitCommand(e, "进件", "INDIVIDUAL", "张三", "13800138000",
                List.of(), "PERSONAL_BANK", ACCOUNT, "", e + "-E-1"));
        jdbc.update("INSERT INTO mch_payment_merchant (entity_no, store_no, pay_channel, apply_status, channel_apply_no,"
                + " created_at, updated_at) VALUES (?, '', 'STUB', 'APPLYING', ?, NOW(), NOW())", e, applyNo);
        backfill.run(List.of(e));
        String legacyNo = jdbc.queryForObject("SELECT o.onboarding_no FROM pay_onboarding o JOIN mch_payment_merchant p"
                + " ON o.onboarding_no = CONCAT('LEGACY-', p.id) WHERE p.entity_no=?", String.class, e);
        assertThat(onboarding(legacyNo)).containsEntry("inflight_key", storeId() + "|STUB");
        String s2 = "S-ONB-" + id();
        jdbc.update("INSERT INTO mch_store (entity_no, store_no, name, is_default, created_at, updated_at)"
                + " VALUES (?, ?, ?, 0, NOW(), NOW())", e, s2, "门店 " + s2);
        Long s2Id = jdbc.queryForObject("SELECT id FROM mch_store WHERE store_no=?", Long.class, s2);
        String no = onboardings.open(s2Id, "STUB", "BIZ", "U-1");
        onboardings.submit(no, materials(), "U-1");

        onboardings.poll(500);

        assertThat(onboarding(no)).containsEntry("status", "APPROVED");
        assertThat(onboarding(legacyNo)).containsEntry("status", "CHANNEL_REVIEW").containsEntry("pay_merchant_id", null);
        assertThatThrownBy(() -> onboardings.refresh(legacyNo)).isInstanceOf(BizException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PAY_ONBOARDING_STATE);
    }
}
