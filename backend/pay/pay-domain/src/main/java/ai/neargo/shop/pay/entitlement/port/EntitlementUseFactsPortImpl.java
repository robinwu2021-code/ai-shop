package ai.neargo.shop.pay.entitlement.port;

import ai.neargo.shop.pay.entitlement.dto.UseSnapshots.Prepared;
import ai.neargo.shop.pay.entitlement.service.EntitlementUseWriter;
import ai.neargo.shop.pay.entitlement.service.EntitlementSourceFence;
import ai.neargo.shop.pay.mapper.entitlement.EntUseIntentMapper;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementUseFactsPort;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.HoldView;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.State;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Core must post the actual frozen hold, rather than reconstructing discounts or funder from a title. */
@Component
public class EntitlementUseFactsPortImpl implements EntitlementUseFactsPort {
    private final EntUseIntentMapper intents;
    private final EntitlementUseWriter writer;
    private final ObjectMapper json;
    private final EntitlementSourceFence fences;
    public EntitlementUseFactsPortImpl(EntUseIntentMapper intents, EntitlementUseWriter writer,
                                       EntitlementSourceFence fences, ObjectMapper json) {
        this.intents = intents;
        this.writer = writer;
        this.json = json;
        this.fences = fences;
    }
    @Override
    public UseFact query(EntitlementContext context, String entityNo, String operationNo) {
        var row = intents.find(context.tenantNo(), operationNo);
        if (row == null) {
            var fence = fences.find(context, "USE", operationNo);
            if (fences.cancelled(fence) && entityNo.equals(fence.getEntityNo())) {
                return new UseFact(writer.cancelled(fence), null, null, List.of(), null);
            }
        }
        if (row == null || !entityNo.equals(row.getEntityNo())) {
            return new UseFact(new HoldView(operationNo, State.NOT_FOUND, null, null, null),
                    null, null, List.of(), null);
        }
        var original = json.readValue(row.getCommandSnapshot(), Prepared.class);
        return new UseFact(writer.view(row), original.command(), original.definition(), original.couponAllocations(),
                original.source().orderSnapshotHash());
    }
}
