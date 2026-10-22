package ai.neargo.shop.food.api.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizContextFilter;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.dto.DishCatalogItemVO;
import ai.neargo.shop.food.dto.DishDetailResultVO;
import ai.neargo.shop.food.dto.ListDishesQuery;

import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.api.FoodRequestScopeValidator;
import ai.neargo.shop.food.config.ConditionalOnFoodWebEnabled;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration.PolicyEnabled;
import ai.neargo.shop.food.dto.DishResultVO;
import ai.neargo.shop.food.dto.SaveDishReq;
import ai.neargo.shop.food.service.DishService;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Product management permission governs restaurant attributes; the caller cannot provide owner or actor. */
@RestController
@Profile("api")
@ConditionalOnFoodWebEnabled
@Conditional(PolicyEnabled.class)
@Validated
public class BizDishController {
    private final DishService service;
    private final StoreDirectoryPort stores;
    private final FoodRequestScopeValidator scope;

    public BizDishController(DishService service, StoreDirectoryPort stores, FoodRequestScopeValidator scope) {
        this.service = service;
        this.stores = stores;
        this.scope = scope;
    }

    /** Saves only draft restaurant attributes; it does not change the base product or publish a menu. */
    @PutMapping("/biz/x/food/stores/{storeNo}/dishes/{goodsNo}")
    @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")
    public DishResultVO saveDish(@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo,
            @PathVariable("goodsNo") @NotBlank @Size(max=32) String goodsNo,
            @Valid @RequestBody SaveDishReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key, HttpServletRequest request) {
        var context = requireStore(storeNo, request);
        return service.saveDish(context.merchantNo(), storeNo, goodsNo, SecurityUtils.currentUserNo(), req, key);
    }

    /** Applies all filters and product visibility before total/page selection. */
    @GetMapping("/biz/x/food/stores/{storeNo}/dishes")
    @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")
    public PageData<DishCatalogItemVO> listDishes(@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo,
            @Valid @ModelAttribute ListDishesQuery query, HttpServletRequest request) {
        queryFields(request, Set.of("page", "size", "keyword", "dishKind"));
        var context = requireStore(storeNo, request);
        return service.listDishes(context.merchantNo(), storeNo, query);
    }

    /** Returns real draft bindings plus original product display/variant facts. */
    @GetMapping("/biz/x/food/stores/{storeNo}/dishes/{goodsNo}")
    @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")
    public DishDetailResultVO getDish(@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo,
            @PathVariable("goodsNo") @NotBlank @Size(max=32) String goodsNo, HttpServletRequest request) {
        queryFields(request, Set.of());
        var context = requireStore(storeNo, request);
        return service.getDish(context.merchantNo(), storeNo, goodsNo);
    }

    private static void queryFields(HttpServletRequest request, Set<String> allowed) {
        if (!allowed.containsAll(request.getParameterMap().keySet())
                || request.getParameterMap().values().stream().anyMatch(values -> values.length != 1)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }

    private BizContext requireStore(String storeNo, HttpServletRequest request) {
        BizContext context = BizContext.current();
        // Check path/header before directory lookup as well as against the directory's actual owner.
        scope.requireStoreTarget(context.merchantNo(), context.currentStoreNo(), context.merchantNo(), storeNo,
                request.getHeader(BizContextFilter.STORE_HEADER), context.storeNos());
        var card = stores.cards(List.of(storeNo)).get(storeNo);
        if (card == null) throw BizException.of(ErrorCode.NOT_FOUND);
        scope.requireStoreTarget(context.merchantNo(), context.currentStoreNo(), card.entityNo(), card.storeNo(),
                request.getHeader(BizContextFilter.STORE_HEADER), context.storeNos());
        return context;
    }
}
