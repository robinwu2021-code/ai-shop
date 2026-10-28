package ai.neargo.shop.spi.product;

import ai.neargo.shop.spi.product.ExecutionStockRetentionPort.RetentionLookup;
import ai.neargo.shop.spi.product.InventoryExecutionStockRetentionPort.ComponentDescriptor;
import ai.neargo.shop.spi.product.InventoryExecutionStockRetentionPort.HeadProof;
import ai.neargo.shop.spi.product.InventoryExecutionStockRetentionPort.LineProof;
import ai.neargo.shop.spi.product.InventoryExecutionStockRetentionPort.OutboundProof;
import ai.neargo.shop.spi.product.InventoryExecutionStockRetentionPort.ProofMode;
import ai.neargo.shop.spi.trade.ExecutionEvidenceContract;
import java.util.List;

/** A native component binds full original/source confirmation bytes without changing any B1 hash domain. */
public final class InventoryExecutionStockRetentionContract {
    private InventoryExecutionStockRetentionContract() {
    }

    public static ComponentDescriptor nativeComponent(RetentionLookup original, String sourceConfirmationNo) {
        String number = ExecutionEvidenceContract.hash("INVENTORY_EXECUTION_COMPONENT_NO_V1",
                List.of(original, ProofMode.NATIVE_CONFIRMATION, sourceConfirmationNo));
        var component = new ComponentDescriptor("INVENTORY_EXECUTION_COMPONENT_V1", number, original,
                ProofMode.NATIVE_CONFIRMATION, original.stockOriginal().needs(),
                original.stockOriginal().manifestHash(), original.stockOriginal().manifestHash(),
                sourceConfirmationNo, null);
        return new ComponentDescriptor(component.schema(), component.componentNo(), component.original(),
                component.proofMode(), component.expectedNeeds(), component.parentManifestHash(),
                component.componentManifestHash(), component.sourceConfirmationNo(),
                ExecutionEvidenceContract.digest("INVENTORY_EXECUTION_COMPONENT_V1", component, "componentHash"));
    }

    public static void validate(ComponentDescriptor value) {
        var original = value.original();
        ExecutionStockRetentionContract.validate(original.reference(), original.stockOriginal(),
                original.confirmationOperationNo(), original.retentionNo(), original.requestHash());
        ExecutionEvidenceContract.reference(value.sourceConfirmationNo(), 64);
        ExecutionEvidenceContract.require("INVENTORY_EXECUTION_COMPONENT_V1".equals(value.schema())
                && value.proofMode() != null && !value.expectedNeeds().isEmpty()
                && original.stockOriginal().manifestHash().equals(value.parentManifestHash())
                && StockDispositionContract.manifestHash(value.expectedNeeds()).equals(value.componentManifestHash())
                && value.componentHash().equals(ExecutionEvidenceContract.digest(
                        "INVENTORY_EXECUTION_COMPONENT_V1",
                        value, "componentHash")));
        if (value.proofMode() == ProofMode.NATIVE_CONFIRMATION) {
            ExecutionEvidenceContract.require("INVENTORY".equals(original.stockOriginal().authority())
                    && original.stockOriginal().needs().equals(value.expectedNeeds())
                    && value.componentNo().equals(ExecutionEvidenceContract.hash("INVENTORY_EXECUTION_COMPONENT_NO_V1",
                            List.of(original, value.proofMode(), value.sourceConfirmationNo()))));
        }
    }

    public static String ownerSetHash(List<HeadProof> heads, List<LineProof> lines, List<OutboundProof> outbounds) {
        return ExecutionEvidenceContract.hash("INVENTORY_EXECUTION_OWNER_SET_V1",
                List.of(heads, lines, outbounds, List.of(), List.of()));
    }

    public static String issueObservationHash(ComponentDescriptor component, List<HeadProof> heads,
            List<LineProof> lines, List<OutboundProof> outbounds) {
        ExecutionEvidenceContract.require(!outbounds.isEmpty());
        return ExecutionEvidenceContract.hash("INVENTORY_EXECUTION_ISSUED_V1",
                List.of(component, heads, lines, outbounds));
    }
}
