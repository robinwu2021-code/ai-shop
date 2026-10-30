package ai.neargo.shop.spi.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.spi.product.InventoryExecutionStockRetentionPort.ComponentDescriptor;
import ai.neargo.shop.spi.product.InventoryExecutionStockRetentionPort.ProofMode;
import ai.neargo.shop.spi.trade.ExecutionReference;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionScope;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Full native descriptors keep parent request, prepared revision, source identity and confirmation inseparable. */
class InventoryExecutionStockRetentionContractTest {
    private static final String HASH = "a".repeat(64);

    private ExecutionReference reference(String payload) {
        return new ExecutionReference("SERVICE_EXECUTION_REFERENCE_V1", new ExecutionScope("MAIN", "ENTITY", "STORE"),
                ExecutionReference.SourceKind.STORE_ORDER, "MANUAL_EXECUTION_V1", "EXEC", "GRANT", "UNIT", "TASK",
                "WORK", 1, 1, HASH, payload, HASH, HASH, HASH, "FENCE", 1, HASH, "BTRC", HASH, "PREPARE", HASH,
                HASH, null).signed();
    }

    private ComponentDescriptor nativeComponent(String payload) {
        var needs = List.of(new StockDispositionPort.Need("SKU", "STORE", "FULFILLMENT", 2));
        var original = new StockDispositionPort.Original("MAIN", "ENTITY", "ORDER", "INVENTORY", 2,
                StockDispositionContract.manifestHash(needs), needs);
        var lookup = ExecutionStockRetentionContract.request(reference(payload), original,
                StockDispositionContract.operationNo(original)).lookup();
        return InventoryExecutionStockRetentionContract.nativeComponent(lookup, "CONFIRMATION");
    }

    @Test
    void creditReturnOriginalDoesNotEnableUnsupportedExecutionRetentionEvidence() {
        var needs = List.of(new StockDispositionPort.Need("SKU", "STORE", "FULFILLMENT", 2));
        var credit = new StockDispositionPort.Original("MAIN", "ENTITY", "ORDER", "INVENTORY", 3,
                StockDispositionContract.manifestHash(needs), needs);
        assertThatThrownBy(() -> ExecutionStockRetentionContract.request(reference(HASH), credit,
                StockDispositionContract.operationNo(credit))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nativeComponentBindsTheFullReferenceAndPreservesParentManifest() {
        var component = nativeComponent(HASH);
        component.validate();
        assertThat(component.proofMode()).isEqualTo(ProofMode.NATIVE_CONFIRMATION);
        assertThat(component.expectedNeeds()).isEqualTo(component.original().stockOriginal().needs());
        assertThat(component.componentManifestHash()).isEqualTo(component.parentManifestHash());
        assertThat(nativeComponent("b".repeat(64)).componentNo()).isNotEqualTo(component.componentNo());
        assertThat(nativeComponent(HASH)).isEqualTo(component);
    }

    @Test
    void shortenedOrReinterpretedDescriptorCannotReuseACompleteOriginalDigest() {
        var component = nativeComponent(HASH);
        var shortened = new ComponentDescriptor(component.schema(), component.componentNo(), component.original(),
                component.proofMode(), List.of(new StockDispositionPort.Need("SKU", "STORE", "FULFILLMENT", 1)),
                component.parentManifestHash(), component.componentManifestHash(), component.sourceConfirmationNo(),
                component.componentHash());
        assertThatThrownBy(shortened::validate).isInstanceOf(IllegalArgumentException.class);
        var forged = new ComponentDescriptor(component.schema(), component.componentNo(), component.original(),
                ProofMode.DUAL_MIRROR, component.expectedNeeds(), component.parentManifestHash(),
                component.componentManifestHash(), component.sourceConfirmationNo(), component.componentHash());
        assertThatThrownBy(forged::validate).isInstanceOf(IllegalArgumentException.class);
    }
    @Test
    void nativeV1PermanentHashDomainsRemainFixedAcrossDualExtension() {
        var component = nativeComponent(HASH);
        assertThat(component.componentNo()).isEqualTo("6dc0854446999b5cdd2f501768e6e66404a67c44f2d108c74a22d13813fc0dd2");
        assertThat(component.componentHash()).isEqualTo("19359609e05095e165330354fa65ac221487fc7d6dbb4dafe1d36f944f5207a9");
        assertThat(component.original().requestHash()).isEqualTo("fbafd2bc5153cc2ebb371d3060ba28fe6a5bbc780963cc0dc560ae008b06fc89");
        assertThat(component.original().retentionNo()).isEqualTo("dc73f4aac0885925f307330f4205665ea698385d6d35f8addadff28dfc689023");
    }

    @Test
    void frozenNativeConsumedOwnerProofAndIssuedObservationUseIndependentLiteralGoldenVectors() {
        var component = nativeComponent(HASH);
        var heads = List.of(new InventoryExecutionStockRetentionPort.HeadProof("OWNER", "RESERVATION", "ORDER", 2,
                component.parentManifestHash(), component.original().confirmationOperationNo(), 2, "COMMITTED",
                        "OUTBOUND"));
        var lines = List.of(new InventoryExecutionStockRetentionPort.LineProof(7, 1, "OWNER", "RESERVATION", "ITEM",
                "LOCATION", "SKU", "STORE", "FULFILLMENT", 2, 2, 0, "OUTBOUND"));
        var outboundLines = List.of(new InventoryExecutionStockRetentionPort.OutboundLineProof(8, 1, "OWNER", "ITEM",
                2, 17L));
        var ledger = List.of(new InventoryExecutionStockRetentionPort.LedgerProof(9, 1, "OWNER", "ITEM", "LOCATION",
                "OUT", "OUTBOUND", "SALE", -2, 98, 17L));
        var outbounds = List.of(new InventoryExecutionStockRetentionPort.OutboundProof("OUTBOUND", "OWNER", "LOCATION",
                "ORDER", "RESERVATION", "POSTED", "SALE", 2, outboundLines, ledger, List.of()));
        String ownerSet = InventoryExecutionStockRetentionContract.ownerSetHash(heads, lines, outbounds);
        String issued = InventoryExecutionStockRetentionContract.issueObservationHash(component, heads, lines,
                outbounds);
        assertThat(ownerSet).isEqualTo("a29d18c25e697de25b582ba5d1395b8ea3a10e2aca0b365dc726d75885a9123e");
        assertThat(issued).isEqualTo("d4e6101f2bb9274429db68e8f43b0587d0566d078f2a220e231a1d2ae3eeff78");
        var proof = new InventoryExecutionStockRetentionPort.InventoryProof(component, HASH, "b".repeat(64), ownerSet,
                heads, lines, outbounds, List.of(), List.of(), "CONSUMED", "RETAINED", 1, issued,
                "4e7308ba17cb7c57f82e16806257a1de0a58d384747c0e17ab5a3e3247781976");
        proof.validate();
        assertThat(ai.neargo.shop.spi.trade.ExecutionEvidenceContract.digest("INVENTORY_EXECUTION_PROOF_V1", proof,
                "proofHash")).isEqualTo("4e7308ba17cb7c57f82e16806257a1de0a58d384747c0e17ab5a3e3247781976");
    }

}
