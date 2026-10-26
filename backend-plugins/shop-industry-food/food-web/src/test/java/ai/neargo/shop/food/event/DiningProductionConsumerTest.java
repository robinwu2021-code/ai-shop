package ai.neargo.shop.food.event;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.event.outbox.DeliveryResult;
import ai.neargo.shop.event.outbox.OutboxEnvelope;
import ai.neargo.shop.event.outbox.RetryableOutboxException;
import ai.neargo.shop.food.dto.ProductionContracts.ConsumptionVO;
import ai.neargo.shop.food.service.ProductionConsumptionService;
import ai.neargo.shop.spi.food.DiningServiceEvent;
import ai.neargo.svc.client.CallOutcome;
import ai.neargo.svc.client.ServiceCallException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * The handler is an external workflow: a raw exception becomes OUTCOME_UNKNOWN and the delivery
 * ends FAILED without retry. Replaying consume() is safe (keyed by the original event, re-queries
 * inventory before issuing), so uncertainty must be retried, and deterministic outcomes must end
 * with a named error code instead of an unknown outcome.
 */
class DiningProductionConsumerTest {
    private final ProductionConsumptionService production = mock(ProductionConsumptionService.class);
    private final JsonMapper json = JsonMapper.builder().build();
    private final DiningProductionConsumer consumer = new DiningProductionConsumer(production, json);

    @Test
    void transportFailureIsRetriedNotLeftAsUnknownOutcome() {
        var transport =
                new ServiceCallException(CallOutcome.UNREACHABLE, "food", 0, "connect refused");
        when(production.consume("E1", "S1", "U1", "SEV1")).thenThrow(transport);
        assertThatThrownBy(() -> consumer.handle(event()))
                .isInstanceOf(RetryableOutboxException.class)
                .hasCause(transport);
    }

    @Test
    void timeoutAndRemote5xxAreRetried() {
        when(production.consume("E1", "S1", "U1", "SEV1"))
                .thenThrow(new ServiceCallException(CallOutcome.TIMEOUT, "food", 0, "timeout"))
                .thenThrow(new ServiceCallException(CallOutcome.REMOTE_ERROR, "food", 503, "busy"));
        assertThatThrownBy(() -> consumer.handle(event()))
                .isInstanceOf(RetryableOutboxException.class);
        assertThatThrownBy(() -> consumer.handle(event()))
                .isInstanceOf(RetryableOutboxException.class);
    }

    @Test
    void localUncertainInventoryFailureIsRetried() {
        when(production.consume("E1", "S1", "U1", "SEV1"))
                .thenThrow(new IllegalStateException("inventory call outcome unknown"));
        assertThatThrownBy(() -> consumer.handle(event()))
                .isInstanceOf(RetryableOutboxException.class);
    }

    @Test
    void pendingReceiptIsRetried() {
        when(production.consume("E1", "S1", "U1", "SEV1")).thenReturn(receipt("UNKNOWN", true));
        assertThatThrownBy(() -> consumer.handle(event()))
                .isInstanceOf(RetryableOutboxException.class);
    }

    @Test
    void businessRejectionFailsPermanentlyWithItsCode() {
        when(production.consume("E1", "S1", "U1", "SEV1"))
                .thenThrow(BizException.of(ErrorCode.CONFLICT));
        var result = consumer.handle(event());
        assertThat(result.kind()).isEqualTo(DeliveryResult.Kind.PERMANENT);
        assertThat(result.errorCode()).isEqualTo("PRODUCTION_REJECTED");
        assertThat(result.detail()).isEqualTo("CONFLICT");
    }

    @Test
    void durableBlockedReceiptIsNotRetriedForever() {
        when(production.consume("E1", "S1", "U1", "SEV1")).thenReturn(receipt("BLOCKED", false));
        var result = consumer.handle(event());
        assertThat(result.kind()).isEqualTo(DeliveryResult.Kind.PERMANENT);
        assertThat(result.errorCode()).isEqualTo("PRODUCTION_BLOCKED");
        assertThat(result.detail()).isEqualTo("FCON1");
    }

    @Test
    void confirmedReceiptIsSent() {
        when(production.consume("E1", "S1", "U1", "SEV1")).thenReturn(receipt("CONFIRMED", false));
        assertThat(consumer.handle(event()).kind()).isEqualTo(DeliveryResult.Kind.SENT);
    }

    private static ConsumptionVO receipt(String status, boolean pending) {
        return new ConsumptionVO(
                "FCON1", "SEV1", "PRODUCTION", status, "H", null, null, null, List.of(), null,
                pending, null, null, 0L);
    }

    private OutboxEnvelope event() {
        var source =
                new DiningServiceEvent(
                        "SEV1", "E1", "S1", "B1", "T1", "K1", "L1", null, "HOT", "MAKE", 1L,
                        "START", BigDecimal.ONE, null, "U1", Instant.parse("2026-10-01T00:00:00Z"));
        return new OutboxEnvelope(
                "EV1", "FOOD", "SEV1", "FOOD_KITCHEN_TASK", "K1", "DINING_SERVICE_EVENT", 1,
                "K1", 1, json.writeValueAsString(source), Instant.parse("2026-10-01T00:00:00Z"));
    }
}
