package ai.neargo.shop.trade.service;

import ai.neargo.shop.spi.trade.IndustryFulfillmentPort.Reference;
import ai.neargo.shop.spi.trade.IndustryOrderAmendmentPort.Command;
import ai.neargo.shop.spi.trade.IndustryOrderAmendmentPort.FeeScopeAdjustment;
import ai.neargo.shop.spi.trade.IndustryOrderAmendmentPort.Receipt;
import ai.neargo.shop.spi.trade.IndustryOrderPort;

import java.util.List;

public interface IndustryOrderAmendmentService {
    Receipt amend(Command command, String key);

    Receipt query(Reference reference, String actor, String key);

    Receipt adjustFee(FeeScopeAdjustment command, String key);

    record EffectiveProjection(
            List<IndustryOrderPort.Allocation> allocations,
            String sourceHash,
            long receivableAdjustmentMinor) {
        public EffectiveProjection {
            allocations = List.copyOf(allocations);
        }
    }

    EffectiveProjection effectiveAllocations(
            Reference reference, List<IndustryOrderPort.Allocation> original);

    int recover(int limit);
}
