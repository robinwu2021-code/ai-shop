package ai.neargo.shop.paybridge;

import ai.neargo.shop.event.outbox.DeliveryResult;
import ai.neargo.shop.event.outbox.OutboxEnvelope;
import ai.neargo.shop.event.outbox.runtime.TransactionalOutboxHandler;
import ai.neargo.shop.promotion.dto.ActivityBenefitDecided;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PayBridgeOutboxBoundaryTest {
    @Test
    void ownerRecoveryReplaysOriginalIntentOutsidePlatformTransactionAndNeverAcknowledgesUnknown() {
        var database = new EmbeddedDatabaseBuilder().generateUniqueName(true)
                .setType(EmbeddedDatabaseType.H2).build();
        try {
            var jdbc = new JdbcTemplate(database);
            jdbc.execute("CREATE TABLE sys_event_consumed (event_no VARCHAR(64), handler VARCHAR(128),"
                    + " consumed_at TIMESTAMP, PRIMARY KEY(event_no,handler))");
            var coordinator = mock(ActivityBenefitCoordinator.class);
            when(coordinator.recover(any(), eq("INTENT-1"))).thenAnswer(invocation -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                        .as("PAY recovery must not hold a platform transaction").isFalse();
                assertThat(invocation.<EntitlementContext>getArgument(0).requestId())
                        .isEqualTo("INTENT-1");
                return new ActivityBenefitCoordinator.ClaimView("INTENT-1", "COMMITTED", List.of());
            });
            var json = new ObjectMapper();
            var consumer = new ActivityBenefitDecisionConsumer(coordinator, json);
            var handler = new TransactionalOutboxHandler(consumer, database,
                    new DataSourceTransactionManager(database), "sys_event_consumed");
            var fact = new ActivityBenefitDecided("MAIN", "INTENT-1", "USER-1", 1);
            var event = new OutboxEnvelope("EVENT-1", "PLATFORM", "EVENT-1", fact.aggregateType(),
                    fact.aggregateId(), fact.eventType(), 1, "ACTIVITY:INTENT-1", 0,
                    json.writeValueAsString(fact), Instant.EPOCH);
            assertThat(handler.handle(event).kind()).isEqualTo(DeliveryResult.Kind.SENT);
            assertThat(handler.handle(event).kind()).isEqualTo(DeliveryResult.Kind.SENT);
            verify(coordinator, times(2)).recover(any(), eq("INTENT-1"));
            when(coordinator.recover(any(), eq("INTENT-1")))
                    .thenThrow(new IllegalStateException("owner outcome unknown"));
            assertThat(handler.handle(event).kind()).isEqualTo(DeliveryResult.Kind.OUTCOME_UNKNOWN);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_event_consumed", Long.class))
                    .as("independent owner workflow must not use a premature platform inbox acknowledgement")
                    .isZero();
        } finally {
            database.shutdown();
        }
    }
}
