package ai.neargo.shop.spi.pay;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.spi.pay.PaymentPort.InternalCreatePaymentCmd;
import ai.neargo.shop.spi.pay.PaymentPort.InternalRefundCmd;
import ai.neargo.shop.spi.pay.PaymentPort.PaymentHandle;
import ai.neargo.shop.spi.pay.PaymentPort.RefundHandle;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link PaymentPort} 带上来源（开发计划 M2.2；TDD-支付服务-接入守卫模块pay-guard §2.3 A-2）：
 * 现有调用方一行不改也有来源 —— 旧构造器与旧方法都自动取 {@link CallSources#current()}。
 */
class PaymentPortSourceTest {

    private static final CallSources.Origin ORDER_PAY = CallSources.Origin.http("req-pay-1", "POST /mp/orders/{orderNo}/pay",
            "APP_C", "223.104.1.1", null, "U-1");

    /** 只实现新的抽象方法，记下收到的来源 */
    private static final class Recording implements PaymentPort {
        final List<CallSource> sources = new ArrayList<>();

        @Override
        public PaymentHandle createPayment(InternalCreatePaymentCmd cmd) {
            sources.add(cmd.source());
            return null;
        }

        @Override
        public PaymentHandle queryPayment(String bizType, String payOrderNo, CallSource source) {
            sources.add(source);
            return null;
        }

        @Override
        public PaymentHandle closePayment(String bizType, String outOrderNo, String reason, CallSource source) {
            sources.add(source);
            return null;
        }

        @Override
        public RefundHandle requestRefund(InternalRefundCmd cmd) {
            sources.add(cmd.source());
            return null;
        }
    }

    private static InternalCreatePaymentCmd legacyCreate() {
        return new InternalCreatePaymentCmd(PaymentPort.MALL_ORDER, "SO1", "SO1", 1L, "门店", "主体", "CN", 100, "CNY",
                "goods", 0L, "AGGREGATED");
    }

    private static InternalRefundCmd legacyRefund() {
        return new InternalRefundCmd(PaymentPort.MALL_ORDER, "PYO1", "RF1", "AS1", 100, "退货", "U-OP");
    }

    @Test
    @DisplayName("★★★ 旧构造器（现有调用方）：来源自动取当前上下文 —— 建单与退款都带着上游请求")
    void legacyConstructorsCarryCurrentSource() {
        InternalCreatePaymentCmd create;
        InternalRefundCmd refund;
        try (var ignored = CallSources.bind(ORDER_PAY)) {
            create = legacyCreate();
            refund = legacyRefund();
        }

        assertThat(create.source().traceId()).isEqualTo("req-pay-1");
        assertThat(create.source().originApi()).isEqualTo("POST /mp/orders/{orderNo}/pay");
        assertThat(refund.source().operatorNo()).isEqualTo("U-1");
    }

    @Test
    @DisplayName("★★★ 旧的查单 / 关单方法：转调带来源的重载，来源取当前上下文")
    void legacyMethodsDelegateWithCurrentSource() {
        Recording port = new Recording();
        try (var ignored = CallSources.bind(ORDER_PAY)) {
            port.queryPayment(PaymentPort.MALL_ORDER, "PYO1");
            port.closePayment(PaymentPort.MALL_ORDER, "SO1", "TIMEOUT");
        }

        assertThat(port.sources).hasSize(2).allSatisfy(s -> assertThat(s.traceId()).isEqualTo("req-pay-1"));
    }

    @Test
    @DisplayName("★★ 新构造器显式传来源：原样保留，不被当前上下文覆盖")
    void explicitSourceKept() {
        CallSource job = CallSources.asJob("pay-order-compensation", CallSources::current);
        InternalCreatePaymentCmd c = new InternalCreatePaymentCmd(PaymentPort.MALL_ORDER, "SO1", "SO1", 1L, "门店", "主体",
                "CN", 100, "CNY", "goods", 0L, "AGGREGATED", job);

        try (var ignored = CallSources.bind(ORDER_PAY)) {
            assertThat(c.source()).isSameAs(job);
        }
    }

    @Test
    @DisplayName("★★★ 来源不进 HTTP 请求体：体的契约不变，来源走 X-Call-Source 头（M2.3）")
    void sourceIsNotPartOfTheBody() {
        JsonMapper json = JsonMapper.builder().build();
        String body = json.writeValueAsString(legacyCreate());

        assertThat(body).doesNotContain("source").contains("\"outOrderNo\":\"SO1\"");
        assertThat(json.readValue(body, InternalCreatePaymentCmd.class).source()).isNull();
        assertThat(json.writeValueAsString(legacyRefund())).doesNotContain("source");
    }
}
