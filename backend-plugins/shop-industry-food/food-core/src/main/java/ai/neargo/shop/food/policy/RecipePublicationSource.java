package ai.neargo.shop.food.policy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.PublicationSnapshot.Dependency;
import ai.neargo.shop.food.dto.PublicationSnapshot;
import ai.neargo.shop.food.dto.RecipeAdjustmentVO;
import ai.neargo.shop.food.dto.RecipeLineVO;
import ai.neargo.shop.food.dto.RecipeOptionGroupSnapshot;
import ai.neargo.shop.food.dto.RecipePublicationSnapshot;
import ai.neargo.shop.food.dto.PublicationMarketContext;
import ai.neargo.shop.food.dto.RecipeVO;
import ai.neargo.shop.food.entity.FnbConfigRevision;
import ai.neargo.shop.food.entity.FnbDishOptionGroup;
import ai.neargo.shop.food.entity.FnbOptionGroup;
import ai.neargo.shop.food.entity.FnbOptionGroupItem;
import ai.neargo.shop.food.entity.FnbRecipe;
import ai.neargo.shop.food.entity.FnbRecipeAdjustment;
import ai.neargo.shop.food.entity.FnbRecipeLine;
import ai.neargo.shop.food.mapper.FoodMappers.DishOptionGroupMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OptionGroupItemMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OptionGroupMapper;
import ai.neargo.shop.food.mapper.FoodMappers.RecipeAdjustmentMapper;
import ai.neargo.shop.food.mapper.FoodMappers.RecipeLineMapper;
import ai.neargo.shop.food.mapper.FoodMappers.RecipeMapper;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.inventory.MaterialCatalogQueryPort.MaterialFact;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.SkuIdentityFact;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import tools.jackson.databind.ObjectMapper;

/** Captures recipe-owned rows plus actual product/group bindings; no remote lookup runs under these locks. */
public final class RecipePublicationSource {
    private final RecipeMapper heads;
    private final RecipeLineMapper lines;
    private final RecipeAdjustmentMapper adjustments;
    private final OptionGroupItemMapper items;
    private final OptionGroupMapper groups;
    private final DishOptionGroupMapper bindings;
    private final BooleanSupplier enabled;
    private final ObjectMapper json;

    public RecipePublicationSource(RecipeMapper heads, RecipeLineMapper lines, RecipeAdjustmentMapper adjustments,
            OptionGroupItemMapper items, OptionGroupMapper groups, DishOptionGroupMapper bindings,
            BooleanSupplier enabled, ObjectMapper json) {
        this.heads = heads; this.lines = lines; this.adjustments = adjustments; this.items = items;
        this.groups = groups; this.bindings = bindings; this.enabled = enabled; this.json = json;
    }
    public record Group(long workingVersion, String workingStatus, Dependency publication,
            PublicationSnapshot definition, List<String> goodsNos) { }
    public record Captured(RecipeVO recipe, List<Group> groups) { }

