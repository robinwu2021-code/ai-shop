package ai.neargo.shop.pay.svc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.spi.pay.PayQueryPort;
import ai.neargo.shop.spi.settle.OrdinaryPaymentPort;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.pay.service.OrdinaryPaymentInitiationService;
import ai.neargo.shop.pay.client.PayOrdinaryPaymentRequests;
import ai.neargo.shop.pay.service.RefundExecutionService;
import ai.neargo.shop.spi.settle.OrdinaryRefundPort;
import ai.neargo.shop.spi.settle.RefundRequest;
import ai.neargo.shop.spi.settle.RefundDispatchResult;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.common.CursorPage;
import ai.neargo.shop.pay.client.PayStoredValueConsumerRequests;
import ai.neargo.shop.pay.storedvalue.service.StoredValueConsumerQueryService;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.storedvalue.StoredValueConsumerViews;
import java.time.Instant;
import ai.neargo.shop.pay.client.PayInternalApi;
import ai.neargo.shop.pay.client.PayInternalApi.IssueReq;
import ai.neargo.shop.pay.client.PayInternalApi.RejectReq;
import ai.neargo.shop.pay.dto.FeeRuleVO;
import ai.neargo.shop.pay.dto.FinanceVOs.SettleInvoiceVO;
import ai.neargo.shop.pay.service.FeeRuleService;
import ai.neargo.shop.pay.service.SettleInvoiceService;
import ai.neargo.shop.svc.ConfigServiceLocator;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.CallOutcome;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * **真客户端打真服务端**：pay-svc 起在随机端口上（真 Tomcat、真令牌校验、真全局信封），
 * shop-app 用的那个 {@link PayInternalApi} 代理直接调过去。只把领域服务换成替身 ——
 * 测试配置关着 Flyway，没有表可查，而这里要验的是 HTTP 契约，不是费率算得对不对。
 *
 * <p>这一组钉住的是「两边各写一半」时最容易漂的东西：路径、参数名、请求体字段名、
 * 令牌头，以及 {@code /internal/**} 不被全局响应信封包裹 —— 被包的话客户端解出来是
 * 字段全 null 的对象（或者解不出来），而服务端日志一切正常。本仓库踩过。
 */
@SpringBootTest(classes = PayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "shop.services.internal-token=tk")
@ActiveProfiles("svctest")
@DisplayName("shop-app → pay-svc 的 HTTP 契约")
class PayInternalApiRoundTripTest {

    @LocalServerPort
    private int port;

    @MockitoBean
    private FeeRuleService feeRuleService;

    @MockitoBean
    private SettleInvoiceService invoiceService;

    private PayInternalApi api(String token) {
        var locator = new ConfigServiceLocator();
        locator.getTargets().put(ServiceName.PAY, "http://127.0.0.1:" + port);
        return new InternalHttp(locator, token).client(ServiceName.PAY, PayInternalApi.class, Duration.ofSeconds(5));
    }

    private static SettleInvoiceVO invoice(String no, String status) {
        return new SettleInvoiceVO(no, "M1", "某商户", "2026-09", 10000, 10000, "COMPANY", "某公司", "TAX1",
                status, "SN1", "2026-09-20 10:00:00", null, null);
    }

    @Test
    @DisplayName("★★★ 费率列表原样回来 —— 被全局信封包住的话这里解不出 List")
    void feeRulesAreNotEnveloped() {
        FeeRuleVO rule = new FeeRuleVO("FR1", "SELF_OPERATED", "PLATFORM", 500, 1788000000000L, 1, "首版",
                LocalDateTime.of(2026, 9, 1, 8, 0), "U1");
        when(feeRuleService.rules()).thenReturn(List.of(rule));

        assertThat(ServiceCalls.call(ServiceName.PAY, api("tk")::feeRules)).containsExactly(rule);
    }

    @Test
    @DisplayName("★★ effectiveRates 的时刻参数按名字 at 到达服务端")
    void effectiveRatesPassesAt() {
        when(feeRuleService.effectiveRates(1788000000000L)).thenReturn(Map.of("SELF_OPERATED|PLATFORM", 500));

        assertThat(api("tk").effectiveRates(1788000000000L)).containsEntry("SELF_OPERATED|PLATFORM", 500);
    }

    @Test
    @DisplayName("★★ 发票列表：null 的筛选条件不带，其它参数按名字到达")
    void settleInvoicesParams() {
        when(invoiceService.list(null, "某", 2, 10))
                .thenReturn(new PageData<>(List.of(invoice("INV1", "PENDING")), 1, 2, 10));

        PageData<SettleInvoiceVO> page = api("tk").settleInvoices(null, "某", 2, 10);

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.records()).extracting(SettleInvoiceVO::invoiceNo).containsExactly("INV1");
        verify(invoiceService).list(null, "某", 2, 10);
    }

    @Test
    @DisplayName("★★★ 开票与驳回：路径变量与请求体字段名两边对得上")
    void issueAndRejectBodies() {
        when(invoiceService.issue("INV1", "SN9", "U1")).thenReturn(invoice("INV1", "ISSUED"));
        when(invoiceService.reject("INV2", "抬头不对", "U1")).thenReturn(invoice("INV2", "REJECTED"));

        assertThat(api("tk").issue("INV1", new IssueReq("SN9", "U1")).status()).isEqualTo("ISSUED");
        assertThat(api("tk").reject("INV2", new RejectReq("抬头不对", "U1")).status()).isEqualTo("REJECTED");
        verify(invoiceService).issue("INV1", "SN9", "U1");
        verify(invoiceService).reject("INV2", "抬头不对", "U1");
    }

    @Test
    @DisplayName("★★ 令牌不对：服务端 401，客户端归为 REMOTE_ERROR 并带上状态码")
    void wrongTokenIs401() {
        assertThatThrownBy(() -> ServiceCalls.call(ServiceName.PAY, api("wrong")::feeRules))
                .isInstanceOfSatisfying(ServiceCallException.class, e -> {
                    assertThat(e.outcome()).isEqualTo(CallOutcome.REMOTE_ERROR);
                    assertThat(e.statusCode()).isEqualTo(401);
                });
    }
    @MockitoBean
    private StoredValueConsumerQueryService consumerReads;

    @Test
    void consumerRecordsAndCommittedNotificationFactsRoundTripWithoutEnvelope() {
        var ctx=new EntitlementContext("TENANT_A","USER_A","query","a".repeat(64));
        var at=Instant.parse("2026-10-21T01:00:00Z");
        var row=new StoredValueConsumerViews.Record("REDEMPTION","护理","CNY",-12800,at,"EU20261021000001");
        var expected=new CursorPage<>(List.of(row),null);
        when(consumerReads.records(ctx,"STORE_A","ALL",at,null,"EU20261021000001",null,20))
                .thenReturn(expected);
        var request=new PayStoredValueConsumerRequests.Records(ctx,"STORE_A","ALL",at,null,
                "EU20261021000001",null,20);
        assertThat(api("tk").svConsumerRecords(request)).isEqualTo(expected);
        var fact=new StoredValueConsumerViews.NotificationFact("USE_A","USER_A","STORE_A","门店","护理",
                "CNY",12800,at,"EU20261021000001");
        when(consumerReads.notificationFact(ctx,"USE_A")).thenReturn(fact);
        assertThat(api("tk").svNotificationFact(new PayStoredValueConsumerRequests.Fact(ctx,"USE_A"))).isEqualTo(fact);
        verify(consumerReads).records(ctx,"STORE_A","ALL",at,null,"EU20261021000001",null,20);
        assertThatThrownBy(()->ServiceCalls.call(ServiceName.PAY,()->api("wrong").svConsumerRecords(request)))
                .isInstanceOfSatisfying(ServiceCallException.class,e->assertThat(e.statusCode()).isEqualTo(401));
    }


    @MockitoBean
    private PaymentLedgerService paymentLedger;

    @MockitoBean
    private PayQueryPort paymentQueries;

    @Test
    void ordinaryLedgerContractKeepsExactRetryReferenceOwnershipAndChannelUncertainty() {
        var reference = new OrdinaryPaymentPort.Reference("O1", "U1", "O1-2");
        var attempt = new OrdinaryPaymentPort.Attempt(
                "P1", "O1-2", "O1", "U1", "E1", "WECHAT", null, 123L, "USD", "PENDING", "D1");
        var command = new SettlePort.PaymentOpen(
                "O1", "U1", "E1", "WECHAT", 123, "items", "USD", List.of());
        when(paymentLedger.open(command)).thenReturn("O1-2");
        when(paymentLedger.ordinaryPayment("O1-2")).thenReturn(attempt);
        assertThat(api("tk").ordinaryPaymentOpen(command)).isEqualTo(attempt);
        assertThat(api("tk").ordinaryPaymentQuery(reference).payment()).isEqualTo(attempt);
        when(paymentLedger.successfulOrdinaryPayment("O1")).thenReturn(null);
        assertThat(api("tk").ordinaryPaymentSuccess(
                new OrdinaryPaymentPort.OrderReference("O1", "U1")).found()).isFalse();
        when(paymentQueries.query("WECHAT", "O1-2"))
                .thenReturn(new PayQueryPort.Result(false, false, false, 0, null, null));
        var outcome = api("tk").ordinaryPaymentChannelQuery(reference);
        assertThat(outcome.status()).isEqualTo(OrdinaryPaymentPort.ChannelStatus.UNKNOWN);
        assertThat(outcome.amountMinor()).isNull();
        assertThat(outcome.currency()).isNull();
        assertThatThrownBy(() -> ServiceCalls.call(ServiceName.PAY,
                () -> api("wrong").ordinaryPaymentQuery(reference)))
                .isInstanceOfSatisfying(ServiceCallException.class, e -> assertThat(e.statusCode()).isEqualTo(401));
        assertThatThrownBy(() -> ServiceCalls.call(ServiceName.PAY,
                () -> api("tk").ordinaryPaymentQuery(
                        new OrdinaryPaymentPort.Reference("OTHER", "U1", "O1-2"))))
                .isInstanceOf(ServiceCallException.class);
    }
    @MockitoBean
    private OrdinaryPaymentInitiationService ordinaryInitiation;

    @Test
    void ordinaryInitiationCarriesTrustedIdentityContextAndReturnsActualGatewayParams() {
        var payment = new SettlePort.PaymentOpen("OI1", "UI1", "EI1", "WECHAT", 120,
                "items", "USD", List.of());
        var payer = new PayOrdinaryPaymentRequests.PayerIdentity(
                "UI1", "OI1", "openid-original", "APP1",
                PayOrdinaryPaymentRequests.hash(payment));
        var expected = new SettlePort.PayInitResult(true, "OI1-2", "WECHAT", Map.of("package", "prepay_id=p1"), null);
        when(ordinaryInitiation.initiate(payment, payer)).thenReturn(expected);
        var command = new PayOrdinaryPaymentRequests.Initiate(payment, payer);
        assertThat(api("tk").ordinaryPaymentInitiate(command)).isEqualTo(expected);
        verify(ordinaryInitiation).initiate(payment, payer);
    }

    @MockitoBean
    private RefundExecutionService refundExecution;

    @Test
    void ordinaryRefundRoundTripPreservesFrozenOwnerCoordinatesAndUnknownOutcome() {
        var original = new OrdinaryPaymentPort.Attempt("P-RF", "O-RF-2", "O-RF", "U-RF", "E-RF",
                "WECHAT", "TX-RF", 120L, "USD", "SUCCESS", "DOC-RF");
        when(paymentLedger.ordinaryPayment("O-RF-2")).thenReturn(original);
        var frozen = new RefundRequest("RF1", "AS1", "S1", "P-RF", 50, "USD", "return");
        var command = new OrdinaryRefundPort.Execution("O-RF", "U-RF", "O-RF-2", frozen);
        var outcome = new RefundDispatchResult("RF1", "REFUND-P1",
                RefundDispatchResult.DispatchStatus.UNKNOWN,
                "REFUND_QUERY_UNKNOWN", true, null);
        when(refundExecution.request(frozen)).thenReturn(outcome);
        when(refundExecution.query(frozen)).thenReturn(outcome);
        var expected = new OrdinaryRefundPort.Receipt(command, outcome);
        assertThat(api("tk").ordinaryRefundRequest(command)).isEqualTo(expected);
        assertThat(api("tk").ordinaryRefundQuery(command)).isEqualTo(expected);
        verify(refundExecution).request(frozen);
        verify(refundExecution).query(frozen);
        assertThatThrownBy(() -> ServiceCalls.call(ServiceName.PAY,
                () -> api("wrong").ordinaryRefundRequest(command)))
                .isInstanceOfSatisfying(ServiceCallException.class, e -> assertThat(e.statusCode()).isEqualTo(401));
        var foreign = new OrdinaryRefundPort.Execution("O-RF", "OTHER", "O-RF-2", frozen);
        assertThatThrownBy(() -> ServiceCalls.call(ServiceName.PAY, () -> api("tk").ordinaryRefundRequest(foreign)))
                .isInstanceOf(ServiceCallException.class);
    }

}
