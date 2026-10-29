package ai.neargo.shop.food.service.impl;

import static ai.neargo.shop.food.service.impl.DiningChannelSourceStore.require;

import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.food.dto.OrderLineInput;
import ai.neargo.shop.food.entity.FnbChannelOrderSource;
import ai.neargo.shop.food.policy.ProductionDemandPolicy;
import ai.neargo.shop.food.policy.PublicationDocuments;
import ai.neargo.shop.food.policy.RecipePublicationDocuments;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanFacts;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Branch;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Leaf;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Plan;
import ai.neargo.shop.spi.inventory.InventoryReservationPort.Scope;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.inventory.MaterialCatalogQueryPort;

import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * Real frozen recipe demand or real finished-SKU material demand; absence never grants
 * unlimited/no-stock production.
 */
public final class DiningChannelProductionPlanBuilder {
    private final DiningChannelSourceStore store;
    private final MaterialCatalogQueryPort materials;
    private final ObjectMapper json;

    public DiningChannelProductionPlanBuilder(
            DiningChannelSourceStore store, MaterialCatalogQueryPort materials, ObjectMapper json) {
        this.store = store;
        this.materials = materials;
        this.json = json;
    }

    /**
     * Network material facts are loaded outside FOOD transactions; the core persists this exact
     * generation before reserve.
     */
    Plan build(FnbChannelOrderSource source, String reservationRef, String subOrderNo) {
        require(MaterialCatalogFacts.validKey(reservationRef, 32));
        require(MaterialCatalogFacts.validKey(subOrderNo, 32));
        var data = store.query(() -> store.data(source));
        var frozen = store.query(() -> store.frozen(source));
        var branches = new ArrayList<Branch>();
        for (var component : data.components()) {
            if ("BUNDLE_CONTAINER".equals(component.productionKind())) {
                var input =
                        frozen.cartLines().stream()
                                .filter(
                                        line ->
                                                line.sourceLineRef()
                                                        .equals(component.sourceLineRef()))
                                .findFirst()
                                .orElseThrow();
                var modifiers =
                        DiningChannelModifierDemand.compile(
                                source.getEntityNo(),
                                source.getStoreNo(),
                                component.componentRef(),
                                component.physicalQuantity(),
                                input,
                                frozen.original().definitions(),
                                materials,
                                json);
                if (!modifiers.isEmpty()) {
                    require(
                            frozen.kitchenRoutes().routes().stream()
                                    .anyMatch(
                                            route ->
                                                    "MAKE".equals(route.taskRole())
                                                            && component
                                                                    .skuNo()
                                                                    .equals(route.skuNo())));
                    branches.add(
                            new Branch(
                                    component.componentRef(),
                                    subOrderNo,
                                    source.getStoreNo(),
                                    "BUNDLE_MODIFIERS",
                                    component.physicalQuantity(),
                                    component.unit(),
                                    modifiers));
                }
                require(
                        "MAIN".equals(component.externalKind())
                                && data.components().stream()
                                        .anyMatch(
                                                child ->
                                                        "BUNDLE_MEMBER".equals(child.externalKind())
                                                                && component
                                                                        .sourceLineRef()
                                                                        .equals(
                                                                                child
                                                                                        .sourceLineRef())
                                                                && component
                                                                        .externalLineRef()
                                                                        .equals(
                                                                                child
                                                                                        .parentExternalLineRef())));
                continue;
            }
            var input =
                    frozen.cartLines().stream()
                            .filter(line -> line.sourceLineRef().equals(component.sourceLineRef()))
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "Missing original channel cart line"));
            var original =
                    frozen.original().lines().stream()
                            .filter(
                                    line ->
                                            line.externalLineRef()
                                                    .equals(
                                                            component.parentExternalLineRef()
                                                                            == null
                                                                    ? component.externalLineRef()
                                                                    : component
                                                                            .parentExternalLineRef()))
                            .toList();
            require(
                    original.size() == 1
                            && input.menuNo().equals(original.getFirst().menuNo())
                            && input.menuItemNo().equals(original.getFirst().menuItemNo())
                            && input.menuRevisionNo().equals(original.getFirst().menuRevisionNo()));
            var drafts =
                    original.getFirst().components().stream()
                            .filter(
                                    draft ->
                                            PricingSnapshotHash.of(
                                                            List.of(
                                                                    source.getSourceNo(),
                                                                    input.sourceLineRef(),
                                                                    draft.logicalKey()))
                                                    .equals(component.componentRef()))
                            .toList();
            require(drafts.size() == 1);
            var draft = drafts.getFirst();
            List<Leaf> leaves;
            String kind;
            String recipeRevision = draft.recipeRevisionNo();
            if (recipeRevision != null) {
                var definitions =
                        frozen.original().definitions().stream()
                                .filter(
                                        definition ->
                                                "RECIPE".equals(definition.kind())
                                                        && recipeRevision.equals(
                                                                definition.revisionNo()))
                                .toList();
                require(definitions.size() == 1);
                var definition = definitions.getFirst();
                require(
                        definition
                                .contentHash()
                                .equals(PublicationDocuments.hash(json, definition.snapshot())));
                var recipe =
                        RecipePublicationDocuments.validate(
                                json, definition.configNo(), definition.snapshot());
                RecipePublicationDocuments.scope(recipe, source.getEntityNo(), source.getStoreNo());
                require(
                        component.skuNo().equals(recipe.recipe().skuNo())
                                && component.unit().equals(recipe.recipe().yieldUnit()));
                var selection =
                        new OrderLineInput(
                                input.sourceLineRef(),
                                input.menuNo(),
                                input.menuItemNo(),
                                input.menuRevisionNo(),
                                component.goodsNo(),
                                component.skuNo(),
                                component.physicalQuantity(),
                                null,
                                null,
                                "MAIN".equals(component.externalKind())
                                        ? input.selections().stream()
                                                .filter(
                                                        item ->
                                                                "SPEC_VALUE"
                                                                        .equals(item.sourceKind()))
                                                .toList()
                                        : List.of(),
                                List.of());
                leaves =
                        ProductionDemandPolicy.evaluateComponent(
                                        recipe, selection, component.physicalQuantity())
                                .stream()
                                .map(
                                        leaf ->
                                                new Leaf(
                                                        PricingSnapshotHash.of(
                                                                List.of(
                                                                        component.componentRef(),
                                                                        leaf.itemRef())),
                                                        leaf.itemRef(),
                                                        leaf.quantity(),
                                                        leaf.unit(),
                                                        leaf.definitionHash()))
                                .toList();
                if ("MAIN".equals(component.externalKind())) {
                    var all = new ArrayList<>(leaves);
                    all.addAll(
                            DiningChannelModifierDemand.compile(
                                    source.getEntityNo(),
                                    source.getStoreNo(),
                                    component.componentRef(),
                                    component.physicalQuantity(),
                                    input,
                                    frozen.original().definitions(),
                                    materials,
                                    json));
                    var merged = new TreeMap<String, Leaf>();
                    for (var leaf : all) {
                        var prior = merged.get(leaf.itemRef());
                        require(
                                prior == null
                                        || prior.unit().equals(leaf.unit())
                                                && prior.definitionHash()
                                                        .equals(leaf.definitionHash()));
                        BigDecimal quantity =
                                prior == null
                                        ? leaf.quantity()
                                        : prior.quantity().add(leaf.quantity());
                        InventoryDemandPlanFacts.quantity(quantity, false);
                        merged.put(
                                leaf.itemRef(),
                                new Leaf(
                                        PricingSnapshotHash.of(
                                                List.of(component.componentRef(), leaf.itemRef())),
                                        leaf.itemRef(),
                                        quantity,
                                        leaf.unit(),
                                        leaf.definitionHash()));
                    }
                    leaves = List.copyOf(merged.values());
                }
                kind = "RECIPE_MATERIAL";
            } else {
                var modifiers =
                        "MAIN".equals(component.externalKind())
                                ? DiningChannelModifierDemand.compile(
                                        source.getEntityNo(),
                                        source.getStoreNo(),
                                        component.componentRef(),
                                        component.physicalQuantity(),
                                        input,
                                        frozen.original().definitions(),
                                        materials,
                                        json)
                                : List.<Leaf>of();
                var facts =
                        materials.getSkuMaterials(
                                source.getEntityNo(),
                                source.getStoreNo(),
                                List.of(component.skuNo()));
                require(
                        facts != null
                                && facts.size() == 1
                                && component.skuNo().equals(facts.getFirst().skuNo()));
                var material = facts.getFirst().material();
                require(
                        MaterialCatalogFacts.validFact(material)
                                && source.getEntityNo().equals(material.entityNo())
                                && component.unit().equals(material.baseUom())
                                && (component.physicalQuantity().stripTrailingZeros().scale() <= 0
                                        || Integer.valueOf(1).equals(material.weighed())));
                leaves =
                        List.of(
                                new Leaf(
                                        PricingSnapshotHash.of(
                                                List.of(component.componentRef(), "SKU")),
                                        component.skuNo(),
                                        component.physicalQuantity(),
                                        material.baseUom(),
                                        material.definitionHash()));
                kind = "FINISHED_SKU";
                if (!modifiers.isEmpty()) {
                    var combined = new ArrayList<Leaf>();
                    var finished = leaves.getFirst();
                    combined.add(
                            new Leaf(
                                    finished.leafRef(),
                                    finished.itemRef(),
                                    finished.quantity(),
                                    finished.unit(),
                                    finished.definitionHash(),
                                    "FINISHED_SKU"));
                    modifiers.forEach(
                            leaf ->
                                    combined.add(
                                            new Leaf(
                                                    leaf.leafRef(),
                                                    leaf.itemRef(),
                                                    leaf.quantity(),
                                                    leaf.unit(),
                                                    leaf.definitionHash(),
                                                    "RECIPE_MATERIAL")));
                    leaves = List.copyOf(combined);
                    kind = "FINISHED_WITH_MODIFIERS";
                }
            }
            branches.add(
                    new Branch(
                            component.componentRef(),
                            subOrderNo,
                            source.getStoreNo(),
                            kind,
                            component.physicalQuantity(),
                            component.unit(),
                            leaves,
                            recipeRevision));
        }
        var scope = new Scope("MAIN", source.getEntityNo(), reservationRef);
        var raw = new Plan(scope, reservationRef, null, branches);
        return InventoryDemandPlanFacts.validate(
                new Plan(scope, reservationRef, InventoryDemandPlanFacts.hash(raw), branches));
    }
}