    public Captured capture(String entity, String store, String number, boolean lock,
            Function<String, FnbConfigRevision> selected) {
        if (!enabled.getAsBoolean()) throw BizException.of(ErrorCode.NOT_FOUND);
        var query = Wrappers.<FnbRecipe>lambdaQuery().eq(FnbRecipe::getEntityNo, entity)
                .eq(FnbRecipe::getStoreNo, store).eq(FnbRecipe::getRecipeNo, number);
        if (lock) query.last("FOR UPDATE");
        var head = heads.selectOne(query);
        if (head == null) throw BizException.of(ErrorCode.NOT_FOUND);
        if (head.getVersion() == null || head.getVersion() < 0 || head.getConfigVersion() == null
                || head.getConfigVersion() < 0 || !List.of("DRAFT", "ACTIVE").contains(head.getStatus())) throw bad();
        var lineQuery = Wrappers.<FnbRecipeLine>lambdaQuery().eq(FnbRecipeLine::getEntityNo, entity)
                .eq(FnbRecipeLine::getRecipeNo, number).orderByAsc(FnbRecipeLine::getSortNo, FnbRecipeLine::getLineNo);
        var adjustmentQuery = Wrappers.<FnbRecipeAdjustment>lambdaQuery()
                .eq(FnbRecipeAdjustment::getEntityNo, entity).eq(FnbRecipeAdjustment::getRecipeNo, number)
                .orderByAsc(FnbRecipeAdjustment::getSortNo, FnbRecipeAdjustment::getAdjustmentNo);
        if (lock) { lineQuery.last("FOR UPDATE"); adjustmentQuery.last("FOR UPDATE"); }
        var children = lines.selectList(lineQuery);
        var options = adjustments.selectList(adjustmentQuery);
        if (children.isEmpty() || children.size() > 100 || options.size() > 100) throw bad();
        var recipe = new RecipeVO(number, store, head.getSkuNo(), head.getRecipeCode(),
                head.getYieldQty(), head.getYieldUnit(), head.getStatus(), head.getVersion(), head.getConfigVersion(),
                children.stream().map(a -> new RecipeLineVO(a.getLineNo(), a.getItemRef(), a.getQty(), a.getUnit(),
                        a.getLossRate(), a.getMaterialDefinitionHash())).toList(),
                options.stream().map(a -> new RecipeAdjustmentVO(a.getAdjustmentNo(), a.getOptionItemNo(),
                        a.getTargetLineNo(), a.getAction(), a.getQty(), a.getUnit(),
                        a.getOptionIdentityHash())).toList());
        var dependencies = new TreeMap<String, Group>();
        for (var adjustment : recipe.adjustments()) {
            var itemQuery = Wrappers.<FnbOptionGroupItem>lambdaQuery().eq(FnbOptionGroupItem::getEntityNo, entity)
                    .eq(FnbOptionGroupItem::getOptionItemNo, adjustment.optionItemNo());
            if (lock) itemQuery.last("FOR UPDATE");
            var item = items.selectOne(itemQuery);
            if (item == null) throw bad();
            String identity = RecipeAdjustmentPolicy.optionIdentity(entity, item.getGroupNo(), item.getOptionItemNo(),
                    item.getSourceKind(), item.getSpecValueNo(), item.getModifierNo());
            if (!identity.equals(adjustment.optionIdentityHash())) throw BizException.of(ErrorCode.CONFLICT);
            var dependency = dependencies.get(item.getGroupNo());
            if (dependency == null) {
                var groupQuery = Wrappers.<FnbOptionGroup>lambdaQuery().eq(FnbOptionGroup::getEntityNo, entity)
                        .eq(FnbOptionGroup::getGroupNo, item.getGroupNo());
                var bindingQuery = Wrappers.<FnbDishOptionGroup>lambdaQuery()
                        .eq(FnbDishOptionGroup::getEntityNo, entity)
                        .eq(FnbDishOptionGroup::getGroupNo, item.getGroupNo())
                        .orderByAsc(FnbDishOptionGroup::getGoodsNo);
                if (lock) { groupQuery.last("FOR UPDATE"); bindingQuery.last("FOR UPDATE"); }
                var group = groups.selectOne(groupQuery);
                if (group == null || !List.of("DRAFT", "ACTIVE").contains(group.getStatus())) throw bad();
                var frozen = selected.apply(item.getGroupNo());
                if (frozen == null) throw bad();
                if ((frozen.getContentSchema() != 1 && frozen.getContentSchema() != 6)
                        || !"OPTION_GROUP".equals(frozen.getConfigKind())
                        || !entity.equals(frozen.getEntityNo()) || !item.getGroupNo().equals(frozen.getConfigNo())
                        || !PublicationDocuments.hash(json, frozen.getDefinitionSnapshot())
                                .equals(frozen.getContentHash())
                        || PublicationDocuments.validate(json, "OPTION_GROUP", item.getGroupNo(),
                                frozen.getDefinitionSnapshot()) != frozen.getContentSchema()) throw bad();
                var definition = json.readValue(frozen.getDefinitionSnapshot(), PublicationSnapshot.class);
                dependency = new Group(group.getVersion(), group.getStatus(),
                        new Dependency(frozen.getRevisionNo(), "OPTION_GROUP", item.getGroupNo(),
                                frozen.getRevision(), frozen.getContentHash()), definition,
                        bindings.selectList(bindingQuery).stream().map(FnbDishOptionGroup::getGoodsNo).toList());
                dependencies.put(item.getGroupNo(), dependency);
            }
            var publishedItem = dependency.definition().optionGroup().data().items().stream()
                    .filter(a -> adjustment.optionItemNo().equals(a.optionItemNo())).findFirst().orElseThrow(
                            RecipePublicationSource::bad);
            if (!identity.equals(RecipeAdjustmentPolicy.optionIdentity(entity, item.getGroupNo(),
                    publishedItem.optionItemNo(), publishedItem.sourceKind(), publishedItem.specValueNo(),
                    publishedItem.modifierNo()))) throw BizException.of(ErrorCode.CONFLICT);
        }
        return new Captured(recipe, List.copyOf(dependencies.values()));
    }

