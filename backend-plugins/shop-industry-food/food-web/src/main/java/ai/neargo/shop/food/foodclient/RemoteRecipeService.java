package ai.neargo.shop.food.foodclient;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.dto.CreateRecipeReq;
import ai.neargo.shop.food.dto.ListRecipesQuery;
import ai.neargo.shop.food.dto.RecipeResultVO;
import ai.neargo.shop.food.dto.RecipeVO;
import ai.neargo.shop.food.dto.ReplaceRecipeDefinitionReq;
import ai.neargo.shop.food.service.RecipeService;
import ai.neargo.shop.food.spi.RecipeInternalApi;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.function.Supplier;

/** One FOOD attempt; scope, identity and exact numeric metadata are checked before accepting a receipt. */
public class RemoteRecipeService implements RecipeService {
    private final RecipeInternalApi api;
    public RemoteRecipeService(RecipeInternalApi api) { this.api = api; }

    @Override
    public RecipeResultVO createRecipe(String entity, String store, String actor, CreateRecipeReq req, String key) {
        if (req == null) throw BizException.of(ErrorCode.BAD_REQUEST);
        var result = call(() -> api.createRecipe(new RecipeInternalApi.CreateCommand(entity, store, actor, key, req)));
        var v = result == null ? null : result.recipe();
        if (!valid(v, store) || !req.skuNo().equals(v.skuNo()) || !req.recipeCode().equals(v.recipeCode())
                || !same(req.yieldQty(), v.yieldQty()) || !req.yieldUnit().equals(v.yieldUnit())
                || v.version() != 0 || v.configVersion() != 0 || !"DRAFT".equals(v.status()) || !v.lines().isEmpty()) {
            throw new IllegalStateException("Invalid recipe creation receipt");
        }
        return copy(v);
    }

    @Override
    public RecipeResultVO replaceDefinition(String entity, String store, String number, String actor,
            ReplaceRecipeDefinitionReq req, String key) {
        if (req == null || req.version() == null || req.version() < 0 || req.version() == Long.MAX_VALUE
                || req.lines() == null) throw BizException.of(ErrorCode.BAD_REQUEST);
        var result = call(() -> api.replaceDefinition(
                new RecipeInternalApi.UpdateCommand(entity, store, number, actor, key, req)));
        var v = result == null ? null : result.recipe();
        if (!valid(v, store) || !number.equals(v.recipeNo()) || v.version() != req.version() + 1
                || !same(req.yieldQty(), v.yieldQty()) || !req.yieldUnit().equals(v.yieldUnit())
                || !"DRAFT".equals(v.status()) || v.lines().size() != req.lines().size()) {
            throw new IllegalStateException("Invalid recipe mutation receipt");
        }
        for (int i = 0; i < req.lines().size(); i++) {
            var input = req.lines().get(i); var output = v.lines().get(i);
            if (input == null || input.lineNo() != null && !input.lineNo().equals(output.lineNo())
                    || !output.itemRef().equals(input.itemRef()) || !output.unit().equals(input.unit())
                    || !same(output.qty(), input.qty()) || !same(output.lossRate(), input.lossRate())) {
                throw new IllegalStateException("Invalid recipe line receipt");
            }
        }
        return copy(v);
    }

    @Override
    public RecipeResultVO getRecipe(String entity, String store, String number) {
        var result = call(() -> api.getRecipe(new RecipeInternalApi.GetQuery(entity, store, number)));
        if (result == null || !valid(result.recipe(), store) || !number.equals(result.recipe().recipeNo())) {
            throw new IllegalStateException("Invalid recipe detail receipt");
        }
        return copy(result.recipe());
    }

    @Override
    public PageData<RecipeVO> listRecipes(String entity, String store, ListRecipesQuery query) {
        int page = query == null || query.page() == null ? 1 : query.page();
        int size = query == null || query.size() == null ? 20 : query.size();
        if (page < 1 || size < 1 || size > 100) throw BizException.of(ErrorCode.BAD_REQUEST);
        var result = call(() -> api.listRecipes(new RecipeInternalApi.Lookup(entity, store, query)));
        var seen = new HashSet<String>();
        if (result == null || result.records() == null || result.page() == null || result.size() == null
                || result.total() == null || result.page() != page || result.size() != size
                || result.total() < result.records().size() || result.records().size() > size
                || result.records().stream().anyMatch(v -> !valid(v, store) || !seen.add(v.recipeNo())
                        || query != null && query.skuNo() != null && !query.skuNo().equals(v.skuNo())
                        || query != null && query.status() != null && !query.status().equals(v.status()))) {
            throw new IllegalStateException("Invalid recipe page receipt");
        }
        return PageData.of(result.records().stream().map(v -> copy(v).recipe()).toList(), result.total(), page, size);
    }

    private static RecipeResultVO copy(RecipeVO v) {
        return new RecipeResultVO(new RecipeVO(v.recipeNo(), v.storeNo(), v.skuNo(), v.recipeCode(), v.yieldQty(),
                v.yieldUnit(), v.status(), v.version(), v.configVersion(), List.copyOf(v.lines())));
    }
    private static boolean valid(RecipeVO v, String store) {
        if (v == null || !MaterialCatalogFacts.validKey(v.recipeNo(), 32) || !store.equals(v.storeNo())
                || !MaterialCatalogFacts.validKey(v.skuNo(), 32) || v.recipeCode() == null
                || !v.recipeCode().matches("[A-Z][A-Z0-9_]{0,31}") || !"EA".equals(v.yieldUnit())
                || !quantity(v.yieldQty(), true) || v.version() < 0 || v.configVersion() < 0
                || !List.of("DRAFT", "ACTIVE", "DISABLED").contains(v.status() == null ? "" : v.status())
                || v.lines() == null || v.lines().size() > 100) return false;
        var seen = new HashSet<String>();
        for (var line : v.lines()) {
            if (line == null || !MaterialCatalogFacts.validKey(line.lineNo(), 32) || !seen.add(line.lineNo())
                    || !MaterialCatalogFacts.validKey(line.itemRef(), 32)
                    || !MaterialCatalogFacts.validKey(line.unit(), 16)
                    || !quantity(line.qty(), true) || !quantity(line.lossRate(), false)
                    || line.lossRate().compareTo(BigDecimal.ONE) >= 0 || line.materialDefinitionHash() == null
                    || !line.materialDefinitionHash().matches("[a-f0-9]{64}")) return false;
        }
        return true;
    }
    private static boolean quantity(BigDecimal n, boolean positive) {
        return n != null && n.signum() >= (positive ? 1 : 0) && n.stripTrailingZeros().scale() <= 6
                && (long) n.precision() - n.scale() <= 12;
    }
    private static boolean same(BigDecimal a, BigDecimal b) { return a != null && b != null && a.compareTo(b) == 0; }
    private <T> T call(Supplier<T> call) {
        try { return ServiceCalls.call(ServiceName.FOOD, call); }
        catch (ServiceCallException failure) {
            var code = switch (failure.statusCode()) {
                case 400 -> ErrorCode.BAD_REQUEST;
                case 403 -> ErrorCode.FORBIDDEN;
                case 404 -> ErrorCode.NOT_FOUND;
                case 409 -> ErrorCode.CONFLICT;
                default -> null;
            };
            if (code != null) throw BizException.of(code);
            throw failure;
        }
    }
}
