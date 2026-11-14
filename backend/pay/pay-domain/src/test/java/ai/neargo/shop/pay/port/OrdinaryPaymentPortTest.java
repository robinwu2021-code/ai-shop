package ai.neargo.shop.pay.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.spi.pay.PayQueryPort;
import ai.neargo.shop.spi.settle.OrdinaryPaymentPort;
import ai.neargo.shop.spi.settle.OrdinaryPaymentPort.*;
import ai.neargo.shop.spi.settle.SettlePort;
import org.junit.jupiter.api.Test;

class OrdinaryPaymentPortTest {
    private final PaymentLedgerService ledger = mock(PaymentLedgerService.class);
    private final PayQueryPort channel = mock(PayQueryPort.class);
    private final OrdinaryPaymentPort port = new OrdinaryPaymentPortImpl(ledger, channel);
    private final Reference reference = new Reference("O1", "U1", "O1-2");

    private Attempt attempt(String status) {
        return new Attempt("P1", "O1-2", "O1", "U1", "E1", "WECHAT", "TX1",
                123L, "USD", status, "DOC1");
    }

    @Test
    void openReturnsActualRetryReferenceWithoutInitiatingChannelPayment() {
        var request = new SettlePort.PaymentOpen("O1", "U1", "E1", "WECHAT", 123,
                "items", "USD", java.util.List.of());
        when(ledger.open(request)).thenReturn("O1-2");
        when(ledger.ordinaryPayment("O1-2")).thenReturn(attempt("PENDING"));
        assertThat(port.open(request).outTradeNo()).isEqualTo("O1-2");
        verifyNoInteractions(channel);
    }

    @Test
    void exactReadPreservesOwnerStatusMoneyAndOriginalReference() {
        when(ledger.ordinaryPayment("O1-2")).thenReturn(attempt("CLOSED"));
        assertThat(port.query(reference)).isEqualTo(new Lookup(true, attempt("CLOSED")));
        verifyNoInteractions(channel);
    }

    @Test
    void queryNeverFallsBackToAnotherAttemptOrAnotherOwner() {
        assertThat(port.query(reference)).isEqualTo(new Lookup(false, null));
        when(ledger.ordinaryPayment("O1-2")).thenReturn(attempt("SUCCESS"));
        assertThatThrownBy(() -> port.query(new Reference("O1", "U2", "O1-2")))
                .isInstanceOf(IllegalArgumentException.class);
        verify(ledger, never()).successfulOrdinaryPayment(anyString());
    }

    @Test
    void successLookupChecksPayerAndDoesNotPromotePending() {
        when(ledger.successfulOrdinaryPayment("O1")).thenReturn(attempt("SUCCESS"));
        assertThat(port.success(new OrderReference("O1", "U1")).payment()).isEqualTo(attempt("SUCCESS"));
        assertThatThrownBy(() -> port.success(new OrderReference("O1", "U2")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownChannelOutcomeNeverClosesOrSettlesLedger() {
        when(ledger.ordinaryPayment("O1-2")).thenReturn(attempt("PENDING"));
        when(channel.query("WECHAT", "O1-2")).thenThrow(new RuntimeException("timeout"));
        var result = port.queryChannel(reference);
        assertThat(result.status()).isEqualTo(ChannelStatus.UNKNOWN);
        assertThat(result.amountMinor()).isNull();
        assertThat(result.currency()).isNull();
        verify(ledger, never()).close(anyString(), anyString());
        verify(ledger, never()).settle(any());
    }

    @Test
    void channelEvidenceCarriesActualMoneyWithoutPromotingLedger() {
        when(ledger.ordinaryPayment("O1-2")).thenReturn(attempt("PENDING"));
        when(channel.query("WECHAT", "O1-2"))
                .thenReturn(new PayQueryPort.Result(true, true, true, 124, "TX2", "EUR"));
        var result = port.queryChannel(reference);
        assertThat(result.status()).isEqualTo(ChannelStatus.PAID);
        assertThat(result.amountMinor()).isEqualTo(124L);
        assertThat(result.currency()).isEqualTo("EUR");
        assertThat(result.tradeNo()).isEqualTo("TX2");
        verify(ledger, never()).settle(any());
    }
    @Test
    void refreshConfirmsAtFinancialOwnerAndReReadsExactReceipt() {
        when(ledger.ordinaryPayment("O1-2")).thenReturn(attempt("PENDING"), attempt("PENDING"), attempt("SUCCESS"));
        when(channel.query("WECHAT", "O1-2"))
                .thenReturn(new PayQueryPort.Result(true, true, true, 123, "TX1", "USD"));
        when(ledger.settle(any())).thenReturn("O1");
        assertThat(port.refresh(reference).payment().status()).isEqualTo("SUCCESS");
        verify(ledger).settle(argThat(proof -> proof.outTradeNo().equals("O1-2")
                && proof.payChannel().equals("WECHAT") && proof.amountMinor()==123
                && proof.currency().equals("USD") && proof.tradeNo().equals("TX1")));
    }

    @Test
    void refreshCannotPromoteUnknownOrMismatchedChannelCash() {
        when(ledger.ordinaryPayment("O1-2")).thenReturn(attempt("PENDING"));
        for (var evidence : java.util.List.of(new PayQueryPort.Result(false,false,false,0,null,null),
                new PayQueryPort.Result(true,true,true,124,"TX1","USD"),
                new PayQueryPort.Result(true,true,true,123,"TX1","CNY"),
                new PayQueryPort.Result(true,true,true,123,null,"USD"))) {
            when(channel.query("WECHAT", "O1-2")).thenReturn(evidence);
            assertThat(port.refresh(reference).payment().status()).isEqualTo("PENDING");
        }
        verify(ledger, never()).settle(any());
    }

}
