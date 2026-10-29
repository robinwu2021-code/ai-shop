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
        ExecutionEvidenceContract.hashValue(value.componentNo());
        ExecutionEvidenceContract.hashValue(value.componentHash());
        ExecutionStockRetentionContract.validate(original.reference(), original.stockOriginal(),
                original.confirmationOperationNo(), original.retentionNo(), original.requestHash());
        ExecutionEvidenceContract.reference(value.sourceConfirmationNo(), 64);
        boolean nativeV1 = "INVENTORY_EXECUTION_COMPONENT_V1".equals(value.schema());
        ExecutionEvidenceContract.require(!value.expectedNeeds().isEmpty()
                && original.stockOriginal().manifestHash().equals(value.parentManifestHash())
                && StockDispositionContract.manifestHash(value.expectedNeeds()).equals(value.componentManifestHash())
                && value.componentHash().equals(ExecutionEvidenceContract.digest(value.schema(), value,
                        "componentHash")));
        if (nativeV1) {
            ExecutionEvidenceContract.require(value.proofMode() == ProofMode.NATIVE_CONFIRMATION
                    && "INVENTORY".equals(original.stockOriginal().authority())
                    && original.stockOriginal().needs().equals(value.expectedNeeds())
                    && value.componentNo().equals(ExecutionEvidenceContract.hash("INVENTORY_EXECUTION_COMPONENT_NO_V1",
                            List.of(original, value.proofMode(), value.sourceConfirmationNo()))));
        } else {
            ExecutionEvidenceContract.require("INVENTORY_EXECUTION_DUAL_COMPONENT_V2".equals(value.schema())
                    && value.proofMode() == ProofMode.DUAL_MIRROR
                    && "DUAL".equals(original.stockOriginal().authority()));
            for (var need : value.expectedNeeds()) {
                ExecutionEvidenceContract.require(original.stockOriginal().needs().stream().anyMatch(parent ->
                        parent.skuNo().equals(need.skuNo())
                        && java.util.Objects.equals(parent.sourceStoreNo(), need.sourceStoreNo())
                        && parent.fulfillmentRef().equals(need.fulfillmentRef())
                                && parent.quantity() >= need.quantity()));
            }
            ExecutionEvidenceContract.require(value.expectedNeeds().equals(StockDispositionContract.needs(
                    value.expectedNeeds().stream().map(n -> new StockPort.SkuQty(n.skuNo(), n.quantity(),
                            n.sourceStoreNo(), n.fulfillmentRef(),
                                original.stockOriginal().lifecycleVersion())).toList())));
        }
    }

    public static ComponentDescriptor dualComponent(RetentionLookup original, String sourceConfirmationNo,
            ExecutionStockMirrorEvidencePort.MirrorOriginalProof proof) {
        proof.validate();
        ExecutionEvidenceContract.require(original.equals(proof.original()) && !proof.expectedNeeds().isEmpty()
                && sourceConfirmationNo.equals(proof.sourceConfirmationNo()));
        String number = ExecutionEvidenceContract.hash("INVENTORY_EXECUTION_DUAL_COMPONENT_NO_V2",
                List.of(original, sourceConfirmationNo, proof.proofHash()));
        var value = new ComponentDescriptor("INVENTORY_EXECUTION_DUAL_COMPONENT_V2", number, original,
                ProofMode.DUAL_MIRROR, proof.expectedNeeds(), original.stockOriginal().manifestHash(),
                StockDispositionContract.manifestHash(proof.expectedNeeds()), sourceConfirmationNo, null);
        return new ComponentDescriptor(value.schema(), number, original, value.proofMode(), value.expectedNeeds(),
                value.parentManifestHash(), value.componentManifestHash(), sourceConfirmationNo,
                ExecutionEvidenceContract.digest(value.schema(), value, "componentHash"));
    }

    public static void validateMirrorBinding(ComponentDescriptor component,
            ExecutionStockMirrorEvidencePort.MirrorOriginalProof proof) {
        component.validate();
        ExecutionEvidenceContract.require(proof != null);
        proof.validate();
        ExecutionEvidenceContract.require(component.proofMode() == ProofMode.DUAL_MIRROR
                && component.equals(dualComponent(component.original(), component.sourceConfirmationNo(), proof)));
    }

    public static String proofDomain(ComponentDescriptor component) {
        component.validate();
        return switch (component.schema()) {
            case "INVENTORY_EXECUTION_COMPONENT_V1" -> "INVENTORY_EXECUTION_PROOF_V1";
            case "INVENTORY_EXECUTION_DUAL_COMPONENT_V2" -> "INVENTORY_EXECUTION_DUAL_PROOF_V2";
            default -> throw new IllegalArgumentException("unsupported Inventory component schema");
        };
    }

    public static String receiptSchema(ComponentDescriptor component) {
        component.validate();
        return switch (component.schema()) {
            case "INVENTORY_EXECUTION_COMPONENT_V1" -> "INVENTORY_EXECUTION_RETENTION_RECEIPT_V1";
            case "INVENTORY_EXECUTION_DUAL_COMPONENT_V2" -> "INVENTORY_EXECUTION_DUAL_RETENTION_RECEIPT_V2";
            default -> throw new IllegalArgumentException("unsupported Inventory component schema");
        };
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
