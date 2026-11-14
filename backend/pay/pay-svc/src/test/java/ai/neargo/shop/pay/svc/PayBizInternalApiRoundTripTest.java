package ai.neargo.shop.pay.svc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.biz.PayBizFacade;
import ai.neargo.shop.pay.biz.PayBizFacade.CollectView;
import ai.neargo.shop.pay.biz.PayBizFacade.RefundView;
import ai.neargo.shop.pay.client.PayBizInternalApi;
import ai.neargo.shop.pay.client.PayBizInternalApi.CollectReq;
import ai.neargo.shop.pay.client.PayBizInternalApi.GrantReq;
import ai.neargo.shop.pay.client.PayBizInternalApi.RefundReq;
import ai.neargo.shop.pay.client.PayBizInternalApi.VoidLinkReq;
import ai.neargo.shop.pay.acquiring.service.MerchantCollectLinkQueryService.LinkPage;
import ai.neargo.shop.pay.acquiring.service.MerchantCollectLinkQueryService.LinkRow;
import ai.neargo.shop.pay.client.PortResult;
import ai.neargo.shop.svc.ConfigServiceLocator;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.ServiceName;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 商家端支付的内部端点（开发计划 M5a.5）：业务服务独立形态经 {@code /internal/pay/v1/biz/stores/{storeNo}/**} 调支付 ——
 * 门店、操作人、入参、业务拒绝都原样过边界（领域换替身，验的是边界）。
 */
@SpringBootTest(classes = PayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "shop.services.internal-token=tk")
@ActiveProfiles("svctest")
class PayBizInternalApiRoundTripTest {

    @LocalServerPort private int port;
    @MockitoBean private PayBizFacade biz;

    private PayBizInternalApi api() {
        var locator = new ConfigServiceLocator();
        locator.getTargets().put(ServiceName.PAY, "http://127.0.0.1:" + port);
        return new InternalHttp(locator, "tk").client(ServiceName.PAY, PayBizInternalApi.class, Duration.ofSeconds(5));
    }

    private static RefundView refund(String status) {
        return new RefundView("PYR-1", 11L, 500, "CNY", status, "七天", "MCH-A", 1L, null);
    }

    @Test
    @DisplayName("★★★ 不带业务单号的收款请求与加字段前一字不差 —— 老版本收款服务不会因为多一个字段拒收")
    void collectWithoutBizNoKeepsTheOldWireShape() {
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        assertThat(json.writeValueAsString(new CollectReq("MCH-A", 1200L, "桌 3", 15, null)))
                .doesNotContain("bizNo");
        assertThat(json.writeValueAsString(new CollectReq("MCH-A", 1200L, "桌 3", 15, "FST1")))
                .contains("\"bizNo\":\"FST1\"");
    }

    @Test
    @DisplayName("★★ 业务单收款汇总的原始行过边界不丢字段（TDD-业务单收款汇总）")
    void bizReceiptsCrossTheBoundary() {
        var receipts = new ai.neargo.shop.pay.biz.PayBizFacade.BizReceipts(
                List.of(new ai.neargo.shop.pay.acquiring.service.MerchantCollectLinkQueryService.BizLink(
                        "PYO-1", "PAID", "CNY", 300, 300, 100, 1L, 2L)),
                List.of(new ai.neargo.shop.pay.biz.PayBizFacade.StoredValueReceipt("EU1", "COMMITTED", "CNY", 150, 3L)));
        when(biz.receipts("S-1", "MCH-A", "FST1")).thenReturn(receipts);
        assertThat(api().receipts("S-1", "MCH-A", "FST1").value()).isEqualTo(receipts);
    }

    @Test
    @DisplayName("★★★ 门店与操作人显式过边界（读也带 —— 门店范围按操作员圈）；结果原样回")
    void storeAndOperatorCrossTheBoundary() {
        when(biz.collect("S-1", "MCH-A", 1200L, "桌 3", 15, "FST1")).thenReturn(new CollectView("PYO-1", "tk-1", 9L, "/c/tk-1", 1200L, "CNY", "桌 3", "一号店"));
        when(biz.refunds("S-1", "MCH-A")).thenReturn(List.of(refund("SUCCEEDED")));

        PortResult<CollectView> c = api().collect("S-1", new CollectReq("MCH-A", 1200L, "桌 3", 15, "FST1"));
        PortResult<List<RefundView>> r = api().refunds("S-1", "MCH-A");
        api().statement("S-1", "MCH-A", "2026-10-28");
        api().disableQrCode("S-1", "QR-1", "MCH-A");
        api().revokeGrant("S-1", 5L, "MCH-A");
        api().grant("S-1", new GrantReq("MCH-A", "APP-X", Set.of("COLLECT")));

        assertThat(c.value().checkoutUrl()).isEqualTo("/c/tk-1");
        assertThat(r.value()).singleElement().satisfies(x -> assertThat(x.requestedBy()).isEqualTo("MCH-A"));
        verify(biz).statement("S-1", "MCH-A", "2026-10-28");
        verify(biz).disableQrCode("S-1", "MCH-A", "QR-1");
        verify(biz).revokeGrant("S-1", "MCH-A", 5L);
        verify(biz).grant("S-1", "MCH-A", "APP-X", Set.of("COLLECT"));
    }

    @Test
    @DisplayName("★★★ 付款链接（TDD-门店收银台与付款链接 L3–L5）：列表条件、再次分享、作废原因与操作人都原样过边界")
    void paymentLinksCrossTheBoundary() {
        LinkRow row = new LinkRow("PYO-2", 300, "CNY", "订金", "VOIDED", 1L, 2L, null, null, "MCH-A", "MCH-A");
        when(biz.paymentLinks("S-1", "MCH-A", "PENDING", "1_2", 5)).thenReturn(new LinkPage(List.of(row), null));
        when(biz.reissueLink("S-1", "MCH-A", "PYO-2"))
                .thenReturn(new CollectView("PYO-2", "tk-2", 9L, "/c/tk-2", 300L, "CNY", "订金", "一号店"));
        when(biz.voidLink("S-1", "MCH-A", "PYO-2", "顾客改现金")).thenReturn(row);

        assertThat(api().paymentLinks("S-1", "MCH-A", "PENDING", "1_2", 5).value().items()).singleElement()
                .satisfies(x -> assertThat(x.subject()).isEqualTo("订金"));
        assertThat(api().reissueLink("S-1", "PYO-2", "MCH-A").value().token()).isEqualTo("tk-2");
        assertThat(api().voidLink("S-1", "PYO-2", new VoidLinkReq("MCH-A", "顾客改现金")).value().status())
                .isEqualTo("VOIDED");
    }

    @Test
    @DisplayName("★★★ 业务拒绝原样过边界：别家门店的单退不了 —— 同一个错误码")
    void rejectionsRoundTrip() {
        when(biz.requestRefund("S-2", "MCH-B", "PYO-1", 100L, null, null))
                .thenThrow(BizException.of(ErrorCode.PAY_FORBIDDEN));

        assertThat(api().requestRefund("S-2", new RefundReq("MCH-B", "PYO-1", 100L, null, null)).errorCode())
                .isEqualTo("PAY_FORBIDDEN");
    }
}
