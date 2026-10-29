package ai.neargo.inventory.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.neargo.inventory.api.InventoryException;
import ai.neargo.inventory.domain.entity.InvLedger;
import ai.neargo.inventory.domain.entity.InvStockBalance;
import ai.neargo.inventory.domain.mapper.InventoryMappers.BalanceMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.LedgerMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.OutboxMapper;
import ai.neargo.inventory.domain.service.impl.StockPostingServiceImpl;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Exact source/receipt and old integer compatibility, deliberately not run during the coding batch.
 */
class ExactStockPostingTest {
    @Test
    void fractionalIssueUsesOriginalBalanceAndImmutableHistoricalLedger() {
        var balances = mock(BalanceMapper.class);
        var ledger = mock(LedgerMapper.class);
        var outbox = mock(OutboxMapper.class);
        var service = new StockPostingServiceImpl(balances, ledger, outbox);
        when(balances.selectCount(any())).thenReturn(1L);
        when(balances.applyExactDelta(eq("O"), eq("I"), eq("L"), eq(new BigDecimal("-0.125"))))
                .thenReturn(1);
        var after = new InvStockBalance();
        after.setOnHandQuantity(new BigDecimal("0.875"));
        when(balances.selectOne(any())).thenReturn(after);
        doAnswer(
                        a -> {
                            ((InvLedger) a.getArgument(0)).setId(7L);
                            return 1;
                        })
                .when(ledger)
                .insert(any(InvLedger.class));
        var ids =
                service.postExact(
                        new StockPostingService.ExactPostingDoc(
                                "O",
                                "OUT",
                                "DOC",
                                "PRODUCTION",
                                LocalDateTime.now(),
                                "A",
                                List.of(
                                        new StockPostingService.ExactLine(
                                                1,
                                                "I",
                                                "L",
                                                new BigDecimal("0.125"),
                                                1600L,
                                                "CNY"))));
        assertThat(ids).containsExactly(7L);
        var captured = ArgumentCaptor.forClass(InvLedger.class);
        verify(ledger).insert(captured.capture());
        assertThat(captured.getValue().getQtyDeltaQuantity()).isEqualByComparingTo("-0.125");
        assertThat(captured.getValue().getBalanceAfterQuantity()).isEqualByComparingTo("0.875");
        assertThat(captured.getValue().getUnitCostMinor()).isEqualTo(1600L);
        assertThat(captured.getValue().getCostCurrency()).isEqualTo("CNY");
        verify(balances, never()).applyDelta(anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    void legacyGetterRejectsFractionInsteadOfTruncatingIt() {
        var balance = new InvStockBalance();
        balance.setOnHandQuantity(new BigDecimal("0.875"));
        assertThatThrownBy(balance::getOnHand).isInstanceOf(ArithmeticException.class);
        balance.setOnHand(2);
        assertThat(balance.getOnHand()).isEqualTo(2);
    }

    @Test
    void insufficientExactBalanceCannotProduceReceiptOrLedger() {
        var balances = mock(BalanceMapper.class);
        var ledger = mock(LedgerMapper.class);
        var outbox = mock(OutboxMapper.class);
        when(balances.selectCount(any())).thenReturn(1L);
        var service = new StockPostingServiceImpl(balances, ledger, outbox);
        assertThatThrownBy(
                        () ->
                                service.postExact(
                                        new StockPostingService.ExactPostingDoc(
                                                "O",
                                                "OUT",
                                                "DOC",
                                                "PRODUCTION",
                                                LocalDateTime.now(),
                                                "A",
                                                List.of(
                                                        new StockPostingService.ExactLine(
                                                                1,
                                                                "I",
                                                                "L",
                                                                new BigDecimal("0.125"),
                                                                null)))))
                .isInstanceOf(InventoryException.class);
        verifyNoInteractions(ledger, outbox);
    }
}
