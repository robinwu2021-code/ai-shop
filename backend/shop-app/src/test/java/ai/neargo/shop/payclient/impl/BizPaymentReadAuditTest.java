package ai.neargo.shop.payclient.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.acquiring.service.MerchantCollectLinkQueryService.LinkPage;
import ai.neargo.shop.pay.acquiring.service.MerchantPaymentQueryService.CollectStatus;
import ai.neargo.shop.pay.acquiring.service.MerchantPaymentQueryService.Lookup;
import ai.neargo.shop.pay.acquiring.service.MerchantPaymentQueryService.PaymentPage;
import ai.neargo.shop.pay.biz.PayBizFacade;
import ai.neargo.shop.pay.biz.PayBizFacade.BizReceipts;
import ai.neargo.shop.pay.biz.PayBizFacade.PaymentQuery;
import ai.neargo.shop.pay.client.PayBizInternalApi;
import ai.neargo.shop.pay.client.PayBizInternalApi.PaymentSearchReq;
import ai.neargo.shop.pay.client.PortResult;
import ai.neargo.shop.payclient.BizPaymentAppService;
import ai.neargo.shop.spi.platform.AuditLogPort;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** Real embedded/remote app methods retain the exact caller and store and audit only successful reads. */
class BizPaymentReadAuditTest {
    private static final PaymentQuery QUERY = new PaymentQuery(null, null, null, null, null,
            null, null, null, null, 20);

    @BeforeEach void login() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                LoginUser.merchant("MCH-A", "店主甲"), null, List.of()));
    }

    @AfterEach void logout() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void successfulReadsKeepExactOwnerArgumentsAndLeaveNonHighRiskViewTrails(boolean remote) {
        var f = new Fixture(remote);
        var receipts = mock(BizReceipts.class);
        var orders = mock(PaymentPage.class);
        var lookup = mock(Lookup.class);
        var status = mock(CollectStatus.class);
        var links = mock(LinkPage.class);
        if (remote) {
            when(f.api.receipts("S1", "MCH-A", "B1")).thenReturn(PortResult.ok(receipts));
            when(f.api.paymentOrders("S1", new PaymentSearchReq("MCH-A", QUERY))).thenReturn(PortResult.ok(orders));
            when(f.api.paymentLookup("S1", "MCH-A", 100L, "30m", "1234")).thenReturn(PortResult.ok(lookup));
            when(f.api.collectStatus("S1", "MCH-A")).thenReturn(PortResult.ok(status));
            when(f.api.paymentLinks("S1", "MCH-A", "PENDING", "cursor", 20)).thenReturn(PortResult.ok(links));
        } else {
            when(f.local.receipts("S1", "MCH-A", "B1")).thenReturn(receipts);
            when(f.local.paymentOrders("S1", "MCH-A", QUERY)).thenReturn(orders);
            when(f.local.paymentLookup("S1", "MCH-A", 100L, "30m", "1234")).thenReturn(lookup);
            when(f.local.collectStatus("S1", "MCH-A")).thenReturn(status);
            when(f.local.paymentLinks("S1", "MCH-A", "PENDING", "cursor", 20)).thenReturn(links);
        }
        assertThat(f.service.receipts("S1", "B1")).isSameAs(receipts);
        assertThat(f.service.paymentOrders("S1", QUERY)).isSameAs(orders);
        assertThat(f.service.paymentLookup("S1", 100L, "30m", "1234")).isSameAs(lookup);
        assertThat(f.service.collectStatus("S1")).isSameAs(status);
        assertThat(f.service.paymentLinks("S1", "PENDING", "cursor", 20)).isSameAs(links);
        verify(f.audit).record("BIZ_PAY_VIEW", "S1/B1", "业务单收款记录");
        verify(f.audit).record("BIZ_PAY_VIEW", "S1", "收款列表");
        verify(f.audit).record("BIZ_PAY_VIEW", "S1", "收款查找");
        verify(f.audit).record("BIZ_PAY_VIEW", "S1", "收款能力");
        verify(f.audit).record("BIZ_PAY_VIEW", "S1", "付款链接记录");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectedReadsKeepTheOriginalErrorAndNeverFabricateSuccessAudit(boolean remote) {
        var f = new Fixture(remote);
        if (remote) {
            when(f.api.receipts("S1", "MCH-A", "B1")).thenReturn(PortResult.rejected("FORBIDDEN", List.of()));
            when(f.api.paymentOrders("S1", new PaymentSearchReq("MCH-A", QUERY)))
                    .thenReturn(PortResult.rejected("FORBIDDEN", List.of()));
            when(f.api.paymentLookup("S1", "MCH-A", 100L, "30m", "1234"))
                    .thenReturn(PortResult.rejected("FORBIDDEN", List.of()));
            when(f.api.collectStatus("S1", "MCH-A")).thenReturn(PortResult.rejected("FORBIDDEN", List.of()));
            when(f.api.paymentLinks("S1", "MCH-A", "PENDING", "cursor", 20))
                    .thenReturn(PortResult.rejected("FORBIDDEN", List.of()));
        } else {
            doThrow(BizException.of(ErrorCode.FORBIDDEN)).when(f.local).receipts("S1", "MCH-A", "B1");
            doThrow(BizException.of(ErrorCode.FORBIDDEN)).when(f.local).paymentOrders("S1", "MCH-A", QUERY);
            doThrow(BizException.of(ErrorCode.FORBIDDEN)).when(f.local)
                    .paymentLookup("S1", "MCH-A", 100L, "30m", "1234");
            doThrow(BizException.of(ErrorCode.FORBIDDEN)).when(f.local).collectStatus("S1", "MCH-A");
            doThrow(BizException.of(ErrorCode.FORBIDDEN)).when(f.local)
                    .paymentLinks("S1", "MCH-A", "PENDING", "cursor", 20);
        }
        for (Consumer<BizPaymentAppService> read : List.<Consumer<BizPaymentAppService>>of(
                service -> service.receipts("S1", "B1"), service -> service.paymentOrders("S1", QUERY),
                service -> service.paymentLookup("S1", 100L, "30m", "1234"),
                service -> service.collectStatus("S1"),
                service -> service.paymentLinks("S1", "PENDING", "cursor", 20))) {
            assertThatThrownBy(() -> read.accept(f.service)).isInstanceOfSatisfying(BizException.class,
                    error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        }
        verifyNoInteractions(f.audit);
    }

    private static final class Fixture {
        final PayBizFacade local = mock(PayBizFacade.class);
        final PayBizInternalApi api = mock(PayBizInternalApi.class);
        final AuditLogPort audit = mock(AuditLogPort.class);
        final BizPaymentAppService service;

        Fixture(boolean remote) {
            service = remote ? new RemoteBizPaymentAppService(api, audit) : new BizPaymentAppServiceImpl(local, audit);
        }
    }
}
