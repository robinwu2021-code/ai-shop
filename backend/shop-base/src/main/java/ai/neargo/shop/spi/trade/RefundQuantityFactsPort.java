package ai.neargo.shop.spi.trade;

import java.time.Instant;
import java.util.List;

/** Actual successful refund quantities, without exposing the trade owner's refund plan model. */
public interface RefundQuantityFactsPort {
    /** Previously processed execution identities are skipped before their original plans are decoded. */
    List<Fact> succeeded(String tenantNo, String subOrderNo, Instant through, List<String> processed);

    record LineQuantity(long itemId, int qty) { }

    record Fact(String executionNo, String subOrderNo, List<LineQuantity> lines, long succeededAt) {
        public Fact {
            lines = List.copyOf(lines);
        }
    }
}
