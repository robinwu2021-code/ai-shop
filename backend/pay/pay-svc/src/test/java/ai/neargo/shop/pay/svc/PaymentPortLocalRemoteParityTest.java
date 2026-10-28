package ai.neargo.shop.pay.svc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.access.PayCaller;
import ai.neargo.shop.pay.acquiring.entity.PayOrder;
import ai.neargo.shop.pay.acquiring.entity.PayRefund;
import ai.neargo.shop.pay.acquiring.port.InternalCallers;
import ai.neargo.shop.pay.acquiring.port.PaymentPortImpl;
import ai.neargo.shop.pay.acquiring.service.PaymentService;
import ai.neargo.shop.pay.acquiring.service.RefundService;
import ai.neargo.shop.pay.channel.api.CallerType;
import ai.neargo.shop.pay.client.PaymentInternalApi;
import ai.neargo.shop.pay.client.PaymentInternalPaths;
import ai.neargo.shop.pay.client.port.RemotePaymentPort;
import ai.neargo.shop.spi.pay.CallSource;
import ai.neargo.shop.spi.pay.CallSourceCodec;
import ai.neargo.shop.spi.pay.CallSources;
import ai.neargo.shop.spi.pay.InternalHeaders;
import ai.neargo.shop.spi.pay.PaymentPort;
import ai.neargo.shop.spi.pay.PaymentPort.InternalCreatePaymentCmd;
import ai.neargo.shop.spi.pay.PaymentPort.InternalRefundCmd;
import ai.neargo.shop.svc.ConfigServiceLocator;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.ServiceName;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * AC9：内嵌（{@link PaymentPortImpl}）与独立（{@link RemotePaymentPort} → pay-svc）对同一输入结果一致；
 * 按调用方授权（TDD-支付服务-I3支付单退款流水 S8）。领域服务换替身（svctest 没有表），这里验的是边界：
 * 字段、业务拒绝原样过边界、调用方鉴权与越权。
 */
@SpringBootTest(classes = PayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"shop.services.internal-token=tk",
                // sha256("mall-secret") / sha256("food-secret")
                "shop.pay.internal-callers.MALL=623d559372a2199cd8b42b3c19735280947a3fc2803be679db7e86c9815ce2c9",
                "shop.pay.internal-callers.FOOD=95e0791892cd9343d8207f8a2e0c15127df7a524e4bef31ad02ee8064c46d2e4"})
@ActiveProfiles("svctest")
@Import(PaymentPortLocalRemoteParityTest.SeeHeaders.class)
@DisplayName("AC9 · PaymentPortLocalRemoteParityTest")
class PaymentPortLocalRemoteParityTest {

    @LocalServerPort private int port;
    @MockitoBean private PaymentService paymentService;
    @MockitoBean private RefundService refundService;
    @MockitoBean private InternalCallers callers;

    private PaymentPort local;
    private PaymentPort remote;

    private PaymentInternalApi api() {
        var locator = new ConfigServiceLocator();
        locator.getTargets().put(ServiceName.PAY, "http://127.0.0.1:" + port);
        return new InternalHttp(locator, "tk").client(ServiceName.PAY, PaymentInternalApi.class, Duration.ofSeconds(5));
    }

    @BeforeEach
    void ports() {
        local = new PaymentPortImpl(paymentService, refundService, callers, true);
        remote = new RemotePaymentPort(api(), Map.of("MALL", "mall-secret", "FOOD", "food-secret"), ServiceName.PAY);
        var caller = new PayCaller(CallerType.INTERNAL, 1L, Set.of(7L), null, null, null);
        when(callers.system(anyString(), any())).thenReturn(caller);
        when(callers.appId(anyString())).thenReturn(1L);
        when(callers.merchantOperator(anyString(), any(), anyString())).thenAnswer(inv -> {
            String op = inv.getArgument(2);
            if (op == null || op.isBlank()) {
                throw BizException.of(ErrorCode.PAY_REFUND_OPERATOR_REQUIRED);
            }
            return new PayCaller(CallerType.BIZ, 1L, Set.of(7L), op, null, null);
        });
    }

