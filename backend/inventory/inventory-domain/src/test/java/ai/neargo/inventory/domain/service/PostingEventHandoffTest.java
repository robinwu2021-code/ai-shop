package ai.neargo.inventory.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.neargo.inventory.domain.entity.InvLedger;
import ai.neargo.inventory.domain.entity.InvOutbox;
import ai.neargo.inventory.domain.entity.InvStockBalance;
import ai.neargo.inventory.domain.mapper.InventoryMappers.BalanceMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.LedgerMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.OutboxMapper;
import ai.neargo.inventory.domain.policy.ExecutionStockRetentionGuard;
import ai.neargo.inventory.domain.service.impl.StockPostingServiceImpl;
import ai.neargo.inventory.domain.support.InvEnums;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * R10.3（ADR-067 决策 10）：宿主接手事件时，过账不再写库存库的 {@code inv_outbox}，
 * 等库存事务提交后才把事件交给宿主；回滚就不交。没有接手的宿主（独立部署）照旧写 {@code inv_outbox}。
 */
@DisplayName("过账事件提交后交给宿主")
class PostingEventHandoffTest {

    private final BalanceMapper balances = mock(BalanceMapper.class);
    private final LedgerMapper ledger = mock(LedgerMapper.class);
    private final OutboxMapper outbox = mock(OutboxMapper.class);
    private final StockPostingServiceImpl service =
            new StockPostingServiceImpl(balances, ledger, outbox, mock(ExecutionStockRetentionGuard.class));

    @AfterEach
    void clear() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private void post() {
        when(balances.selectCount(any())).thenReturn(1L);
        when(balances.applyExactDelta(eq("O"), eq("I"), eq("L"), any())).thenReturn(1);
        var after = new InvStockBalance();
        after.setOnHandQuantity(BigDecimal.ONE);
        when(balances.selectOne(any())).thenReturn(after);
        doAnswer(a -> {
            ((InvLedger) a.getArgument(0)).setId(7L);
            return 1;
        }).when(ledger).insert(any(InvLedger.class));
        service.postExact(new StockPostingService.ExactPostingDoc("O", "OUT", "DOC-1", "PRODUCTION",
                LocalDateTime.now(), "A",
                List.of(new StockPostingService.ExactLine(1, "I", "L", BigDecimal.ONE, null, null))));
    }

    private static InventoryEventSink handoff() {
        InventoryEventSink sink = mock(InventoryEventSink.class);
        when(sink.takesOverAfterCommit()).thenReturn(true);
        when(sink.deliver(any(), any(), any(), any(), any())).thenReturn(true);
        return sink;
    }

    @Test
    @DisplayName("提交后才交给宿主，不写 inv_outbox")
    void handsOverAfterCommit() {
        InventoryEventSink sink = handoff();
        service.setSinks(List.of(sink));
        TransactionSynchronizationManager.initSynchronization();

        post();
        verify(sink, never()).deliver(any(), any(), any(), any(), any());
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);

        verify(sink).deliver(any(), eq("O"), eq(InvEnums.EventType.DOCUMENT_POSTED),
                eq("{\"docNo\":\"DOC-1\",\"docKind\":\"OUT\"}"), any());
        verify(outbox, never()).insert(any(InvOutbox.class));
    }

    @Test
    @DisplayName("库存事务回滚：不交给宿主")
    void rollbackHandsNothing() {
        InventoryEventSink sink = handoff();
        service.setSinks(List.of(sink));
        TransactionSynchronizationManager.initSynchronization();

        post();
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verify(sink, never()).deliver(any(), any(), any(), any(), any());
        verify(outbox, never()).insert(any(InvOutbox.class));
    }

    @Test
    @DisplayName("宿主接手失败只记日志，不影响已提交的过账")
    void handoffFailureIsSwallowedAfterCommit() {
        InventoryEventSink sink = handoff();
        when(sink.deliver(any(), any(), any(), any(), any())).thenThrow(new IllegalStateException("down"));
        service.setSinks(List.of(sink));
        TransactionSynchronizationManager.initSynchronization();

        post();
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(outbox, never()).insert(any(InvOutbox.class));
    }

    @Test
    @DisplayName("没有接手的宿主（独立部署）：照旧写 inv_outbox")
    void withoutHandoffWritesInventoryOutbox() {
        InventoryEventSink sink = mock(InventoryEventSink.class);
        service.setSinks(List.of(sink));

        post();

        verify(outbox).insert(any(InvOutbox.class));
        verify(sink, never()).deliver(any(), any(), any(), any(), any());
    }
}
