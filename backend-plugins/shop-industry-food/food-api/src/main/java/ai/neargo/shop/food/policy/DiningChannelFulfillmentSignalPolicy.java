package ai.neargo.shop.food.policy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.food.service.DiningChannelFulfillmentSignalPort;
import ai.neargo.shop.spi.food.DiningServiceEvent;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

/**
 * Scope mismatches are invalid source facts; expired read evidence is retryable and never
 * authorizes publication.
 */
public final class DiningChannelFulfillmentSignalPolicy {
    private DiningChannelFulfillmentSignalPolicy() {}

    public static DiningChannelFulfillmentSignalPort.Signal validate(
            DiningServiceEvent event,
            DiningChannelFulfillmentSignalPort.Signal result,
            Clock clock) {
        check(
                result != null
                        && result.status() != null
                        && same(event, result.originalEvent())
                        && result.observedAt() != null
                        && result.validUntil() != null);
        if (result.observedAt().isAfter(clock.instant())
                || !result.validUntil().isAfter(clock.instant()))
            throw new IllegalStateException(
                    "Original fulfillment signal evidence expired or unavailable");
        check(!result.validUntil().isAfter(result.observedAt().plusSeconds(30)));
        if (result.status() == DiningChannelFulfillmentSignalPort.Status.READY) {
            text(result.importNo(), 32);
            text(result.batchNo(), 32);
            text(result.coreOrderNo(), 32);
            text(result.custodyOperationNo(), 32);
            check(
                    event.batchNo().equals(result.batchNo())
                            && result.sourceRevision() != null
                            && result.sourceRevision().matches("CUSTODY_V1:[a-f0-9]{64}"));
        } else
            check(
                    result.importNo() == null
                            && result.batchNo() == null
                            && result.coreOrderNo() == null
                            && result.custodyOperationNo() == null
                            && result.sourceRevision() == null);
        var receipt =
                "FOOD:FULFILLMENT_SIGNAL:"
                        + PricingSnapshotHash.of(
                                List.of(
                                        result.status(),
                                        result.originalEvent(),
                                        Objects.toString(result.importNo(), ""),
                                        Objects.toString(result.batchNo(), ""),
                                        Objects.toString(result.coreOrderNo(), ""),
                                        Objects.toString(result.custodyOperationNo(), ""),
                                        Objects.toString(result.sourceRevision(), "")));
        check(receipt.equals(result.originalReceiptRef()));
        return result;
    }

    private static boolean same(DiningServiceEvent a, DiningServiceEvent b) {
        return a != null
                && b != null
                && Objects.equals(a.eventNo(), b.eventNo())
                && Objects.equals(a.entityNo(), b.entityNo())
                && Objects.equals(a.storeNo(), b.storeNo())
                && Objects.equals(a.batchNo(), b.batchNo())
                && Objects.equals(a.ticketNo(), b.ticketNo())
                && Objects.equals(a.taskNo(), b.taskNo())
                && Objects.equals(a.foodLineNo(), b.foodLineNo())
                && Objects.equals(a.componentNo(), b.componentNo())
                && Objects.equals(a.stationNo(), b.stationNo())
                && Objects.equals(a.taskRole(), b.taskRole())
                && a.routeVersion() == b.routeVersion()
                && Objects.equals(a.action(), b.action())
                && a.qty() != null
                && b.qty() != null
                && a.qty().compareTo(b.qty()) == 0
                && Objects.equals(a.reason(), b.reason())
                && Objects.equals(a.actorNo(), b.actorNo())
                && Objects.equals(a.occurredAt(), b.occurredAt());
    }

    private static void text(String value, int max) {
        check(value != null && !value.isBlank() && value.length() <= max);
    }

    private static void check(boolean valid) {
        if (!valid) throw BizException.of(ErrorCode.CONFLICT);
    }
}
