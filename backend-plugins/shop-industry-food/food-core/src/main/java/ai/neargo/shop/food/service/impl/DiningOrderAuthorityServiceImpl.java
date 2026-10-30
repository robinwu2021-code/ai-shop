package ai.neargo.shop.food.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.food.dto.CartAccess;
import ai.neargo.shop.food.dto.DiningPriceFacts;
import ai.neargo.shop.food.dto.OrderLineInput;
import ai.neargo.shop.food.policy.DiningMeasurementPolicy;
import ai.neargo.shop.food.policy.ProductionDemandPolicy;
import ai.neargo.shop.food.policy.PublicationDocuments;
import ai.neargo.shop.food.policy.RecipePublicationDocuments;
import ai.neargo.shop.food.service.ConfigRevisionService;
import ai.neargo.shop.food.service.DiningIdentityService;
import ai.neargo.shop.food.service.DiningOrderAuthorityService;
import ai.neargo.shop.food.service.DiningPricingFactsService;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanFacts;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Branch;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Leaf;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Plan;
import ai.neargo.shop.spi.inventory.InventoryReservationPort;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.inventory.MaterialCatalogQueryPort;
import ai.neargo.shop.spi.inventory.MaterialCatalogQueryPort.MaterialFact;
import ai.neargo.shop.spi.trade.IndustryOrderPort.Command;
import ai.neargo.shop.spi.trade.IndustryOrderPort.Mode;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Delivery;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Party;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope;

import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Builds one immutable physical branch per selected component using the original published
 * definitions.
 */
public class DiningOrderAuthorityServiceImpl implements DiningOrderAuthorityService {
    private final DiningPricingFactsService facts;
    private final DiningIdentityService identities;
    private final ConfigRevisionService revisions;
    private final MaterialCatalogQueryPort materials;
    private final ObjectMapper json;
    private boolean localStock;
    public DiningOrderAuthorityServiceImpl localStock(boolean enabled){this.localStock=enabled;return this;}

    public DiningOrderAuthorityServiceImpl(
            DiningPricingFactsService facts,
            DiningIdentityService identities,
            ConfigRevisionService revisions,
            MaterialCatalogQueryPort materials,
            ObjectMapper json) {
        this.facts = facts;
        this.identities = identities;
        this.revisions = revisions;
        this.materials = materials;
        this.json = json;
    }

    private DiningPriceFacts authorize(Command command) {
        if (command == null
                || command.identity() == null
                || command.source() == null
                || !"FOOD".equals(command.source().sourceSystem())) throw forbidden();
        var id = command.identity();
        var source = command.source();
        var result =
                command.credentials() == null
                        ? facts.staff(
                                id.entityNo(),
                                id.storeNo(),
                                source.sourceNo(),
                                source.sourceVersion())
                        : facts.customer(
                                source.sourceNo(),
                                new CartAccess(
                                        command.credentials().sessionNo(),
                                        command.credentials().accessToken(),
                                        command.credentials().buyerNo()),
                                source.sourceVersion());
        if (!id.entityNo().equals(result.payload().entityNo())
                || !id.storeNo().equals(result.payload().cart().storeNo())) throw forbidden();
        return result;
    }

    @Override
    public Party party(Command command) {
        var result = authorize(command);
        var id = command.identity();
        var cart = result.payload().cart();
        if (id.partyNo() == null
                || !id.partyNo().equals(cart.sessionNo())
                || id.buyerNo() != null && !id.buyerNo().isEmpty()
                || cart.buyerNo() != null) throw forbidden();
        var party = identities.party(id.entityNo(), id.storeNo(), id.partyNo());
        if (!id.entityNo().equals(party.entityNo())
                || !id.storeNo().equals(party.storeNo())
                || !Objects.equals(cart.checkNo(), party.checkNo())) throw forbidden();
        return new Party(
                new Scope(id.tenantNo(), id.entityNo(), id.storeNo()),
                id.partyNo(),
                party.deviceNo() == null ? "GUEST" : "DEVICE",
                party.displayName(),
                party.sessionNo(),
                party.deviceNo(),
                command.source().sourceNo(),
                command.source().sourceVersion());
    }

    @Override
    public Delivery delivery(Command command) {
        var result = authorize(command);
        var delivery = result.payload().delivery();
        if (delivery == null || !"DELIVERY".equals(result.payload().cart().fulfillment()))
            throw forbidden();
        var id = command.identity();
        return new Delivery(
                new Scope(id.tenantNo(), id.entityNo(), id.storeNo()),
                command.source().sourceNo(),
                command.source().sourceVersion(),
                delivery.revision(),
                delivery.receiverName(),
                delivery.receiverPhone(),
                delivery.address(),
                delivery.deliveryInstruction(),
                delivery.latE6(),
                delivery.lngE6());
    }

