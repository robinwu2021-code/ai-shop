package ai.neargo.shop.pay.channel;

import ai.neargo.shop.spi.pay.PayApplymentGateway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 两家进件网关的**字段映射与状态机**。没有通道凭证也能测的那一半。
 *
 * <p>用一个假 {@link ChannelClient} 把出站报文接下来，断言的是
 * 「我们发出去的那份报文对不对」—— 通道收到之后怎么回应，要凭证联调才知道。
 */
class ApplymentGatewayTest {

    /** 记下最后一次调用，并按脚本返回回执。 */
    static final class RecordingClient implements ChannelClient {
        String api;
        Map<String, Object> body;
        Map<String, Object> reply = Map.of();

        @Override
        public Map<String, Object> post(String api, Map<String, Object> body) {
            this.api = api;
            this.body = body;
            return reply;
        }
    }

    private static PayApplymentGateway.SubmitCommand cmd(String legalForm, String settleType) {
        return new PayApplymentGateway.SubmitCommand(
                "E-1", "老张粮油店", legalForm, "张老板", "13900000000",
                List.of("https://cdn/license.jpg"), settleType, "6222021234567890123");
    }

    // ---------------------------------------------------------------- 微信

    @Test
    @DisplayName("★★★ 微信：主体类型映射只此一处，认不出来**不猜**")
    void wechatSubjectTypeMapping() {
        assertThat(WechatApplymentGateway.subjectType("MICRO")).isEqualTo("SUBJECT_TYPE_MICRO");
        assertThat(WechatApplymentGateway.subjectType("INDIVIDUAL")).isEqualTo("SUBJECT_TYPE_INDIVIDUAL");
        assertThat(WechatApplymentGateway.subjectType("ENTERPRISE")).isEqualTo("SUBJECT_TYPE_ENTERPRISE");
        assertThat(WechatApplymentGateway.subjectType("PERSONAL"))
                .as("旧口径不该被猜成小微 —— 猜错会按错误的主体类型进件").isNull();
    }

    @Test
    @DisplayName("★★★ 微信：认不出的法律形态当场失败，不发出去")
    void wechatRefusesUnknownLegalForm() {
        var c = new RecordingClient();
        var g = new WechatApplymentGateway(c);
        assertThatThrownBy(() -> g.submit(cmd("PERSONAL", "PERSONAL_BANK_CARD")))
                .isInstanceOf(ChannelClient.ChannelException.class);
        assertThat(c.api).as("一个字节都不该发出去").isNull();
    }

    @Test
    @DisplayName("★★★ 微信：拿不到申请单号必须炸 —— 落一个空号进库，那一行永远停在审核中")
    void wechatFailsWhenNoApplyNo() {
        var c = new RecordingClient();
        c.reply = Map.of("code", "OK");   // 没有 applyment_id
        var g = new WechatApplymentGateway(c);
        assertThatThrownBy(() -> g.submit(cmd("MICRO", "PERSONAL_BANK_CARD")))
                .isInstanceOf(ChannelClient.ChannelException.class)
                .hasMessageContaining("申请单号");
    }

    @Test
    @DisplayName("★★★ 微信：NEED_SIGN 不算开好户 —— 当成 ACTIVE 会让页面说「可以收款了」而第一笔就失败")
    void wechatNeedSignIsNotActive() {
        var c = new RecordingClient();
        var g = new WechatApplymentGateway(c);

        c.reply = Map.of("applyment_state", "APPLYMENT_STATE_FINISHED", "sub_mchid", "SUB-1");
        assertThat(g.query("A1").status()).isEqualTo("ACTIVE");

        c.reply = Map.of("applyment_state", "NEED_SIGN");
        assertThat(g.query("A1").status()).as("待签约仍是在途").isEqualTo("APPLYING");

        c.reply = Map.of("applyment_state", "APPLYMENT_STATE_REJECTED", "audit_detail", "执照照片模糊");
        var r = g.query("A1");
        assertThat(r.status()).isEqualTo("REJECTED");
        assertThat(r.rejectReason()).as("驳回必须带原因，否则商家只能反复重提").isEqualTo("执照照片模糊");
    }

    // -------------------------------------------------------------- 支付宝

    @Test
    @DisplayName("★★★ 支付宝：商户类型是数字码，与微信取值完全不同 —— 两份映射各自写死")
    void alipayMerchantTypeMapping() {
        assertThat(AlipayApplymentGateway.merchantType("ENTERPRISE")).isEqualTo("01");
        assertThat(AlipayApplymentGateway.merchantType("INDIVIDUAL")).isEqualTo("02");
        assertThat(AlipayApplymentGateway.merchantType("MICRO")).isEqualTo("03");
        assertThat(AlipayApplymentGateway.merchantType("COMPANY")).isNull();
    }

    @Test
    @DisplayName("★★★ 支付宝：结算卡进 biz_cards 数组，对公/个人码不同")
    void alipayBuildsSettleCard() {
        var c = new RecordingClient();
        c.reply = Map.of("order_id", "ORD-1");
        var g = new AlipayApplymentGateway(c);
        g.submit(cmd("MICRO", "PERSONAL_BANK_CARD"));

        assertThat(c.api).isEqualTo(AlipayApis.SUB_MERCHANT_CREATE);
        assertThat(c.body.get("merchant_type")).isEqualTo("03");
        List<?> cards = (List<?>) c.body.get("biz_cards");
        assertThat(cards).hasSize(1);
        Map<?, ?> card = (Map<?, ?>) cards.get(0);
        assertThat(card.get("account_no")).isEqualTo("6222021234567890123");
        assertThat(card.get("account_type")).as("个人卡是 2").isEqualTo("2");
    }

    @Test
    @DisplayName("★★★ 支付宝：查询走**另一个接口名** —— 拿创建接口带参数查会开出第二个二级商户号")
    void alipayQueryUsesDifferentApi() {
        var c = new RecordingClient();
        c.reply = Map.of("apply_status", "99", "sub_merchant_id", "SM-1");
        var g = new AlipayApplymentGateway(c);

        assertThat(g.query("ORD-1").status()).isEqualTo("ACTIVE");
        assertThat(c.api).isEqualTo(AlipayApis.SUB_MERCHANT_QUERY);
        assertThat(c.api).isNotEqualTo(AlipayApis.SUB_MERCHANT_CREATE);
    }

    // ------------------------------------------------------------ 两家共同

    @Test
    @DisplayName("★★★ 结算账号进了报文、但不进日志 —— 明文账号只在这一次调用里存在")
    void settleAccountNeverReachesLog() {
        var logged = new ArrayList<String>();
        var appender = new ch.qos.logback.core.AppenderBase<ch.qos.logback.classic.spi.ILoggingEvent>() {
            @Override
            protected void append(ch.qos.logback.classic.spi.ILoggingEvent e) {
                logged.add(e.getFormattedMessage());
            }
        };
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
                "ai.neargo.shop.pay.channel.base.AbstractApplymentGateway");
        appender.start();
        logger.addAppender(appender);
        try {
            var c = new RecordingClient();
            c.reply = Map.of("applyment_id", "AP-1");
            new WechatApplymentGateway(c).submit(cmd("MICRO", "PERSONAL_BANK_CARD"));

            assertThat(c.body.toString()).as("报文里当然要有").contains("6222021234567890123");
            assertThat(logged).isNotEmpty();
            assertThat(String.join("\n", logged))
                    .as("日志里一个字都不能有 —— 日志会被采集、转发、留存")
                    .doesNotContain("6222021234567890123")
                    .doesNotContain("张老板");
        } finally {
            logger.detachAppender(appender);
        }
    }
}
