package ai.neargo.shop.food.policy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.MenuCategoryInput;
import ai.neargo.shop.food.dto.MenuCompositionVO;
import ai.neargo.shop.food.dto.MenuItemInput;
import ai.neargo.shop.food.dto.MenuSkuRecipeInput;
import ai.neargo.shop.food.entity.FnbMenuCategory;
import ai.neargo.shop.food.entity.FnbMenuItem;
import ai.neargo.shop.food.entity.FnbMenuItemOptionGroup;
import ai.neargo.shop.food.entity.FnbMenuItemSku;
import ai.neargo.shop.food.mapper.FoodMappers.MenuCategoryMapper;
import ai.neargo.shop.food.mapper.FoodMappers.MenuItemMapper;
import ai.neargo.shop.food.mapper.FoodMappers.MenuItemOptionGroupMapper;
import ai.neargo.shop.food.mapper.FoodMappers.MenuItemSkuMapper;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.GoodsFact;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** Only the locked working definition is replaceable; external product facts are loaded before that transaction. */
public final class MenuCompositionSource {
    private final MenuCategoryMapper categories;
    private final MenuItemMapper items;
    private final MenuItemSkuMapper skus;
    private final MenuItemOptionGroupMapper groups;
    private final GoodsCatalogQueryPort goods;

