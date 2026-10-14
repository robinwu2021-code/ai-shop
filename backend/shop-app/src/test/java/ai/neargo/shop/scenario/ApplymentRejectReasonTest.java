package ai.neargo.shop.scenario;

import ai.neargo.shop.merchant.service.MerchantPaymentService;
import ai.neargo.shop.pay.channel.StubApplymentGateway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 驳回必须带一句可读的原因（S17）。
 *
 * <h2>契约写了，但没有任何东西兑现它</h2>
 * {@code ApplymentResult} 的注释写着「<b>驳回必须带原因</b> ——
 * 没有原因商家只能反复重提」。而实现是
 * {@code row.setRejectReason(r.rejectReason())} 一行直传：通道给空，这一列就是 null。
 *
 * <p>b 端那句原因原本是 {@code v-if="p.rejectReason"} 渲染的，于是商家看到的是
 * 一个「已驳回」标签，下面<b>什么都没有</b> —— 不知道哪儿不对，
 * 就只能把同一份资料再提一遍。而通道侧重复进件会产生<b>新的二级商户号</b>，
 * 历史订单的分账仍指向旧号，那是对不上账的开始。
 *
 * <h2>这一组第一版是假的</h2>
 * 第一版在测试里把兜底逻辑<b>抄了一遍</b>再断言 —— 删掉生产代码它照样绿。
 * 现在走的是真实的 {@code refresh} 链路，靠桩上的测试钩子造出
 * 「驳回却不给原因」这种<b>违反契约的通道回执</b>（正常的桩造不出来）。
 *
 * <h2>为什么没做码 → 中文的映射表</h2>
 * 设计册的 S17 是「驳回原因的码 → 中文说明 + 怎么改」。
 * 而<b>今天没有任何通道在产出码</b>：真通道一个都没接（S3 卡在凭证），
 * 桩返回的本来就是中文。现在写映射表等于凭空发明码值 ——
 * 微信给的是「字段名 + 中文描述」，支付宝给的是 {@code sub_code}，形状都不一样，
 * 猜出来的表接上真通道时一条都对不上。
 */
@SpringBootTest
@ActiveProfiles("test")
class ApplymentRejectReasonTest {

    private static final String ENTITY = "M-REJ-REASON";
    private static final String NAME_NO_REASON = "驳回不给原因的店";
    private static final String NAME_WITH_REASON = "驳回并说明原因的店";

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MerchantPaymentService paymentService;
    @Autowired
    private StubApplymentGateway stub;

    @AfterEach
    void clean() {
        stub.clearTestHooks();
        jdbc.update("DELETE FROM mch_payment_merchant WHERE entity_no = ?", ENTITY);
        jdbc.update("DELETE FROM mch_store WHERE entity_no = ?", ENTITY);
        jdbc.update("DELETE FROM mch_entity WHERE entity_no = ?", ENTITY);
    }

    private void fixture(String name) {
        jdbc.update("INSERT INTO mch_entity (entity_no, name, created_at, updated_at)"
                + " VALUES (?,?,NOW(),NOW())", ENTITY, name);
        jdbc.update("INSERT INTO mch_payment_merchant (entity_no, store_no, pay_channel,"
                        + " legal_form, apply_status, created_at, updated_at)"
                        + " VALUES (?,?,?,?,?,NOW(),NOW())",
                ENTITY, "", "STUB", "MICRO", "NONE");
    }

    private MerchantPaymentService.SubmitCommand cmd() {
        return new MerchantPaymentService.SubmitCommand("STUB", "PERSONAL",
                "6222020000999977776", java.util.List.of("ID_CARD_FRONT", "ID_CARD_BACK"),
                "测试联系人", "13900000001", "");
    }

    private String storedReason() {
        return jdbc.queryForObject(
                "SELECT reject_reason FROM mch_payment_merchant WHERE entity_no = ?",
                String.class, ENTITY);
    }

    @Test
    @DisplayName("★★★ 通道驳回不给原因时，落库的仍是一句可读的话 —— 空白等于让商家反复重提")
    void blankReasonGetsReadableFallback() {
        fixture(NAME_NO_REASON);
        stub.rejectWithoutReasonFor(NAME_NO_REASON);

        var vo = paymentService.submit(ENTITY, cmd());

        assertThat(vo.applyStatus()).as("前置：这一单要真的走到驳回").isEqualTo("REJECTED");
        assertThat(storedReason())
                .as("驳回原因是空的 —— b 端那一段整块消失，"
                        + "商家看到「已驳回」下面什么都没有")
                .isNotBlank();
        assertThat(storedReason())
                .as("兜底要明确劝阻重复提交：重复进件会产生新的收款商户号，"
                        + "而历史订单的分账仍指向旧号")
                .contains("不要重复提交");
        assertThat(storedReason())
                .as("**不许编一个原因** —— 编了他会去改一个本来没错的地方，再提一次，再被驳回")
                .contains("通道未说明");
    }

    @Test
    @DisplayName("★★★ 通道给了原因就原样落库 —— 兜底盖掉通道说的话，比没有兜底更糟")
    void channelReasonIsKeptVerbatim() {
        fixture(NAME_WITH_REASON);

        var vo = paymentService.submit(ENTITY, cmd());

        assertThat(vo.applyStatus()).as("前置：主体名带「驳回」二字，桩会驳回它").isEqualTo("REJECTED");
        assertThat(storedReason())
                .as("兜底把通道给的具体原因盖掉了")
                .contains("结算账户与主体名称不一致")
                .doesNotContain("通道未说明");
    }

    @Test
    @DisplayName("★★★ 改完重提通过后，上一次的驳回原因要清掉 —— 留着商家会以为自己还没过")
    void approvedClearsPreviousReason() {
        /*
         * ⚠️ 这一条第一版没测到东西，消融时才发现：
         * 夹具是一条全新的记录，reject_reason 本来就是 null ——
         * 于是「清掉」与「不清」结果一样，把 setRejectReason(null) 删掉照样绿。
         *
         * 要测的是**上一次留下的那句话**，所以夹具必须先带着它。
         */
        fixture("正常开通的店");
        jdbc.update("UPDATE mch_payment_merchant SET apply_status = 'REJECTED',"
                + " reject_reason = ? WHERE entity_no = ?", "上次：结算账户名对不上", ENTITY);
        assertThat(storedReason()).as("前置：上一次的原因真的在库里").isNotBlank();

        var vo = paymentService.submit(ENTITY, cmd());

        assertThat(vo.applyStatus()).isEqualTo("ACTIVE");
        assertThat(storedReason())
                .as("开通了还留着上次的驳回原因 —— 页面上「可以收款」下面挂着一句"
                        + "「结算账户名对不上」，商家会以为自己还没过")
                .isNull();
    }
}
