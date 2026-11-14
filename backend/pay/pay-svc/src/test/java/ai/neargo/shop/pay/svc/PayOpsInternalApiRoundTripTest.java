package ai.neargo.shop.pay.svc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.acquiring.service.PayOpsQueryService.OrderFilter;
import ai.neargo.shop.pay.acquiring.service.PayOpsQueryService.RefundRow;
import ai.neargo.shop.pay.client.PayOpsInternalApi;
import ai.neargo.shop.pay.client.PayOpsInternalApi.OperatorReq;
import ai.neargo.shop.pay.client.PortResult;
import ai.neargo.shop.pay.ops.PayOpsFacade;
import ai.neargo.shop.svc.ConfigServiceLocator;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.ServiceName;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 支付运营的内部端点（开发计划 M5a.3）：业务服务独立形态经 {@code /internal/pay/v1/ops/**} 调支付 —— 路径、入参、
 * 操作人、业务拒绝都原样过边界（领域换替身，验的是边界）。
 */
@SpringBootTest(classes = PayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "shop.services.internal-token=tk")
@ActiveProfiles("svctest")
class PayOpsInternalApiRoundTripTest {

    @LocalServerPort private int port;
    @MockitoBean private PayOpsFacade ops;

    private PayOpsInternalApi api() {
        var locator = new ConfigServiceLocator();
        locator.getTargets().put(ServiceName.PAY, "http://127.0.0.1:" + port);
        return new InternalHttp(locator, "tk").client(ServiceName.PAY, PayOpsInternalApi.class, Duration.ofSeconds(5));
    }

    private static RefundRow refund(String no, String status) {
        return new RefundRow(no, "PYO-1", 500, "CNY", status, "七天", "OP-A", "BIZ", null, null);
    }

    @Test
    @DisplayName("★★★ 查询：过滤条件与分页原样到支付，结果原样回")
    void queries() {
        when(ops.refunds("PENDING_REVIEW", 50)).thenReturn(List.of(refund("PYR-1", "PENDING_REVIEW")));
        when(ops.orders(any(), anyInt(), anyInt())).thenReturn(List.of());

        PortResult<List<RefundRow>> r = api().refunds("PENDING_REVIEW", 50);
        api().orders("SUCCEEDED", "PYO-1", null, 7L, "MALL_ORDER", 2, 20);

        assertThat(r.value()).singleElement().satisfies(x -> assertThat(x.payRefundNo()).isEqualTo("PYR-1"));
        verify(ops).orders(new OrderFilter("SUCCEEDED", "PYO-1", null, 7L, "MALL_ORDER"), 2, 20);
    }

    @Test
    @DisplayName("★★★ 写操作：操作人显式过边界（支付只记、不认人 —— G9）；复核结果原样回")
    void writesCarryTheOperator() {
        when(ops.reviewRefund("PYR-1", true, "ok", "OPS-B")).thenReturn(refund("PYR-1", "DISPATCHED"));

        PortResult<RefundRow> r = api().reviewRefund("PYR-1", new OperatorReq("OPS-B", true, "ok", null));
        api().resendNotify(9L, new OperatorReq("OPS-B", null, null, null));
        api().disableQrCode("QR-1", new OperatorReq("OPS-B", null, null, null));
        api().ignoreException(3L, new OperatorReq("OPS-B", null, null, "线下已退"));

        assertThat(r.value().status()).isEqualTo("DISPATCHED");
        verify(ops).resendNotify(9L, "OPS-B");
        verify(ops).disableQrCode("QR-1", "OPS-B");
        verify(ops).ignoreException(3L, "OPS-B", "线下已退");
    }

    @Test
    @DisplayName("★★★ 业务拒绝原样过边界：申请人复核自己、单不存在 —— 同一个错误码")
    void rejectionsRoundTrip() {
        when(ops.reviewRefund("PYR-1", true, null, "OPS-A")).thenThrow(BizException.of(ErrorCode.PAY_FORBIDDEN));
        doThrow(BizException.of(ErrorCode.PAY_ORDER_NOT_FOUND)).when(ops).order("PYO-NOPE");

        assertThat(api().reviewRefund("PYR-1", new OperatorReq("OPS-A", true, null, null)).errorCode())
                .isEqualTo("PAY_FORBIDDEN");
        assertThat(api().order("PYO-NOPE").errorCode()).isEqualTo("PAY_ORDER_NOT_FOUND");
    }
}