    public MenuCompositionSource(MenuCategoryMapper categories, MenuItemMapper items, MenuItemSkuMapper skus,
            MenuItemOptionGroupMapper groups, GoodsCatalogQueryPort goods) {
        this.categories = categories; this.items = items; this.skus = skus; this.groups = groups; this.goods = goods;
    }
    public Map<String, GoodsFact> facts(String entity, String store, MenuCompositionVO definition) {
        var result = new HashMap<String, GoodsFact>();
        for (var number : definition.categories().stream().flatMap(c -> c.items().stream())
                .map(MenuItemInput::goodsNo).distinct().sorted().toList()) {
            var fact = goods.getGoods(entity, store, number);
            if (fact == null || !entity.equals(fact.entityNo()) || !store.equals(fact.storeNo())
                    || !number.equals(fact.goodsNo()) || fact.version() < 0 || fact.skus() == null
                    || fact.skus().isEmpty() || fact.skus().size() > 1000) throw invalidAuthority();
            var seen = new HashSet<String>();
            for (var sku : fact.skus()) {
                if (sku == null || !MaterialCatalogFacts.validKey(sku.skuNo(), 32) || sku.version() < 0
                        || !seen.add(sku.skuNo())) throw invalidAuthority();
            }
            result.put(number, fact);
        }
        return result;
    }
    public MenuCompositionVO read(String entity, String number) {
        var categoryRows = categories.selectList(Wrappers.<FnbMenuCategory>lambdaQuery()
                .eq(FnbMenuCategory::getEntityNo, entity).eq(FnbMenuCategory::getMenuNo, number)
                .orderByAsc(FnbMenuCategory::getSortNo, FnbMenuCategory::getCategoryNo));
        if (categoryRows.isEmpty()) return new MenuCompositionVO(List.of());
        var categoryNos = categoryRows.stream().map(FnbMenuCategory::getCategoryNo).toList();
        var itemRows = items.selectList(Wrappers.<FnbMenuItem>lambdaQuery().eq(FnbMenuItem::getEntityNo, entity)
                .in(FnbMenuItem::getCategoryNo, categoryNos)
                .orderByAsc(FnbMenuItem::getSortNo, FnbMenuItem::getMenuItemNo));
        var itemNos = itemRows.stream().map(FnbMenuItem::getMenuItemNo).toList();
        var skuRows = itemNos.isEmpty() ? List.<FnbMenuItemSku>of() : skus.selectList(
                Wrappers.<FnbMenuItemSku>lambdaQuery().eq(FnbMenuItemSku::getEntityNo, entity)
                        .in(FnbMenuItemSku::getMenuItemNo, itemNos).orderByAsc(FnbMenuItemSku::getSkuNo));
        var groupRows = itemNos.isEmpty() ? List.<FnbMenuItemOptionGroup>of() : groups.selectList(
                Wrappers.<FnbMenuItemOptionGroup>lambdaQuery().eq(FnbMenuItemOptionGroup::getEntityNo, entity)
                        .in(FnbMenuItemOptionGroup::getMenuItemNo, itemNos)
                        .orderByAsc(FnbMenuItemOptionGroup::getSortNo));
        var output = categoryRows.stream().map(category -> new MenuCategoryInput(category.getCategoryNo(),
                category.getName(), category.getSortNo(), itemRows.stream()
                        .filter(i -> category.getCategoryNo().equals(i.getCategoryNo())).map(item -> {
                            var selected = skuRows.stream().filter(s -> item.getMenuItemNo().equals(s.getMenuItemNo()))
                                    .toList();
                            return new MenuItemInput(item.getMenuItemNo(), item.getGoodsNo(), item.getSortNo(),
                                    item.getEnabled(), item.getSkuScope(), selected.stream()
                                            .map(FnbMenuItemSku::getSkuNo).toList(), item.getOptionMode(),
                                    groupRows.stream().filter(g -> item.getMenuItemNo().equals(g.getMenuItemNo()))
                                            .map(FnbMenuItemOptionGroup::getGroupNo).toList(), selected.stream()
                                                    .filter(s -> s.getRecipeNo() != null)
                                                    .map(s -> new MenuSkuRecipeInput(s.getSkuNo(), s.getRecipeNo()))
                                                    .toList());
                        }).toList())).toList();
        try { return MenuCompositionDefinitions.normalize(new MenuCompositionVO(output)); }
        catch (RuntimeException invalid) {
            throw new IllegalStateException("Invalid stored menu composition", invalid);
        }
    }
    public MenuCompositionVO replace(String entity, String store, String menu, MenuCompositionVO definition,
            MenuCompositionVO previous, Map<String, GoodsFact> facts) {
        var oldCategories = new HashSet<String>(); var oldItems = new HashMap<String, String>();
        for (var category : previous.categories()) {
            oldCategories.add(category.categoryNo());
            category.items().forEach(item -> oldItems.put(item.menuItemNo(), item.goodsNo()));
        }
        for (var category : definition.categories()) {
            if (category.categoryNo() != null && !oldCategories.contains(category.categoryNo())) throw conflict();
            for (var item : category.items()) {
                if (item.menuItemNo() != null && !item.goodsNo().equals(oldItems.get(item.menuItemNo()))) {
                    throw conflict();
                }
                var fact = facts.get(item.goodsNo());
                if (fact == null || item.skuNos().stream().anyMatch(sku -> fact.skus().stream()
                        .noneMatch(s -> sku.equals(s.skuNo())))) throw bad();
                for (String group : item.optionGroupNos()) if (groups.availableGroup(entity, group) != 1) throw bad();
                for (var recipe : item.skuRecipes()) {
                    if (skus.recipeForSku(entity, store, recipe.recipeNo(), recipe.skuNo()) != 1) throw bad();
                }
            }
        }
        groups.replaceDraftRows(entity, menu); skus.replaceDraftRows(entity, menu);
        items.replaceDraftRows(entity, menu); categories.replaceDraftRows(entity, menu);
        var result = new ArrayList<MenuCategoryInput>();
        for (var category : definition.categories()) {
            var row = new FnbMenuCategory(); row.setEntityNo(entity); row.setMenuNo(menu);
            row.setCategoryNo(category.categoryNo() == null ? BizKey.next("FMCA") : category.categoryNo());
            row.setName(category.name()); row.setSortNo(category.sortNo()); categories.insert(row);
            var children = new ArrayList<MenuItemInput>();
            for (var item : category.items()) {
                var dish = new FnbMenuItem(); dish.setEntityNo(entity); dish.setCategoryNo(row.getCategoryNo());
                dish.setMenuItemNo(item.menuItemNo() == null ? BizKey.next("FMIT") : item.menuItemNo());
                dish.setGoodsNo(item.goodsNo()); dish.setEnabled(item.enabled()); dish.setSkuScope(item.skuScope());
                dish.setOptionMode(item.optionMode()); dish.setSortNo(item.sortNo()); items.insert(dish);
                for (String sku : item.skuNos()) {
                    var selected = new FnbMenuItemSku();
                    selected.setEntityNo(entity);
                    selected.setMenuItemNo(dish.getMenuItemNo());
                    selected.setSkuNo(sku); selected.setSelectionMode("EXPLICIT");
                    selected.setRecipeNo(item.skuRecipes().stream().filter(r -> sku.equals(r.skuNo()))
                            .map(MenuSkuRecipeInput::recipeNo).findFirst().orElse(null)); skus.insert(selected);
                }
                int order = 0;
                for (String group : item.optionGroupNos()) {
                    var selected = new FnbMenuItemOptionGroup(); selected.setEntityNo(entity);
                    selected.setMenuItemNo(dish.getMenuItemNo());
                    selected.setGroupNo(group);
                    selected.setSortNo(order++);
                    groups.insert(selected);
                }
                children.add(new MenuItemInput(dish.getMenuItemNo(), item.goodsNo(), item.sortNo(), item.enabled(),
                        item.skuScope(), item.skuNos(), item.optionMode(), item.optionGroupNos(), item.skuRecipes()));
            }
            result.add(new MenuCategoryInput(row.getCategoryNo(), category.name(), category.sortNo(), children));
        }
        return new MenuCompositionVO(result);
    }
    private static IllegalStateException invalidAuthority() { return new IllegalStateException("Invalid goods facts"); }
    private static BizException bad() { return BizException.of(ErrorCode.BAD_REQUEST); }
    private static BizException conflict() { return BizException.of(ErrorCode.CONFLICT); }
}
