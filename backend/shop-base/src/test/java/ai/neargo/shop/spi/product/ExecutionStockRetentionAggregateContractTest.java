package ai.neargo.shop.spi.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.spi.trade.ExecutionReference;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionScope;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Source fixtures: full parent identity, strict schema dispatch and permanent acceptance addresses. */
class ExecutionStockRetentionAggregateContractTest {
    private static final String HASH = "a".repeat(64);

    private ExecutionStockRetentionPort.RetentionLookup lookup(String authority) {
        var ref = new ExecutionReference("SERVICE_EXECUTION_REFERENCE_V1", new ExecutionScope("MAIN", "ENTITY",
                "STORE"), ExecutionReference.SourceKind.STORE_ORDER, "MANUAL_EXECUTION_V1", "EXEC", "GRANT",
                "UNIT", "TASK", "WORK", 1, 1, HASH, HASH, HASH, HASH, HASH, "FENCE", 1, HASH, "BTRC", HASH,
                "PREPARE", HASH, HASH, null).signed();
        var needs = List.of(new StockDispositionPort.Need("SKU", "STORE", "FULFILLMENT", 2));
        var original = new StockDispositionPort.Original("MAIN", "ENTITY", "ORDER", authority, 2,
                StockDispositionContract.manifestHash(needs), needs);
        return ExecutionStockRetentionContract.request(ref, original, StockDispositionContract.operationNo(original))
                .lookup();
    }

    @Test
    void platformComponentRetainsTheCompleteDualParentAndCannotAliasNativeIdentity() {
        var dual = lookup("DUAL");
        var component = PlatformExecutionStockRetentionComponent.descriptor(dual, "CONFIRMATION");
        component.validate();
        assertThat(component.original()).isEqualTo(dual);
        assertThat(component.componentNo()).isNotEqualTo(PlatformExecutionStockRetentionComponent
                .descriptor(lookup("PLATFORM"), "CONFIRMATION").componentNo());
        assertThatThrownBy(() -> PlatformExecutionStockRetentionComponent.descriptor(lookup("INVENTORY"),
                "CONFIRMATION").validate()).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void underspecifiedDualEnumCannotEnterNativeV1Schema() {
        var nativeValue = InventoryExecutionStockRetentionContract.nativeComponent(lookup("INVENTORY"), "CONFIRM");
        var forged = new InventoryExecutionStockRetentionPort.ComponentDescriptor(nativeValue.schema(),
                nativeValue.componentNo(), lookup("DUAL"), InventoryExecutionStockRetentionPort.ProofMode.DUAL_MIRROR,
                nativeValue.expectedNeeds(), nativeValue.parentManifestHash(), nativeValue.componentManifestHash(),
                nativeValue.sourceConfirmationNo(), nativeValue.componentHash());
        assertThatThrownBy(forged::validate).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptedRouteAddressBindsFullParentAndContainsNoCurrentObservation() {
        assertThat(ExecutionStockRetentionAggregateContract.routeNo(lookup("DUAL")))
                .isEqualTo(ExecutionStockRetentionAggregateContract.routeNo(lookup("DUAL")))
                .isNotEqualTo(ExecutionStockRetentionAggregateContract.routeNo(lookup("PLATFORM")));
        assertThat(ExecutionStockRetentionAggregateContract.compatible(lookup("DUAL"), null)).isFalse();
    }
    @Test
    void dualSchemaCannotUseNativeProofOrReceiptDomain() {
        var original = lookup("DUAL");
        var proof = new ExecutionStockMirrorEvidencePort.MirrorOriginalProof(
                "EXECUTION_STOCK_MIRROR_ORIGINAL_V2", original, "CONFIRM", original.reference().referenceHash(),
                original.requestHash(), ExecutionStockRetentionContract.originalHash(original.stockOriginal()),
                original.stockOriginal().manifestHash(), original.stockOriginal().needs(), 2,
                List.of("INV_MIRROR_RESERVE", "INV_MIRROR_ALLOCATE"),
                List.of(event(1, "INV_MIRROR_RESERVE"), event(2, "INV_MIRROR_ALLOCATE")), HASH,
                original.stockOriginal().manifestHash(), null).signed();
        var dual = InventoryExecutionStockRetentionContract.dualComponent(original, "CONFIRM", proof);
        dual.validate();
        InventoryExecutionStockRetentionContract.validateMirrorBinding(dual, proof);
        assertThat(InventoryExecutionStockRetentionContract.proofDomain(dual))
                .isEqualTo("INVENTORY_EXECUTION_DUAL_PROOF_V2");
        assertThat(InventoryExecutionStockRetentionContract.receiptSchema(dual))
                .isEqualTo("INVENTORY_EXECUTION_DUAL_RETENTION_RECEIPT_V2");
        var substituted = new InventoryExecutionStockRetentionPort.ComponentDescriptor(
                "INVENTORY_EXECUTION_COMPONENT_V1", dual.componentNo(), dual.original(), dual.proofMode(),
                dual.expectedNeeds(), dual.parentManifestHash(), dual.componentManifestHash(),
                dual.sourceConfirmationNo(), dual.componentHash());
        assertThatThrownBy(substituted::validate).isInstanceOf(IllegalArgumentException.class);
    }

    private ExecutionStockMirrorEvidencePort.MirrorEventProof event(long id, String type) {
        String items = "INV_MIRROR_RESERVE".equals(type)
                ? "[{\"skuNo\":\"SKU\",\"qty\":2,\"storeNo\":\"STORE\","
                        + "\"fulfillmentRef\":\"FULFILLMENT\",\"lifecycleVersion\":2}]" : "[]";
        String payload = "{\"type\":\"" + type + "\",\"ref\":\"ORDER\",\"items\":" + items
                + ",\"orderNo\":\"ORDER\"}";
        String raw = StockDispositionContract.hash(payload);
        String number = "EVENT" + id;
        String legacy = ai.neargo.shop.spi.trade.ExecutionEvidenceContract.hash(
                "EXECUTION_STOCK_MIRROR_LEGACY_ENVELOPE_V2", List.of(id, number, "INVENTORY", "ORDER", type,
                        payload, raw, java.time.Instant.EPOCH));
        return new ExecutionStockMirrorEvidencePort.MirrorEventProof(id, number, "INVENTORY", "ORDER", type,
                payload, raw, java.time.Instant.EPOCH, null, null, null, null, null, null, null, null, legacy, HASH);
    }

}
