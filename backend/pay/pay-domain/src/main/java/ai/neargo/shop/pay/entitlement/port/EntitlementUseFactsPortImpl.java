package ai.neargo.shop.pay.entitlement.port;

import ai.neargo.shop.pay.entitlement.dto.UseSnapshots.Prepared;
import ai.neargo.shop.pay.entitlement.service.EntitlementUseWriter;
import ai.neargo.shop.pay.entitlement.service.EntitlementSourceFence;
import ai.neargo.shop.pay.mapper.entitlement.EntUseIntentMapper;
import ai.neargo.shop.pay.mapper.entitlement.EntUsageMapper;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementUseFactsPort;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.HoldView;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.State;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.UseCommand;
import java.util.List;
import java.util.Objects;
import tools.jackson.databind.ObjectMapper;

/** Core must post the actual frozen hold, rather than reconstructing discounts or funder from a title. */
public class EntitlementUseFactsPortImpl implements EntitlementUseFactsPort {
    private final EntUseIntentMapper intents;
    private final EntUsageMapper usages;
    private final EntitlementUseWriter writer;
    private final ObjectMapper json;
    private final EntitlementSourceFence fences;
    public EntitlementUseFactsPortImpl(EntUseIntentMapper intents, EntUsageMapper usages, EntitlementUseWriter writer,
                                       EntitlementSourceFence fences, ObjectMapper json) {
        this.intents = intents;
        this.usages = usages;
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
        var operation = writer.view(row);
        var command = original.command();
        if (operation.status() == State.COMMITTED && "CORE".equals(command.sourceRef().sourceSystem())
                && "SERVICE_USE".equals(command.sourceRef().sourceType())
                && !matchesCommittedUsage(context, operation, command)) {
            return new UseFact(new HoldView(operationNo, State.PROCESSING, null, null, null),
                    null, null, List.of(), null);
        }
        return new UseFact(operation, command, original.definition(), original.couponAllocations(),
                original.source().orderSnapshotHash());
    }

    private boolean matchesCommittedUsage(EntitlementContext context, HoldView operation, UseCommand command) {
        var usage = usages.findCommittedStandard(context.tenantNo(), operation.usageNo());
        return usage != null && command.operationNo().equals(usage.getOperationNo())
                && command.sourceRef().sourceNo().equals(usage.getSourceOrderNo())
                && command.userNo().equals(usage.getUserNo())
                && command.entityNo().equals(usage.getEntityNo())
                && command.storeNo().equals(usage.getStoreNo())
                && command.assetKind().name().equals(usage.getAssetKind())
                && command.currency().equals(usage.getCurrency())
                && Objects.equals(command.units(), usage.getUnits())
                && Objects.equals(command.amountMinor(), usage.getAmountMinor());
    }
}
