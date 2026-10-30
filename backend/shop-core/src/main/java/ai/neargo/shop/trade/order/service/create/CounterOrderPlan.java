package ai.neargo.shop.trade.order.service.create;

import ai.neargo.shop.spi.product.StockDispositionPort.ReleaseView;
import ai.neargo.shop.spi.product.StockDispositionPort.ReservationView;
import ai.neargo.shop.spi.product.StockPort;
import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutLineReq;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.CustomerParty;
import ai.neargo.shop.trade.service.OrderService.CreateOrderCommand;

import java.util.List;

/**
 * The existing original creation intent stores this exact common writer output and stable
 * identifiers.
 */
public record CounterOrderPlan(
        String orderNo,
        String subOrderNo,
        String operationNo,
        String intentKey,
        String requestHash,
        CustomerParty party,
        CreateOrderCommand command,
        CheckoutEconomicSnapshot economic,
        List<StockPort.SkuQty> stockNeeds,
        String stockAuthority,
        int lifecycleVersion,
        String manifestHash,
        long intentRevision) {
    public CounterOrderPlan {
        stockNeeds = List.copyOf(stockNeeds);
    }

    /**
     * Status-aware intent evidence retains the actual original reserve while release is retried.
     */
    public record CompensationEvidence(
            int schema, ReservationView reservation, ReleaseView release, String reason) {
        public CompensationEvidence {
            if (schema != 1
                    || reservation == null
                    || reason == null
                    || reason.isBlank()
                    || !("RESERVED".equals(reservation.outcome())
                            || "REJECTED".equals(reservation.outcome()))
                    || release != null
                            && (!reservation.original().equals(release.original())
                                    || !reservation
                                            .reservationOperationNo()
                                            .equals(release.reservationOperationNo()))) {
                throw new IllegalArgumentException("invalid counter compensation evidence");
            }
        }
    }

    /** Counter-only command encoding preserves legacy CheckoutCommand serialization unchanged. */
    public record PreparedCommand(
            CreateOrderCommand command, List<CheckoutLineReq> lines, SelectorProof selector) {
        public PreparedCommand {
            if (command == null || selector == null) {
                throw new IllegalArgumentException("counter command");
            }
            lines = List.copyOf(lines);
        }
    }

    /** Safe frozen owner observation; neither a reusable token nor a fabricated USER revision. */
    public record SelectorProof(
            String kind, String guestNo, Long claimRevision, String proofDigest) {
        public SelectorProof {
            if (!("USER".equals(kind) && guestNo == null && claimRevision == null
                            || ("GUEST".equals(kind) || "CLAIMED_GUEST".equals(kind))
                                    && guestNo != null
                                    && !guestNo.isBlank()
                                    && claimRevision != null
                                    && claimRevision > 0)
                    || proofDigest == null
                    || !proofDigest.matches("[a-f0-9]{64}")) {
                throw new IllegalArgumentException("invalid frozen counter selector");
            }
        }
    }
}
