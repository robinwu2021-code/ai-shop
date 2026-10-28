package ai.neargo.shop.pay.acquiring.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.neargo.shop.pay.access.PayCaller;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.acquiring.service.PaymentService.StartPaymentCmd;
import java.time.LocalDateTime;
import ai.neargo.shop.pay.access.policy.CallerPolicy;
import ai.neargo.shop.pay.acquiring.entity.PayOrder;
import ai.neargo.shop.pay.acquiring.route.RouteSelectionPolicy;
import ai.neargo.shop.pay.acquiring.service.PaymentAttemptCloseService;
import ai.neargo.shop.pay.channel.api.CallerType;
import ai.neargo.shop.pay.channel.api.ChannelResult;
import ai.neargo.shop.pay.entity.StlPayment;
import ai.neargo.shop.pay.mapper.SettleMappers.PaymentMapper;
import ai.neargo.shop.pay.mapper.acquiring.AcquiringMappers.PayOrderMapper;
import ai.neargo.shop.pay.mapper.checkout.CheckoutMappers.PayCheckoutSessionMapper;
import ai.neargo.shop.pay.notify.service.BizNotifyService;
import ai.neargo.shop.pay.service.OrdinaryPaymentInitiationService;
import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.spi.pay.PayQueryPort;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import java.util.List;
import java.util.Set;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

