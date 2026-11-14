package ai.neargo.shop.commerce.policy;

import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.common.pricing.SourceQuantityUnits;
import ai.neargo.shop.spi.commerce.ChannelOrderGateway.Line;
import ai.neargo.shop.spi.commerce.ChannelOrderIngestPort.Accepted;
import ai.neargo.shop.spi.commerce.ChannelOrderIngestPort.Command;
import ai.neargo.shop.spi.commerce.ChannelOrderIngestPort.Reference;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Checks conservation of confirmed original allocations; it never invents a new component price or
 * physical demand.
 */
public final class ChannelOrderAllocationPolicy {
    private ChannelOrderAllocationPolicy() {}

    /**
     * Requires complete original line/component lineage before business confirmation can become
     * locally visible.
     */
    public static void validate(Command command, Accepted accepted) {
        require(
                command != null
                        && command.original() != null
                        && command.original().document() != null);
        var document = command.original().document();
        var reference =
                new Reference(
                        command.importNo(),
                        command.entityNo(),
                        command.storeNo(),
                        command.original().documentHash());
        require(
                accepted != null
                        && reference.equals(accepted.reference())
                        && PricingSnapshotHash.of(document).equals(reference.documentHash()));
        CommerceValidation.text(accepted.orderNo(), 64);
        CommerceValidation.text(accepted.businessDocumentNo(), 64);
        CommerceValidation.text(accepted.acceptanceReceiptRef(), 256);
        require(!accepted.allocations().isEmpty() && accepted.allocations().size() <= 10000);
        var originals = new HashMap<String, Line>();
        document.lines()
                .forEach(line -> require(originals.putIfAbsent(line.lineRef(), line) == null));
        var amounts = new HashMap<String, Long>();
        var quantities = new HashMap<String, BigDecimal>();
        var identities = new HashSet<List<String>>();
        for (var allocation : accepted.allocations()) {
            require(allocation != null);
            CommerceValidation.text(allocation.externalLineRef(), 128);
            CommerceValidation.text(allocation.sourceLineRef(), 128);
            CommerceValidation.text(allocation.componentRef(), 128);
            CommerceValidation.text(allocation.coreItemRef(), 64);
            CommerceValidation.text(allocation.allocationEvidenceRef(), 256);
            CommerceValidation.text(allocation.unit(), 16);
            require(
                    allocation.quantity() != null
                            && allocation
                                    .quantity()
                                    .matches("(?:0|[1-9][0-9]{0,13})(?:\\.[0-9]{1,6})?"));
            require(new BigDecimal(allocation.quantity()).signum() > 0);
            require(
                    allocation.amountMinor() >= 0
                            && document.currency().equals(allocation.currency())
                            && allocation.kind() != null
                            && Set.of("MAIN", "OPTION", "SKU", "BUNDLE_MEMBER")
                                    .contains(allocation.kind()));
            boolean main = "MAIN".equals(allocation.kind());
            String root = main ? allocation.externalLineRef() : allocation.parentExternalLineRef();
            require(root != null && (!main || allocation.parentExternalLineRef() == null));
            var original = originals.get(root);
            require(original != null);
            if (main) {
                require(SourceQuantityUnits.same(original.unit(), allocation.unit()));
                quantities.merge(root, new BigDecimal(allocation.quantity()), BigDecimal::add);
            } else {
                CommerceValidation.text(allocation.parentExternalLineRef(), 128);
                require(
                        original.selections().stream()
                                .anyMatch(
                                        selection ->
                                                selection
                                                                .lineRef()
                                                                .equals(
                                                                        allocation
                                                                                .externalLineRef())
                                                        && selection
                                                                .kind()
                                                                .equals(allocation.kind())));
            }
            require(
                    identities.add(
                            List.of(
                                    allocation.externalLineRef(),
                                    allocation.componentRef(),
                                    allocation.coreItemRef())));
            amounts.merge(root, allocation.amountMinor(), Math::addExact);
        }
        for (var line : document.lines()) {
            require(
                    amounts.getOrDefault(line.lineRef(), -1L) == line.netMinor()
                            && quantities.containsKey(line.lineRef())
                            && quantities
                                            .get(line.lineRef())
                                            .compareTo(new BigDecimal(line.quantity()))
                                    == 0);
        }
    }

    private static void require(boolean valid) {
        if (!valid)
            throw new IllegalStateException("Invalid original core line allocation evidence");
    }
}