    private static PayOrder order() {
        PayOrder o = new PayOrder();
        o.setId(9L);
        o.setPayOrderNo("PYO-1");
        o.setStatus("CREATED");
        o.setStoreId(7L);
        o.setBizType(PaymentPort.MALL_ORDER);
        return o;
    }

    private static InternalCreatePaymentCmd create() {
        return new InternalCreatePaymentCmd(PaymentPort.MALL_ORDER, "O1", "O1", 7L, "门店", "主体", "CN", 1990, "CNY",
                "goods", 1_800_000_000_000L, "AGGREGATED");
    }

    @Test
    @DisplayName("★★★ 建单、退款：两种实现返回同一个结果")
    void sameResults() {
        when(paymentService.create(any(), any())).thenReturn(order());
        assertThat(remote.createPayment(create())).isEqualTo(local.createPayment(create()));

        PayRefund r = new PayRefund();
        r.setPayRefundNo("PYR-1");
        r.setStatus("DISPATCHED");
        when(paymentService.find("PYO-1")).thenReturn(order());
        when(refundService.request(any(), any())).thenReturn(r);
        var cmd = new InternalRefundCmd(PaymentPort.MALL_ORDER, "PYO-1", "RF-1", "AS-1", 500, "七天", "OP-1");
        assertThat(remote.requestRefund(cmd)).isEqualTo(local.requestRefund(cmd))
                .extracting(PaymentPort.RefundHandle::status).isEqualTo("DISPATCHED");
    }

    @Test
    void businessCancellationKeepsItsMeaningAcrossHttp() {
        PayOrder current = order();
        current.setStatus("AWAITING_PAYMENT");
        when(paymentService.findByOutOrderNo(1L, "O1")).thenReturn(current);
        when(paymentService.cancel(any(), anyString(), anyString())).thenReturn(current);
        assertThat(remote.cancelPayment(PaymentPort.MALL_ORDER, "O1", "business closed"))
                .isEqualTo(local.cancelPayment(PaymentPort.MALL_ORDER, "O1", "business closed"));
        verify(paymentService, never()).close(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("★★★ 业务拒绝原样过边界：可退余额不足、没有操作员 —— 两边抛同一个错误码与参数")
    void sameRejections() {
        when(paymentService.find("PYO-1")).thenReturn(order());
        when(refundService.request(any(), any())).thenThrow(BizException.of(ErrorCode.PAY_REFUND_EXCEEDS, 490L));
        var cmd = new InternalRefundCmd(PaymentPort.MALL_ORDER, "PYO-1", "RF-2", "AS-2", 600, "七天", "OP-1");
        assertThatThrownBy(() -> local.requestRefund(cmd)).extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.PAY_REFUND_EXCEEDS);
        assertThatThrownBy(() -> remote.requestRefund(cmd)).isInstanceOf(BizException.class)
                .satisfies(e -> {
                    assertThat(((BizException) e).errorCode()).isEqualTo(ErrorCode.PAY_REFUND_EXCEEDS);
                    assertThat(((BizException) e).args()).containsExactly("490");
                });
        var anonymous = new InternalRefundCmd(PaymentPort.MALL_ORDER, "PYO-1", "RF-3", "AS-3", 1, "七天", " ");
        assertThatThrownBy(() -> remote.requestRefund(anonymous)).extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.PAY_REFUND_OPERATOR_REQUIRED);
    }

