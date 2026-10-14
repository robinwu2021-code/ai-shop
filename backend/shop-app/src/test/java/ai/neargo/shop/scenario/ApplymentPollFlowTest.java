package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.merchant.service.MerchantPaymentService;
import ai.neargo.shop.spi.pay.PayApplymentGateway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 进件状态轮询。
 *
 * <p>今天进件状态<b>只有商家自己点「刷新」才会推进</b> —— 没有回调、没有轮询。
 * 商家不点，单子就一直显示「审核中」，而通道那边可能三天前就批了。
 *
 * <p>走的是真的 {@code StubApplymentGateway}：它对提交过的单号回 ACTIVE、
 * 对不认识的单号回 APPLYING —— 正好是「批了」与「还在审」这两种真实形态。
 * 用替身把 query 写死的话，这个类就只在验我自己写的替身。
 */
@SpringBootTest
@ActiveProfiles("test")
class ApplymentPollFlowTest {

    private static final String ENTITY = "M-POLL-T1";
    private static final long DAY = 86400000L;

    @Autowired
    private MerchantPaymentService paymentService;
    @Autowired
    private List<PayApplymentGateway> gateways;
    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        DataScopeContext.executeWithoutScope(() -> {
            jdbc.update("DELETE FROM mch_payment_merchant WHERE entity_no LIKE 'M-POLL-%'");
            return null;
        });
    }

    /** 造一条「审核中」的进件档案 */
    private void givenApplying(String suffix, String channelApplyNo, long appliedAt) {
        givenApplyingFor(ENTITY, suffix, channelApplyNo, appliedAt);
    }

    /**
     * ⚠️ <b>一个主体在一个通道的一个门店只能有一行</b>（`uk_mp_entity_channel_store`）。
     * 所以要造多条待轮询的档案，只能换主体 —— 写这条用例时才发现这个约束。
     */
    private void givenApplyingFor(String entityNo, String suffix,
                                  String channelApplyNo, long appliedAt) {
        DataScopeContext.executeWithoutScope(() -> jdbc.update(
                "INSERT INTO mch_payment_merchant (pay_merchant_no, entity_no, store_no,"
                        + " pay_channel, apply_status, channel_apply_no, applied_at,"
                        + " tenant_no, created_at, updated_at, version, deleted)"
                        + " VALUES (?, ?, '', 'STUB', 'APPLYING', ?, ?, 'MAIN',"
                        + " CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 0)",
                "PM-POLL-" + suffix, entityNo, channelApplyNo, appliedAt));
    }

    private String statusOf() {
        return DataScopeContext.executeWithoutScope(() -> jdbc.queryForObject(
                "SELECT apply_status FROM mch_payment_merchant WHERE entity_no = ?",
                String.class, ENTITY));
    }

    /** 走真网关提交一次，拿到它认得的申请单号 —— 之后 query 会回 ACTIVE */
    private String submitToStub() {
        PayApplymentGateway stub = gateways.stream()
                .filter(g -> "STUB".equals(g.payChannel()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("测试要的 STUB 进件网关不在"));
        return stub.submit(new PayApplymentGateway.SubmitCommand(
                ENTITY, "轮询测试店", "INDIVIDUAL",
                "张三", "13800138000", List.of(),
                "PERSONAL_BANK", "6222000000000000"));
    }

    @Test
    @DisplayName("★★★ 通道批了就自动推进 —— 不靠商家去点「刷新」")
    void pollSettlesApprovedApplyment() {
        givenApplying("ok", submitToStub(), System.currentTimeMillis() - DAY);

        MerchantPaymentService.PollResult r = paymentService.pollApplying(50, 3 * DAY);

        assertThat(r.scanned()).isEqualTo(1);
        assertThat(r.settled()).as("通道那边已经批了，这一轮就该出结果").isEqualTo(1);
        assertThat(statusOf()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("★★★ 还在审的不算「出结果」—— 与「查询失败」分开计，两者要查的地方完全不同")
    void stillApplyingIsNotSettled() {
        // 通道不认识这个单号 → 回 APPLYING，正是「还在审」的形态
        givenApplying("wait", "APPLY-UNKNOWN-1", System.currentTimeMillis() - DAY);

        MerchantPaymentService.PollResult r = paymentService.pollApplying(50, 3 * DAY);

        assertThat(r.scanned()).isEqualTo(1);
        assertThat(r.settled()).isZero();
        assertThat(r.failed()).as("还在审不是失败").isZero();
        assertThat(statusOf()).isEqualTo("APPLYING");
    }

    @Test
    @DisplayName("★★★ 卡太久要单独计数 —— 没有它，一单卡三个月也没有任何地方会说")
    void staleApplymentIsCounted() {
        givenApplying("stale", "APPLY-UNKNOWN-2", System.currentTimeMillis() - 10 * DAY);

        MerchantPaymentService.PollResult r = paymentService.pollApplying(50, 3 * DAY);

        assertThat(r.stale()).as("10 天前提交、阈值 3 天，该报出来").isEqualTo(1);
    }

    @Test
    @DisplayName("★★ 没超期的不计入 stale —— 否则这个数天天非零，等于没有")
    void freshApplymentIsNotStale() {
        givenApplying("fresh", "APPLY-UNKNOWN-3", System.currentTimeMillis() - DAY);

        MerchantPaymentService.PollResult r = paymentService.pollApplying(50, 3 * DAY);

        assertThat(r.stale()).isZero();
    }

    @Test
    @DisplayName("★★ 没有提交过的（无申请单号）不去打扰通道 —— 问一个不存在的单号只会拿回噪声")
    void neverSubmittedIsSkipped() {
        DataScopeContext.executeWithoutScope(() -> jdbc.update(
                "INSERT INTO mch_payment_merchant (pay_merchant_no, entity_no, store_no,"
                        + " pay_channel, apply_status, tenant_no, created_at, updated_at,"
                        + " version, deleted)"
                        + " VALUES (?, ?, '', 'STUB', 'APPLYING', 'MAIN',"
                        + " CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 0)",
                "PM-POLL-none", ENTITY));

        MerchantPaymentService.PollResult r = paymentService.pollApplying(50, 3 * DAY);

        assertThat(r.scanned()).as("channel_apply_no 为空的根本不该进候选").isZero();
    }

    @Test
    @DisplayName("★★ limit 封顶 —— 第一次上线时库里可能积着一批历史 APPLYING，别一口气把通道打满")
    void limitCapsOneRound() {
        long now = System.currentTimeMillis();
        // 三个不同主体：同一主体在同一通道同一门店只能有一行
        givenApplyingFor("M-POLL-A", "a", "APPLY-UNKNOWN-A", now - DAY);
        givenApplyingFor("M-POLL-B", "b", "APPLY-UNKNOWN-B", now - 2 * DAY);
        givenApplyingFor("M-POLL-C", "c", "APPLY-UNKNOWN-C", now - 3 * DAY);

        assertThat(paymentService.pollApplying(2, 30 * DAY).scanned()).isEqualTo(2);
    }
}