    @Override
    public Plan demandPlan(Command command, String orderNo, String subOrderNo) {
        var result = authorize(command);
        if (command.mode() != Mode.POSTPAY&&!localStock) return null;
        var payload = result.payload();
        var id = command.identity();
        var branches = new ArrayList<Branch>();
        for (var unit : payload.units()) {
            boolean container =
                    "MAIN".equals(unit.kind())
                            && payload.units().stream()
                                    .anyMatch(
                                            other ->
                                                    unit.sourceLineNo().equals(other.sourceLineNo())
                                                            && other.kind().startsWith("BUNDLE_"));
            if (container) continue;
            var selected =
                    payload.cart().lines().stream()
                            .filter(line -> unit.sourceLineNo().equals(line.sourceLineRef()))
                            .findFirst()
                            .orElseThrow(DiningOrderAuthorityServiceImpl::forbidden);
            var menu =
                    payload.menus().stream()
                            .filter(m -> selected.menuNo().equals(m.menuNo()))
                            .findFirst()
                            .orElseThrow(DiningOrderAuthorityServiceImpl::forbidden);
            var menuItem =
                    payload.composition().items().stream()
                            .filter(
                                    item ->
                                            selected.menuNo().equals(item.menuNo())
                                                    && selected.menuItemNo()
                                                            .equals(item.menuItemNo()))
                            .findFirst()
                            .orElseThrow(DiningOrderAuthorityServiceImpl::forbidden);
            String selectedRecipe =
                    "MAIN".equals(unit.kind())
                            ? menuItem.skus().stream()
                                    .filter(sku -> unit.skuNo().equals(sku.skuNo()))
                                    .map(sku -> sku.recipeNo())
                                    .filter(Objects::nonNull)
                                    .findFirst()
                                    .orElse(null)
                            : null;
            var candidates = new ArrayList<DiningPriceFacts.DependencyRef>();
            for (var dependency : menu.dependencies()) {
                if (!"RECIPE".equals(dependency.kind())
                        || "MAIN".equals(unit.kind())
                                && !Objects.equals(selectedRecipe, dependency.configNo())) continue;
                var revision =
                        revisions.getRevision(id.entityNo(), id.storeNo(), dependency.revisionNo());
                if (revision == null
                        || revision.publication() == null
                        || !dependency
                                .contentHash()
                                .equals(
                                        PublicationDocuments.hash(
                                                json, revision.definitionSnapshot())))
                    throw forbidden();
                var recipe =
                        RecipePublicationDocuments.validate(
                                json, dependency.configNo(), revision.definitionSnapshot());
                RecipePublicationDocuments.scope(recipe, id.entityNo(), id.storeNo());
                if (unit.skuNo().equals(recipe.recipe().skuNo())) candidates.add(dependency);
            }
            if (candidates.size() > 1) throw forbidden();
            if("ADDON".equals(unit.kind())) {
                var selectedModifiers=selected.selections().stream().filter(option->"MODIFIER".equals(option.sourceKind()))
                        .filter(option->{var key=List.of("OPTION",option.groupNo(),option.sourceNo());return unit.componentRef().equals(PricingSnapshotHash.of(key))
                                ||unit.componentRef().equals(PricingSnapshotHash.of(List.of(selected.sourceLineRef(),key)));}).toList();
                if(selectedModifiers.size()!=1)throw forbidden();var option=selectedModifiers.getFirst();
                var refs=menu.dependencies().stream().filter(d->"MODIFIER".equals(d.kind())&&option.sourceNo().equals(d.configNo())).toList();
                if(refs.size()!=1)throw forbidden();var definitions=frozenModifierDefinitions(id.entityNo(),id.storeNo(),refs.getFirst());
                var root=definitions.stream().filter(d->refs.getFirst().revisionNo().equals(d.revisionNo())).findFirst().orElseThrow(DiningOrderAuthorityServiceImpl::forbidden);
                var composite=DiningChannelModifierDemand.compilePriced(id.entityNo(),id.storeNo(),unit.componentRef(),unit.quantity(),root,definitions,materials,json);
                if(composite!=null) {
                    var identities=materials.getSkuMaterials(id.entityNo(),id.storeNo(),List.of(unit.skuNo()));
                    if(identities==null||identities.size()!=1||!unit.skuNo().equals(identities.getFirst().skuNo())
                            ||!ai.neargo.shop.food.policy.FoodMaterialFacts.validFact(identities.getFirst().material())
                            ||!id.entityNo().equals(identities.getFirst().material().entityNo()))throw forbidden();
                    String unitName=identities.getFirst().material().baseUom();
                    branches.add(new Branch(unit.componentRef(),subOrderNo,id.storeNo(),"RECIPE_MATERIAL",unit.quantity(),unitName,composite));
                    continue;
                }
            }
            List<Leaf> leaves;
            String kind;
            String revisionNo = null;
            String quantityUnit;
            if (!candidates.isEmpty()) {
                var dep = candidates.getFirst();
                var revision = revisions.getRevision(id.entityNo(), id.storeNo(), dep.revisionNo());
                var recipe =
                        RecipePublicationDocuments.validate(
                                json, dep.configNo(), revision.definitionSnapshot());
                var selection =
                        new OrderLineInput(
                                selected.sourceLineRef(),
                                selected.menuNo(),
                                selected.menuItemNo(),
                                selected.menuRevisionNo(),
                                unit.goodsNo(),
                                unit.skuNo(),
                                unit.quantity(),
                                selected.seatNo(),
                                selected.courseNo(),
                                "MAIN".equals(unit.kind())
                                        ? selected.selections().stream()
                                                .filter(s -> "SPEC_VALUE".equals(s.sourceKind()))
                                                .toList()
                                        : List.of(),
                                List.of());
                var demand =
                        ProductionDemandPolicy.evaluateComponent(
                                recipe, selection, unit.quantity());
                leaves =
                        demand.stream()
                                .map(
                                        l ->
                                                new Leaf(
                                                        PricingSnapshotHash.of(
                                                                List.of(
                                                                        unit.componentRef(),
                                                                        l.itemRef())),
                                                        l.itemRef(),
                                                        l.quantity(),
                                                        l.unit(),
                                                        l.definitionHash()))
                                .toList();
                kind = "RECIPE_MATERIAL";
                revisionNo = dep.revisionNo();
                quantityUnit = recipe.recipe().yieldUnit();
            } else {
                var matching =
                        materials.getSkuMaterials(
                                id.entityNo(), id.storeNo(), List.of(unit.skuNo()));
                if (matching == null
                        || matching.size() != 1
                        || !unit.skuNo().equals(matching.getFirst().skuNo())) throw forbidden();
                MaterialFact material = matching.getFirst().material();
                if (!ai.neargo.shop.food.policy.FoodMaterialFacts.validFact(material)
                        || !id.entityNo().equals(material.entityNo())) throw forbidden();
                quantityUnit = material.baseUom();
                leaves =
                        List.of(
                                new Leaf(
                                        PricingSnapshotHash.of(List.of(unit.componentRef(), "SKU")),
                                        "FOOD_LOCAL".equals(material.dataSource())?material.itemId():unit.skuNo(),
                                        unit.quantity(),
                                        quantityUnit,
                                        material.definitionHash(),null,
                                        "FOOD_LOCAL".equals(material.dataSource())?unit.skuNo():null));
                kind = "FINISHED_SKU";
            }
            if ("MAIN".equals(unit.kind())) {
                if (!DiningMeasurementPolicy.sameBaseUnit(
                        menuItem.dish().measurement(), quantityUnit)) throw forbidden();
            }
            branches.add(
                    new Branch(
                            unit.componentRef(),
                            subOrderNo,
                            id.storeNo(),
                            kind,
                            unit.quantity(),
                            quantityUnit,
                            leaves,
                            revisionNo));
        }
        var scope = new InventoryReservationPort.Scope(id.tenantNo(), id.entityNo(), orderNo);
        var plan = new Plan(scope, orderNo, null, branches);
        return new Plan(scope, orderNo, InventoryDemandPlanFacts.hash(plan), branches);
    }

