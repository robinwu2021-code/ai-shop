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
import ai.neargo.shop.food.dto.BundleConfigVO;
import ai.neargo.shop.food.dto.BundleMutationVO;
import ai.neargo.shop.food.dto.CreateBundleReq;
import ai.neargo.shop.food.dto.CreateBundleResultVO;
import ai.neargo.shop.food.dto.GetBundleVO;
import ai.neargo.shop.food.dto.ListBundlesQuery;
import ai.neargo.shop.food.dto.ReplaceBundleDefinitionReq;
import ai.neargo.shop.food.service.BundleService;
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

/** Original goods permissions protect entity-shared composition through an authorized store entry. */
@RestController
@Profile("api")
@ConditionalOnFoodWebEnabled
@Conditional(PolicyEnabled.class)
@Validated
public class BizBundleController {
    private final BundleService service;
    private final StoreDirectoryPort stores;
    private final FoodRequestScopeValidator scope;

    public BizBundleController(BundleService service, StoreDirectoryPort stores, FoodRequestScopeValidator scope) {
        this.service = service; this.stores = stores; this.scope = scope;
    }

    /** Registers the original SKU without accepting client price, ownership, or publication facts. */
    @PostMapping("/biz/x/food/stores/{storeNo}/bundles")
    @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")
    public CreateBundleResultVO createBundle(@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo,
            @Valid @RequestBody CreateBundleReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key, HttpServletRequest request) {
        queryFields(request, Set.of());
        var context = requireStore(storeNo, request);
        return service.createBundle(context.merchantNo(), storeNo, SecurityUtils.currentUserNo(), req, key);
    }

    /** Replaces a working composition; the platform resolves entity, current store, and actor. */
    @PutMapping("/biz/x/food/bundles/{skuNo}/definition")
    @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")
    public BundleMutationVO replaceDefinition(@PathVariable("skuNo") @NotBlank @Size(max=32) String skuNo,
            @Valid @RequestBody ReplaceBundleDefinitionReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key, HttpServletRequest request) {
        queryFields(request, Set.of());
        var context = BizContext.current();
        if (context.currentStoreNo() == null) throw BizException.of(ErrorCode.BAD_REQUEST);
        requireStore(context.currentStoreNo(), request);
        return service.replaceDefinition(context.merchantNo(), context.currentStoreNo(), skuNo,
                SecurityUtils.currentUserNo(), req, key);
    }

    /** Reads working rows without depending on remote catalog uptime or claiming sale availability. */
    @GetMapping("/biz/x/food/stores/{storeNo}/bundles/{configNo}")
    @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")
    public GetBundleVO getBundle(@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo,
            @PathVariable("configNo") @NotBlank @Size(max=32) String number, HttpServletRequest request) {
        queryFields(request, Set.of());
        var context = requireStore(storeNo, request);
        return service.getBundle(context.merchantNo(), storeNo, number);
    }

    /** Pagination operates on the entity library, not a customer-facing menu. */
    @GetMapping("/biz/x/food/stores/{storeNo}/bundles")
    @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")
    public PageData<BundleConfigVO> listBundles(@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo,
            @Valid @ModelAttribute ListBundlesQuery query, HttpServletRequest request) {
        queryFields(request, Set.of("page", "size"));
        var context = requireStore(storeNo, request);
        return service.listBundles(context.merchantNo(), storeNo, query);
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
