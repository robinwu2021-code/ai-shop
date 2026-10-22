package ai.neargo.shop.food.api.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizContextFilter;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.api.FoodRequestScopeValidator;
import ai.neargo.shop.food.config.ConditionalOnFoodWebEnabled;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration.PolicyEnabled;
import ai.neargo.shop.food.dto.CreateRecipeReq;
import ai.neargo.shop.food.dto.ListRecipesQuery;
import ai.neargo.shop.food.dto.RecipeResultVO;
import ai.neargo.shop.food.dto.RecipeVO;
import ai.neargo.shop.food.dto.ReplaceRecipeDefinitionReq;
import ai.neargo.shop.food.service.RecipeService;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Original goods permission and store directory protect store-specific recipe drafts. */
@RestController
@Profile("api")
@ConditionalOnFoodWebEnabled
@Conditional(PolicyEnabled.class)
@Validated
public class BizRecipeController {
    private final RecipeService service;
    private final StoreDirectoryPort stores;
    private final FoodRequestScopeValidator scope;

    public BizRecipeController(RecipeService service, StoreDirectoryPort stores, FoodRequestScopeValidator scope) {
        this.service = service; this.stores = stores; this.scope = scope;
    }

    /** Registers the original SKU without accepting client price, ownership, or publication facts. */
    @PostMapping("/biz/x/food/stores/{storeNo}/recipes")
    @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")
    public RecipeResultVO createRecipe(@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo,
            @Valid @RequestBody CreateRecipeReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key, HttpServletRequest request) {
        queryFields(request, Set.of());
        var context = requireStore(storeNo, request);
        return service.createRecipe(context.merchantNo(), storeNo, SecurityUtils.currentUserNo(), req, key);
    }

    /** Replaces a working composition; the platform resolves entity, current store, and actor. */
    @PutMapping("/biz/x/food/recipes/{recipeNo}/definition")
    @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")
    public RecipeResultVO replaceDefinition(@PathVariable("recipeNo") @NotBlank @Size(max=32) String recipeNo,
            @Valid @RequestBody ReplaceRecipeDefinitionReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key, HttpServletRequest request) {
        queryFields(request, Set.of());
        var context = BizContext.current();
        if (context.currentStoreNo() == null) throw BizException.of(ErrorCode.BAD_REQUEST);
        requireStore(context.currentStoreNo(), request);
        return service.replaceDefinition(context.merchantNo(), context.currentStoreNo(), recipeNo,
                SecurityUtils.currentUserNo(), req, key);
    }

    /** Reads only the current authorized store, without requiring authority services online. */
    @GetMapping("/biz/x/food/recipes/{recipeNo}")
    @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")
    public RecipeResultVO getRecipe(@PathVariable("recipeNo") @NotBlank @Size(max=32) String number,
            HttpServletRequest request) {
        queryFields(request, Set.of());
        var context = BizContext.current();
        if (context.currentStoreNo() == null) throw BizException.of(ErrorCode.BAD_REQUEST);
        requireStore(context.currentStoreNo(), request);
        return service.getRecipe(context.merchantNo(), context.currentStoreNo(), number);
    }

    /** Filters and total operate on the current store draft library. */
    @GetMapping("/biz/x/food/stores/{storeNo}/recipes")
    @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")
    public PageData<RecipeVO> listRecipes(@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo,
            @Valid @ModelAttribute ListRecipesQuery query, HttpServletRequest request) {
        queryFields(request, Set.of("page", "size", "skuNo", "status"));
        var context = requireStore(storeNo, request);
        return service.listRecipes(context.merchantNo(), storeNo, query);
    }

    private static void queryFields(HttpServletRequest request, Set<String> allowed) {
        if (!allowed.containsAll(request.getParameterMap().keySet())
                || request.getParameterMap().values().stream().anyMatch(values -> values.length != 1)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }

    private BizContext requireStore(String storeNo, HttpServletRequest request) {
        var context = BizContext.current();
        scope.requireStoreTarget(context.merchantNo(), context.currentStoreNo(), context.merchantNo(), storeNo,
                request.getHeader(BizContextFilter.STORE_HEADER), context.storeNos());
        var card = stores.cards(List.of(storeNo)).get(storeNo);
        if (card == null) throw BizException.of(ErrorCode.NOT_FOUND);
        scope.requireStoreTarget(context.merchantNo(), context.currentStoreNo(), card.entityNo(), card.storeNo(),
                request.getHeader(BizContextFilter.STORE_HEADER), context.storeNos());
        return context;
    }
}