/** 查询没有得到可信结果时，不能向调用方报告订单已经关闭（AC16 / W3 E04）。 */
class PaymentCloseSafetyTest {
    private final PayOrderMapper orders = mock(PayOrderMapper.class);
    private final PaymentMapper attempts = mock(PaymentMapper.class);
    private final PayQueryPort query = mock(PayQueryPort.class);
    private final PaymentLedgerService ledger = mock(PaymentLedgerService.class);
    private final BizNotifyService notify = mock(BizNotifyService.class);
    private final PayCheckoutSessionMapper sessions = mock(PayCheckoutSessionMapper.class);
    private final PaymentAttemptCloseService channelClose = mock(PaymentAttemptCloseService.class);
    private final PayOrder order = new PayOrder();
    private final StlPayment attempt = new StlPayment();
    private final PayCaller caller = new PayCaller(CallerType.INTERNAL, 1L, Set.of(7L), "operator", null, null);
    private final PaymentServiceImpl service = new PaymentServiceImpl(mock(CallerPolicy.class), orders, attempts,
            new Transactions(), query, ledger, mock(RouteSelectionPolicy.class),
            mock(OrdinaryPaymentInitiationService.class), notify, sessions, channelClose);

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), StlPayment.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), PayOrder.class);
        order.setId(11L);
        order.setPayOrderNo("PAY-11");
        order.setStoreId(7L);
        order.setStatus("AWAITING_PAYMENT");
        attempt.setId(22L);
        attempt.setPayChannel("WECHAT");
        attempt.setOutTradeNo("TRADE-22");
        attempt.setStatus(StlPayment.PENDING);
        when(orders.selectOne(any())).thenReturn(order);
        when(orders.lockById(11L)).thenReturn(order);
        when(attempts.selectList(any())).thenReturn(List.of(attempt));
        doAnswer(inv -> {
            PayOrder patch = inv.getArgument(0);
            if (patch.getStatus() != null) { order.setStatus(patch.getStatus()); }
            return 1;
        }).when(orders).updateById(any(PayOrder.class));
    }

    @Test
    void failedQueryDoesNotCloseOrRevokeCheckout() {
        when(query.query("WECHAT", "TRADE-22")).thenReturn(new PayQueryPort.Result(false, false, false, 0, null));
        assertThat(service.close(caller, "PAY-11", "timeout").getStatus()).isEqualTo("AWAITING_PAYMENT");
        verify(ledger, never()).close(any(), any());
        verify(sessions, never()).revokeAll(any());
        verify(notify, never()).paymentClosed(any());
    }

    @Test
    void missingQueryReceiptDoesNotClose() {
        assertThat(service.close(caller, "PAY-11", "timeout").getStatus()).isEqualTo("AWAITING_PAYMENT");
        verify(ledger, never()).close(any(), any());
    }

    @Test
    void unpaidQueryAloneDoesNotProveChannelClosed() {
        when(query.query("WECHAT", "TRADE-22")).thenReturn(new PayQueryPort.Result(true, false, true, 0, null));
        assertThat(service.close(caller, "PAY-11", "timeout").getStatus()).isEqualTo("AWAITING_PAYMENT");
        verify(ledger, never()).close(any(), any());
    }

    @Test
    void confirmedChannelCloseClosesLocalOrderAndRevokesToken() {
        when(query.query("WECHAT", "TRADE-22")).thenReturn(new PayQueryPort.Result(true, false, true, 0, null));
        when(channelClose.close(caller, order, attempt)).thenReturn(true);
        assertThat(service.close(caller, "PAY-11", "timeout").getStatus()).isEqualTo("CLOSED");
        verify(ledger).close("TRADE-22", "timeout");
        verify(sessions).revokeAll(11L);
        verify(notify).paymentClosed(any());
    }

    @Test
    void newAttemptDuringChannelCallPreventsClosingTheWholeOrder() {
        when(query.query("WECHAT", "TRADE-22")).thenReturn(new PayQueryPort.Result(true, false, true, 0, null));
        when(channelClose.close(caller, order, attempt)).thenReturn(true);
        StlPayment concurrent = new StlPayment();
        concurrent.setId(23L);
        when(attempts.selectList(any())).thenReturn(List.of(attempt), List.of(attempt, concurrent));
        assertThat(service.close(caller, "PAY-11", "timeout").getStatus()).isEqualTo("AWAITING_PAYMENT");
        verify(ledger, never()).close(any(), any());
    }

    @Test
    void closedOrderRejectsNewAttemptBeforeLinkingIt() {
        order.setStatus("CLOSED");
        assertThatThrownBy(() -> service.attachAttempt("PAY-11", 23L))
                .isInstanceOf(BizException.class);
        verify(attempts, never()).updateById(any(StlPayment.class));
    }

    @Test
    void terminalReplayDoesNotCallChannelAgain() {
        order.setStatus("CLOSED");
        assertThat(service.close(caller, "PAY-11", "timeout").getStatus()).isEqualTo("CLOSED");
        verify(query, never()).query(any(), any());
        verify(channelClose, never()).close(any(), any(), any());
    }

    @Test
    void paidReceiptIsAppliedInsteadOfClosed() {
        when(query.query("WECHAT", "TRADE-22")).thenReturn(new PayQueryPort.Result(true, true, true, 100, "TX-22", "CNY"));
        doAnswer(inv -> { order.setStatus("SUCCEEDED"); return "TRADE-22"; }).when(ledger).settle(any());
        assertThat(service.close(caller, "PAY-11", "timeout").getStatus()).isEqualTo("SUCCEEDED");
        verify(channelClose, never()).close(any(), any(), any());
        verify(ledger, never()).close(any(), any());
    }

    @Test
    void frozenMerchantQueryCannotFallBackToDefaultMerchantsMissingAnswer() {
        attempt.setPayMerchantId(33L);
        when(channelClose.query(caller, order, attempt)).thenReturn(new ChannelResult.Unknown("TIMEOUT"));
        when(query.query("WECHAT", "TRADE-22")).thenReturn(new PayQueryPort.Result(true, false, false, 0, null));
        assertThat(service.close(caller, "PAY-11", "timeout").getStatus()).isEqualTo("AWAITING_PAYMENT");
        verify(query, never()).query(any(), any());
        verify(ledger, never()).close(any(), any());
    }

    @Test
    void missingOrderStillNeedsChannelCloseProof() {
        when(query.query("WECHAT", "TRADE-22")).thenReturn(new PayQueryPort.Result(true, false, false, 0, null));
        assertThat(service.close(caller, "PAY-11", "timeout").getStatus()).isEqualTo("AWAITING_PAYMENT");
        verify(channelClose).close(caller, order, attempt);
    }

    @Test
    void requestedCloseRejectsNewAttemptEvenBeforeChannelConfirmation() {
        order.setCloseRequestedAt(LocalDateTime.now());
        assertThatThrownBy(() -> service.attachAttempt("PAY-11", 23L)).isInstanceOf(BizException.class);
        verify(attempts, never()).updateById(any(StlPayment.class));
    }

    @Test
    void requestedCloseCannotStartAnotherPayment() {
        order.setCloseRequestedAt(LocalDateTime.now());
        assertThatThrownBy(() -> service.start(caller, "PAY-11", new StartPaymentCmd("TEST_PAY", null, null)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.PAY_ORDER_STATE));
    }

    @Test
    void failedOrderStillRetainsExplicitBusinessCancellationIntent() {
        order.setStatus("FAILED");
        service.cancel(caller, "PAY-11", "business closed");
        assertThat(order.getCloseRequestedAt()).isNotNull();
    }

    private static final class Transactions extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object tx, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }
}
