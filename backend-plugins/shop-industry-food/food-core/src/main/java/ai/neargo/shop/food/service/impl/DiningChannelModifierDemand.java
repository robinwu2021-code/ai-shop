package ai.neargo.shop.food.service.impl;

import static ai.neargo.shop.food.service.impl.DiningChannelSourceStore.require;

import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.food.dto.DiningChannelSourceFacts.Definition;
import ai.neargo.shop.food.dto.OrderLineInput;
import ai.neargo.shop.food.dto.PublicationSnapshot;
import ai.neargo.shop.food.policy.PublicationDocuments;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanFacts;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Leaf;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.inventory.MaterialCatalogQueryPort;

import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A frozen composite modifier names other modifiers, never material IDs; only their real terminal
 * SKU owners supply materials.
 */
public final class DiningChannelModifierDemand {
    private DiningChannelModifierDemand() {}

    /**
     * Additional material facts retain their real original definitions; the caller assigns explicit
     * recipe or finished-with-modifier phases without manufacturing a recipe revision.
     */
    public static List<Leaf> compile(
            String entity,
            String store,
            String componentRef,
            BigDecimal mainQuantity,
            OrderLineInput input,
            List<Definition> definitions,
            MaterialCatalogQueryPort materials,
            ObjectMapper json) {
        var result = new ArrayList<Leaf>();
        if (input.selections().stream().noneMatch(s -> "MODIFIER".equals(s.sourceKind())))
            return List.of();
        var resolver =
                new DiningChannelOriginalModifierResolver(
                        entity,
                        store,
                        input.menuNo(),
                        input.menuItemNo(),
                        input.menuRevisionNo(),
                        definitions,
                        json);
        for (var selected : input.selections()) {
            if (!"MODIFIER".equals(selected.sourceKind())) continue;
            var root = resolver.modifier(selected);
            require(selected.qty() != null && selected.qty().signum() > 0);
            var body = body(root, json);
            // Saleable selections already own a separate physical branch.
            if (!"COMPONENTS".equals(ai.neargo.shop.food.policy.ModifierInventoryMode.resolve(
                    body.modifier().kind(), body.modifier().data().salesSkuNo(),
                    body.modifier().data().inventoryMode()))) continue;
            if (body.modifier().data().components().isEmpty()) continue;
            BigDecimal quantity = mainQuantity.multiply(selected.qty());
            InventoryDemandPlanFacts.quantity(quantity, false);
            expand(
                    entity,
                    store,
                    componentRef,
                    root,
                    quantity,
                    definitions,
                    materials,
                    json,
                    new HashSet<>(),
                    0,
                    result);
        }
        return List.copyOf(result);
    }

    private static PublicationSnapshot body(Definition definition, ObjectMapper json) {
        require(
                "MODIFIER".equals(definition.kind())
                        && definition
                                .contentHash()
                                .equals(PublicationDocuments.hash(json, definition.snapshot())));
        PublicationDocuments.validate(
                json, definition.kind(), definition.configNo(), definition.snapshot());
        var body = json.readValue(definition.snapshot(), PublicationSnapshot.class);
        require(
                body.modifier() != null
                        && body.modifier().data() != null
                        && definition.configNo().equals(body.modifier().configNo()));
        return body;
    }

    private static void expand(
            String entity,
            String store,
            String componentRef,
            Definition definition,
            BigDecimal quantity,
            List<Definition> definitions,
            MaterialCatalogQueryPort materials,
            ObjectMapper json,
            Set<String> path,
            int depth,
            List<Leaf> result) {
        require(depth <= 8 && path.add(definition.revisionNo()) && result.size() < 200);
        var body = body(definition, json);
        if (body.marketContext() != null)
            require(
                    entity.equals(body.marketContext().entityNo())
                            && store.equals(body.marketContext().storeNo()));
        String sku = body.modifier().data().salesSkuNo();
        if (body.modifier().data().components().isEmpty()
                || "SALES_SKU".equals(ai.neargo.shop.food.policy.ModifierInventoryMode.resolve(
                        body.modifier().kind(), sku, body.modifier().data().inventoryMode()))) {
            require(
                    sku != null
                            && body.skuFacts().stream()
                                    .anyMatch(
                                            f ->
                                                    sku.equals(f.skuNo())
                                                            && entity.equals(f.entityNo())
                                                            && store.equals(f.storeNo())));
            var found = materials.getSkuMaterials(entity, store, List.of(sku));
            require(found != null && found.size() == 1 && sku.equals(found.getFirst().skuNo()));
            var material = found.getFirst().material();
            require(
                    MaterialCatalogFacts.validFact(material)
                            && entity.equals(material.entityNo())
                            && (quantity.stripTrailingZeros().scale() <= 0
                                    || Integer.valueOf(1).equals(material.weighed())));
            InventoryDemandPlanFacts.quantity(quantity, false);
            result.add(
                    new Leaf(
                            PricingSnapshotHash.of(
                                    List.of(componentRef, definition.revisionNo(), result.size())),
                            material.itemId(),
                            quantity,
                            material.baseUom(),
                            material.definitionHash()));
        } else {
            require(
                    "COMPONENTS".equals(ai.neargo.shop.food.policy.ModifierInventoryMode.resolve(
                            body.modifier().kind(), sku, body.modifier().data().inventoryMode()))
                            && "COMBO".equals(body.modifier().kind())
                            && body.modifier().data().components().size() <= 100);
            for (var part : body.modifier().data().components()) {
                require(part.quantity() != null && part.quantity().signum() > 0);
                var edges =
                        body.dependencies().stream()
                                .filter(
                                        d ->
                                                "MODIFIER".equals(d.configKind())
                                                        && part.memberNo().equals(d.configNo()))
                                .toList();
                require(edges.size() == 1);
                var edge = edges.getFirst();
                var children =
                        definitions.stream()
                                .filter(
                                        d ->
                                                edge.revisionNo().equals(d.revisionNo())
                                                        && edge.configKind().equals(d.kind())
                                                        && edge.configNo().equals(d.configNo())
                                                        && edge.revision()
                                                                == d.publicationRevision()
                                                        && edge.contentHash()
                                                                .equals(d.contentHash()))
                                .toList();
                require(children.size() == 1);
                var childQuantity = quantity.multiply(part.quantity());
                InventoryDemandPlanFacts.quantity(childQuantity, false);
                expand(
                        entity,
                        store,
                        componentRef,
                        children.getFirst(),
                        childQuantity,
                        definitions,
                        materials,
                        json,
                        path,
                        depth + 1,
                        result);
            }
        }
        path.remove(definition.revisionNo());
    }
}