    private List<ai.neargo.shop.food.dto.DiningChannelSourceFacts.Definition> frozenModifierDefinitions(String entity,String store,DiningPriceFacts.DependencyRef root) {
        var queue=new java.util.ArrayDeque<DiningPriceFacts.DependencyRef>();queue.add(root);
        var found=new java.util.LinkedHashMap<String,ai.neargo.shop.food.dto.DiningChannelSourceFacts.Definition>();
        while(!queue.isEmpty()) {
            var ref=queue.removeFirst();if(found.containsKey(ref.revisionNo()))continue;if(found.size()>=2000)throw forbidden();
            var revision=revisions.getRevision(entity,store,ref.revisionNo());
            if(!ref.kind().equals(revision.publication().configKind())||!ref.configNo().equals(revision.publication().configNo())
                    ||!ref.contentHash().equals(revision.publication().contentHash())
                    ||!ref.contentHash().equals(PublicationDocuments.hash(json,revision.definitionSnapshot())))throw forbidden();
            found.put(ref.revisionNo(),new ai.neargo.shop.food.dto.DiningChannelSourceFacts.Definition(ref.kind(),ref.configNo(),ref.revisionNo(),ref.revision(),ref.contentHash(),revision.definitionSnapshot()));
            var body=json.readValue(revision.definitionSnapshot(),ai.neargo.shop.food.dto.PublicationSnapshot.class);
            for(var edge:body.dependencies())queue.add(new DiningPriceFacts.DependencyRef(edge.configKind(),edge.configNo(),edge.revisionNo(),edge.revision(),edge.contentHash()));
        }
        return List.copyOf(found.values());
    }
    private static BizException forbidden() {
        return BizException.of(ErrorCode.FORBIDDEN);
    }
}
