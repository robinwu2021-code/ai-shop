package ai.neargo.shop.trade.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutScope;
import ai.neargo.shop.spi.trade.CheckoutPort.OperationRef;
import ai.neargo.shop.trade.entity.OrdCheckoutOperation;
import ai.neargo.shop.trade.entity.OrdCheckoutQuote;
import ai.neargo.shop.trade.entity.OrdCheckoutConfirmation;
import ai.neargo.shop.trade.mapper.CheckoutMappers;
import ai.neargo.shop.trade.policy.ManualServicePurchasePolicy;
import ai.neargo.shop.trade.policy.CheckoutAccessPolicy.CashierActor;
import ai.neargo.shop.trade.order.service.create.CheckoutClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.Instant;
import java.time.LocalDateTime;

/** Receipt reads cannot accept again, create orders or expire an accepted purchase. */
@ExtendWith(MockitoExtension.class)
class CounterStateTest {
    @Mock CheckoutMappers.OperationMapper operations;
    @Mock CheckoutMappers.QuoteMapper quotes;
    @Mock CheckoutMappers.ConfirmationMapper confirmations;
    @Mock ManualServicePurchasePolicy manualPolicy;
    @Mock CheckoutClock clock;
    @Mock PlatformTransactionManager manager;
    @InjectMocks CheckoutServiceImpl service;
    @BeforeEach void wireFieldDependency() {
        ReflectionTestUtils.setField(service, "manualPolicy", manualPolicy);
        lenient().when(manualPolicy.requireCounterReader(any(), any()))
                .thenReturn(new CashierActor("MAIN", "E", "S", "A", "MERCHANT", "PERSON", "AC"));
    }
    final CheckoutScope scope = new CheckoutScope("MAIN", "E", "S", "A");
    final OperationRef ref = new OperationRef("BEAUTY", "OP");
    final LocalDateTime time = LocalDateTime.of(2026, 11, 10, 0, 0);

    OrdCheckoutOperation operation(String phase) {
        var op = new OrdCheckoutOperation();
        op.setTenantNo("MAIN"); op.setEntityNo("E"); op.setStoreNo("S");
        op.setCaller("BEAUTY"); op.setOperationNo("OP"); op.setCurrentQuoteNo("Q");
        op.setPurchaseAuthority("MERCHANT_RECORDED_IN_PERSON"); op.setAdmissionKind("MANUAL_OFFLINE");
        op.setPhase(phase); op.setVersion(3L);
        op.setActorNo("A"); op.setActorRealm("MERCHANT"); op.setActorKind("PERSON"); op.setAccountNo("AC");
        when(operations.lock("BEAUTY", "OP")).thenReturn(op);
        lenient().when(clock.now()).thenReturn(Instant.parse("2026-11-10T01:00:00Z"));
        return op;
    }
    @Test void recoversAcceptedReceiptAndOriginalOrderAfterRestart() {
        var op = operation("CREATED"); op.setConfirmationNo("CF"); op.setOrderNo("O"); op.setSubOrderNo("SO");
        var row = new OrdCheckoutConfirmation();
        row.setCaller("BEAUTY"); row.setOperationNo("OP"); row.setConfirmationNo("CF");
        row.setQuoteNo("Q"); row.setQuoteRevision(1L); row.setQuoteHash("f".repeat(64));
        row.setCustomerKind("GUEST"); row.setGuestNo("G"); row.setIdentityRevision(1L);
        row.setPurchaseAuthority("MERCHANT_RECORDED_IN_PERSON"); row.setAcceptedAt(time);
        row.setExpiresAt(time.plusMinutes(10)); row.setStatus("CONSUMED");
        when(confirmations.selectOne(any())).thenReturn(row);
        var state = service.readCounterState(scope, ref);
        assertThat(state.confirmation().confirmationNo()).isEqualTo("CF");
        assertThat(state.result().orderNo()).isEqualTo("O");
        assertThat(state.result().phase()).isEqualTo("CREATED");
        verify(manualPolicy).requireCounterReader(scope, ref);
        verify(operations, never()).updateById(any(OrdCheckoutOperation.class));
    }
    @Test void committedPlaceKeyKeepsUnknownPlacementReadOnly() {
        var op = operation("CONFIRMED"); op.setPlaceKeyHash("original-key-hash");
        assertThat(service.readCounterState(scope, ref).result().phase()).isEqualTo("PLACING");
    }
    @Test void expiresOnlyUnacceptedQuoteUnderAcceptanceLock() {
        var op = operation("QUOTED");
        var quote = new OrdCheckoutQuote(); quote.setCaller("BEAUTY"); quote.setOperationNo("OP");
        quote.setExpiresAt(time.plusMinutes(10)); quote.setStatus("OPEN");
        when(quotes.lock("Q")).thenReturn(quote);
        when(quotes.updateById(quote)).thenReturn(1); when(operations.updateById(op)).thenReturn(1);
        var state = service.readCounterState(scope, ref);
        assertThat(state.confirmation()).isNull();
        assertThat(state.result().phase()).isEqualTo("REQUOTE_REQUIRED");
        assertThat(quote.getStatus()).isEqualTo("EXPIRED");
    }
    @Test void onlyOriginalAccountMayAcceptButCurrentReadersCanObserveIt() {
        var op = operation("QUOTED");
        var quote = new OrdCheckoutQuote(); quote.setCaller("BEAUTY"); quote.setOperationNo("OP");
        quote.setExpiresAt(time.plusHours(2)); when(quotes.lock("Q")).thenReturn(quote);
        assertThat(service.readCounterState(scope, ref).canAccept()).isTrue();
        op.setAccountNo("ORIGINAL-OTHER-ACCOUNT");
        assertThat(service.readCounterState(scope, ref).canAccept()).isFalse();
        verify(operations, never()).updateById(any(OrdCheckoutOperation.class));
    }
    @Test void rejectsForeignStoreBeforeReceiptRead() {
        operation("CREATED");
        assertThatThrownBy(() -> service.readCounterState(new CheckoutScope("MAIN", "E", "OTHER", "A"), ref))
                .isInstanceOf(BizException.class);
        verify(confirmations, never()).selectOne(any());
    }
}