    public void validateAuthorities(String entity, Captured captured, List<SkuIdentityFact> skus,
            List<MaterialFact> materials) {
        if (skus.size() != 1 || materials == null || materials.size() > 100) {
            throw new IllegalStateException("Invalid recipe authority facts");
        }
        var sku = skus.getFirst();
        if (captured.groups().stream().anyMatch(g -> !g.goodsNos().contains(sku.goodsNo()))) throw bad();
        Map<String, MaterialFact> facts = new HashMap<>();
        var requested = captured.recipe().lines().stream().map(RecipeLineVO::itemRef).distinct().toList();
        for (var fact : materials) {
            if (!MaterialCatalogFacts.validFact(fact) || !entity.equals(fact.entityNo())
                    || !requested.contains(fact.itemId()) || facts.putIfAbsent(fact.itemId(), fact) != null) {
                throw new IllegalStateException("Invalid recipe material facts");
            }
        }
        for (var line : captured.recipe().lines()) {
            var fact = facts.get(line.itemRef());
            if (fact == null || !line.unit().equals(fact.baseUom())
                    || !line.materialDefinitionHash().equals(fact.definitionHash())) {
                throw BizException.of(ErrorCode.CONFLICT);
            }
        }
    }

    public RecipePublicationSnapshot document(Captured captured, List<SkuIdentityFact> skus,
            List<MaterialFact> materials, String approval) {
        return document(captured, skus, materials, approval, null);
    }

    /** The owning publisher supplies the already-qualified market; no external lookup runs during capture. */
    public RecipePublicationSnapshot document(Captured captured, List<SkuIdentityFact> skus,
            List<MaterialFact> materials, String approval, PublicationMarketContext context) {
        return new RecipePublicationSnapshot(context == null ? 3 : 6, "RECIPE", captured.recipe().recipeNo(),
                captured.recipe().version(),
                captured.recipe(), skus, materials.stream().sorted(Comparator.comparing(MaterialFact::itemId))
                        .toList(), captured.groups().stream().map(g -> new RecipeOptionGroupSnapshot(
                                skus.getFirst().goodsNo(), g.publication(), g.definition())).toList(),
                approval, context);
    }

    public long currentVersion(String entity, String store, String number, boolean lock) {
        if (!enabled.getAsBoolean()) throw BizException.of(ErrorCode.NOT_FOUND);
        var query = Wrappers.<FnbRecipe>lambdaQuery().eq(FnbRecipe::getEntityNo, entity)
                .eq(FnbRecipe::getRecipeNo, number).eq(FnbRecipe::getStoreNo, store);
        if (lock) query.last("FOR UPDATE");
        var row = heads.selectOne(query);
        if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
        if (row.getVersion() == null || !("DRAFT".equals(row.getStatus()) || "ACTIVE".equals(row.getStatus()))) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        return row.getVersion();
    }
    public void advance(String entity, String store, String number, long version, FnbConfigRevision revision) {
        var row = heads.selectOne(Wrappers.<FnbRecipe>lambdaQuery().eq(FnbRecipe::getEntityNo, entity)
                .eq(FnbRecipe::getStoreNo, store).eq(FnbRecipe::getRecipeNo, number));
        if (row == null) throw BizException.of(ErrorCode.CONFLICT);
        row.setVersion(version);
        row.setStatus("EFFECTIVE".equals(revision.getStatus()) ? "ACTIVE" : "DRAFT");
        if ("EFFECTIVE".equals(revision.getStatus())) row.setPublishedRevisionNo(revision.getRevisionNo());
        if (heads.updateById(row) != 1) throw BizException.of(ErrorCode.CONFLICT);
    }
    private static BizException bad() { return BizException.of(ErrorCode.BAD_REQUEST); }
}
