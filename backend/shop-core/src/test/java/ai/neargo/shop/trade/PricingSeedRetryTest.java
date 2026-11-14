package ai.neargo.shop.trade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import ai.neargo.shop.common.pricing.PricingCapabilities;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.idem.SysIdempotentMapper;
import ai.neargo.shop.trade.dto.PricingConfigCommands.Context;
import ai.neargo.shop.trade.mapper.TradeMappers.PricingTemplateMapper;
import ai.neargo.shop.trade.service.PricingSeedService.ImportMode;
import ai.neargo.shop.trade.service.PricingSeedService.ImportReport;
import ai.neargo.shop.trade.service.impl.PricingSeedServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import tools.jackson.databind.json.JsonMapper;

class PricingSeedRetryTest {
    @Test
    void transientReceiptDeadlockRetriesInANewTransactionAndExhaustionIsBounded() throws Exception {
        run(1, false, 2);
        run(10, true, 3);
    }

    private static void run(int failures, boolean exhausted, int expectedAttempts) throws Exception {
        var mapper = mock(PricingTemplateMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of());
        var attempts = new AtomicInteger();
        var report = new ImportReport(ImportMode.DRAFT, "TEST", "hash", 20, 3, 2, List.of());
        var idempotency = new IdempotencyService(mock(SysIdempotentMapper.class), JsonMapper.builder().build()) {
            @Override
            public <T> T execute(String key, String endpoint, String actor, String hash, Class<T> type,
                    Supplier<T> action) {
                if (attempts.incrementAndGet() <= failures) {
                    throw new PessimisticLockingFailureException("Injected receipt deadlock");
                }
                return type.cast(report);
            }
        };
        var manager = new CountingTransactions();
        var service = new PricingSeedServiceImpl(mapper, idempotency, manager);
        var identity = new Context("MAIN", "OPS", true, null, null,
                new PricingCapabilities(0, 0, false, false, false), "DRAFT_ONLY", Instant.EPOCH, Set.of());
        var seed = Files.readString(Path.of("../../docs/technical/design/schema/pricing-initial-data.example.json"));
        if (exhausted) {
            assertThatThrownBy(() -> service.importDrafts(identity, seed, "PRICING_RULE_SPEC_V4", ImportMode.DRAFT))
                    .isInstanceOf(PessimisticLockingFailureException.class);
        } else {
            assertThat(service.importDrafts(identity, seed, "PRICING_RULE_SPEC_V4", ImportMode.DRAFT))
                    .isEqualTo(report);
        }
        assertThat(attempts.get()).isEqualTo(expectedAttempts);
        assertThat(manager.begun).isEqualTo(expectedAttempts);
        assertThat(manager.rolledBack).isEqualTo(Math.min(failures, expectedAttempts));
        assertThat(manager.committed).isEqualTo(exhausted ? 0 : 1);
    }

    private static class CountingTransactions extends AbstractPlatformTransactionManager {
        int begun;
        int committed;
        int rolledBack;
        @Override
        protected Object doGetTransaction() { return new Object(); }
        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) { begun++; }
        @Override
        protected void doCommit(DefaultTransactionStatus status) { committed++; }
        @Override
        protected void doRollback(DefaultTransactionStatus status) { rolledBack++; }
    }
}
