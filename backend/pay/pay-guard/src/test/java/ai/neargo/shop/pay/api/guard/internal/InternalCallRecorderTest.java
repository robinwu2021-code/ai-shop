package ai.neargo.shop.pay.api.guard.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.access.entity.PayApiRequestLog;
import ai.neargo.shop.pay.api.guard.internal.port.RecordingPaymentPort;
import ai.neargo.shop.spi.pay.CallSource;
import ai.neargo.shop.spi.pay.CallSources;
import ai.neargo.shop.spi.pay.PaymentPort;
import ai.neargo.shop.spi.pay.PaymentPort.InternalCreatePaymentCmd;
import ai.neargo.shop.spi.pay.PaymentPort.InternalRefundCmd;
import ai.neargo.shop.spi.pay.PaymentPort.PaymentHandle;
import ai.neargo.shop.spi.pay.PaymentPort.RefundHandle;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 内嵌形态的内部调用留痕（ADR-045 G9；开发计划 M2.4）：{@link RecordingPaymentPort} 包住真实现，
 * 每次调用写一行 {@code INTERNAL_PORT} —— 成功、业务拒绝、未预期异常三种都写，异常原样抛回调用方。
 */
class InternalCallRecorderTest {

    private static final CallSources.Origin ORDER_PAY = CallSources.Origin.http("req-pay-1", "POST /mp/orders/{orderNo}/pay",
            "APP_C", "223.104.1.1", "Mozilla/5.0", "U-1");

    private final List<PayApiRequestLog> rows = new ArrayList<>();

    /** 真实现的替身：按需成功或抛 */
    private static final class Fake implements PaymentPort {
        RuntimeException fail;

        @Override
        public PaymentHandle createPayment(InternalCreatePaymentCmd cmd) {
            if (fail != null) {
                throw fail;
            }
            return new PaymentHandle(9L, "PYO-1", "CREATED");
        }

        @Override
        public PaymentHandle queryPayment(String bizType, String payOrderNo, CallSource source) {
            return new PaymentHandle(9L, payOrderNo, "SUCCEEDED");
        }

        @Override
        public PaymentHandle closePayment(String bizType, String outOrderNo, String reason, CallSource source) {
            return null;
        }

        @Override
        public RefundHandle requestRefund(InternalRefundCmd cmd) {
            return new RefundHandle("PYR-1", "DISPATCHED", null);
        }
    }

    private final Fake real = new Fake();
    private final PaymentPort port = new RecordingPaymentPort(real, new InternalCallRecorder(rows::add));

    private static InternalCreatePaymentCmd create() {
        return new InternalCreatePaymentCmd(PaymentPort.FOOD_CHECK, "CHK-1", "CHK-1", 7L, "门店", "主体", "CN", 1990,
                "CNY", "餐费", 0L, "AGGREGATED");
    }

    @Test
    @DisplayName("★★★ 成功：一行 INTERNAL_PORT，来源每个字段落到对应列，业务线按业务类型补，单号金额与支付单关联都在")
    void successWritesOneRow() {
        PaymentHandle h;
        try (var ignored = CallSources.bind(ORDER_PAY)) {
            h = port.createPayment(create());
        }

        assertThat(h.payOrderNo()).isEqualTo("PYO-1");
        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.getEntry()).isEqualTo("INTERNAL_PORT");
            assertThat(r.getApi()).isEqualTo("PaymentPort#createPayment");
            assertThat(r.getPayAction()).isEqualTo("CREATE_ONLINE");
            assertThat(r.getSourceType()).isEqualTo("INTERNAL");
            assertThat(r.getAppNo()).isEqualTo("FOOD");
            assertThat(r.getTraceId()).isEqualTo("req-pay-1");
            assertThat(r.getSourceModule()).isEqualTo("FOOD");
            assertThat(r.getSourceInstance()).isNotBlank();
            assertThat(r.getOriginApi()).isEqualTo("POST /mp/orders/{orderNo}/pay");
            assertThat(r.getOriginClient()).isEqualTo("APP_C");
            assertThat(r.getOriginIp()).isEqualTo("223.104.1.1");
            assertThat(r.getOriginUaDigest()).hasSize(64);
            assertThat(r.getOperatorNo()).isEqualTo("U-1");
            assertThat(r.getBizType()).isEqualTo(PaymentPort.FOOD_CHECK);
            assertThat(r.getBizNo()).isEqualTo("CHK-1");
            assertThat(r.getRequestAmountMinor()).isEqualTo(1990L);
            assertThat(r.getRequestCurrency()).isEqualTo("CNY");
            assertThat(r.getStoreId()).isEqualTo(7L);
            assertThat(r.getPayOrderId()).isEqualTo(9L);
            assertThat(r.getOutcome()).isEqualTo("ACCEPTED");
            assertThat(r.getResultCode()).isEqualTo("OK");
            assertThat(r.getClientIp()).as("进程内调用没有对端").isNull();
            assertThat(r.getHttpStatus()).isNull();
            assertThat(r.getElapsedMs()).isNotNull();
        });
    }

    @Test
    @DisplayName("★★★ 业务拒绝：照样写一行（结果码 = 错误码），异常原样抛回调用方")
    void businessRejectionIsRecordedAndRethrown() {
        real.fail = BizException.of(ErrorCode.PAY_FORBIDDEN);

        assertThatThrownBy(() -> port.createPayment(create())).isSameAs(real.fail);
        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.getOutcome()).isEqualTo("REJECTED_PARAM");
            assertThat(r.getResultCode()).isEqualTo("PAY_FORBIDDEN");
        });
    }

    @Test
    @DisplayName("★★★ 未预期异常：写一行 ERROR（结果码 = 异常类名），异常原样抛回")
    void unexpectedFailureIsRecordedAndRethrown() {
        real.fail = new IllegalStateException("db down");

        assertThatThrownBy(() -> port.createPayment(create())).isSameAs(real.fail);
        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.getOutcome()).isEqualTo("ERROR");
            assertThat(r.getResultCode()).isEqualTo("IllegalStateException");
        });
    }

    @Test
    @DisplayName("★★ 查单 / 关单 / 退款也各写一行；退款的操作人取命令里的商家操作员")
    void otherMethodsAreRecorded() {
        CallSource job = CallSources.asJob("pay-order-compensation", CallSources::current);
        port.queryPayment(PaymentPort.MALL_ORDER, "PYO-1", job);
        port.closePayment(PaymentPort.MALL_ORDER, "SO-1", "TIMEOUT");
        port.requestRefund(new InternalRefundCmd(PaymentPort.MALL_ORDER, "PYO-1", "RF-1", "AS-1", 500, "七天", "OP-9"));

        assertThat(rows).extracting(PayApiRequestLog::getPayAction).containsExactly("QUERY", "CLOSE", "REQUEST_REFUND");
        assertThat(rows.get(0).getOriginClient()).isEqualTo(CallSources.JOB);
        assertThat(rows.get(0).getBizNo()).isEqualTo("PYO-1");
        assertThat(rows.get(1).getBizNo()).isEqualTo("SO-1");
        assertThat(rows.get(2).getOperatorNo()).isEqualTo("OP-9");
        assertThat(rows.get(2).getBizNo()).isEqualTo("RF-1");
    }

    @Test
    @DisplayName("★★ 记录本身失败（写库抛）不影响调用")
    void recordingFailureDoesNotBreakTheCall() {
        PaymentPort broken = new RecordingPaymentPort(real, new InternalCallRecorder(r -> {
            throw new IllegalStateException("log table gone");
        }));
        assertThat(broken.createPayment(create()).payOrderNo()).isEqualTo("PYO-1");
    }
}
