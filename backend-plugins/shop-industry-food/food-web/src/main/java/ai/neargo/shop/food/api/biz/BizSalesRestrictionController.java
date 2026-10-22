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
import ai.neargo.shop.food.service.SalesRestrictionService;
import ai.neargo.shop.food.policy.SalesRestrictionPolicy;
import ai.neargo.shop.food.dto.SalesRestrictionReq;
import ai.neargo.shop.food.dto.SalesRestrictionQuery;
import ai.neargo.shop.food.dto.SalesRestrictionVO;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Original goods permission and store directory protect restaurant restriction scope. */
@RestController
@Profile("api")
@ConditionalOnFoodWebEnabled
@Conditional(PolicyEnabled.class)
@Validated
public class BizSalesRestrictionController {
    private final SalesRestrictionService service;
    private final StoreDirectoryPort stores;
    private final FoodRequestScopeValidator scope;

    public BizSalesRestrictionController(SalesRestrictionService service, StoreDirectoryPort stores,
            FoodRequestScopeValidator scope) {
        this.service = service; this.stores = stores; this.scope = scope;
    }

    /** Changes only the authorized store restriction and requires an actor-bound replay key. */
    @PostMapping("/biz/x/food/stores/{storeNo}/sales-restrictions")
    @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")
    public SalesRestrictionVO manage(@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo,
            @Valid @RequestBody SalesRestrictionReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key, HttpServletRequest request) {
        queryFields(request, Set.of()); var context = requireStore(storeNo, request);
        SalesRestrictionPolicy.command(req);
        return service.manage(context.merchantNo(), storeNo, SecurityUtils.currentUserNo(), req, key);
    }
    /** Paginates effective restriction status without publishing physical inventory. */
    @GetMapping("/biz/x/food/stores/{storeNo}/sales-restrictions")
    @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")
    public PageData<SalesRestrictionVO> list(@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo,
            @Valid @ModelAttribute SalesRestrictionQuery query, HttpServletRequest request) {
        queryFields(request, Set.of("page", "size", "skuNo", "status"));
        var context = requireStore(storeNo, request);
        return service.list(context.merchantNo(), storeNo, query);
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