    @Test
    @DisplayName("★★★ 餐饮调商城的退款 → 403；凭据不对 / 没带：内部调用只识别不校验（G9，M2.4），照样进端点")
    void callerAuthorization() {
        var rest = RestClient.create("http://127.0.0.1:" + port);
        String body = "{\"bizType\":\"MALL_ORDER\",\"payOrderNo\":\"PYO-1\",\"outRefundNo\":\"RF-9\",\"amountMinor\":1,"
                + "\"reason\":\"x\",\"operatorNo\":\"OP-1\"}";
        assertThat(status(rest, "FOOD", "food-secret", body)).isEqualTo(403);
        when(paymentService.find("PYO-1")).thenReturn(order());
        PayRefund r = new PayRefund();
        r.setPayRefundNo("PYR-9");
        r.setStatus("DISPATCHED");
        when(refundService.request(any(), any())).thenReturn(r);
        assertThat(status(rest, "MALL", "wrong", body)).as("凭据错不再 401：verify-credential 默认关").isEqualTo(200);
        assertThat(status(rest, null, null, body)).as("没带调用方码：记 UNKNOWN，端点按业务类型拒").isEqualTo(403);
        when(paymentService.create(any(), any())).thenReturn(order());
        assertThat(new RemotePaymentPort(api(), Map.of("MALL", "wrong"), ServiceName.PAY).createPayment(create()))
                .isNotNull();
    }

    /** 服务端实际收到的来源头与请求体（M2.4 之前 pay-svc 还不读它，这里先在边界上看） */
    record Seen(String source, String body) {
    }

    static final List<Seen> SEEN = new CopyOnWriteArrayList<>();

    @TestConfiguration
    static class SeeHeaders {
        @Bean
        OncePerRequestFilter seeCallSource() {
            return new OncePerRequestFilter() {
                @Override
                protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                        throws ServletException, IOException {
                    var cached = new org.springframework.web.util.ContentCachingRequestWrapper(req, 1 << 16);
                    try {
                        chain.doFilter(cached, res);
                    } finally {
                        SEEN.add(new Seen(req.getHeader(InternalHeaders.CALL_SOURCE),
                                new String(cached.getContentAsByteArray(), java.nio.charset.StandardCharsets.UTF_8)));
                    }
                }
            };
        }
    }

    @Test
    @DisplayName("★★★ 来源过边界（M2.3）：命令里的来源编进 X-Call-Source 头、不进请求体；查单 / 关单显式传的来源原样到达")
    void sourceTravelsInTheHeader() {
        when(paymentService.create(any(), any())).thenReturn(order());
        when(paymentService.find("PYO-1")).thenReturn(order());
        when(paymentService.query(any(), anyString())).thenReturn(order());
        SEEN.clear();
        try (var ignored = CallSources.bind(CallSources.Origin.http("req-parity-1", "POST /mp/orders/{orderNo}/pay",
                "APP_C", "223.104.1.1", null, "U-1"))) {
            remote.createPayment(create());
        }
        CallSource job = CallSources.asJob("pay-order-compensation", CallSources::current);
        remote.queryPayment(PaymentPort.MALL_ORDER, "PYO-1", job);

        assertThat(SEEN).hasSize(2);
        CallSource created = CallSourceCodec.decode(SEEN.get(0).source()).orElseThrow();
        assertThat(created.traceId()).isEqualTo("req-parity-1");
        assertThat(created.originApi()).isEqualTo("POST /mp/orders/{orderNo}/pay");
        assertThat(SEEN.get(0).body()).contains("\"outOrderNo\":\"O1\"").doesNotContain("source");
        assertThat(CallSourceCodec.decode(SEEN.get(1).source())).contains(job);
    }

    private static int status(RestClient rest, String caller, String credential, String body) {
        return rest.post().uri(PaymentInternalPaths.REFUNDS).contentType(MediaType.APPLICATION_JSON)
                .headers(h -> {
                    if (caller != null) {
                        h.set(PaymentInternalPaths.CALLER, caller);
                        h.set(PaymentInternalPaths.CREDENTIAL, credential);
                    }
                }).body(body).exchange((req, res) -> res.getStatusCode().value());
    }
}
