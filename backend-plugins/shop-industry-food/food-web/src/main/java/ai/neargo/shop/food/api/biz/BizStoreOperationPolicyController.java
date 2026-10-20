package ai.neargo.shop.food.api.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizContextFilter;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.api.FoodRequestScopeValidator;
import ai.neargo.shop.food.config.ConditionalOnFoodWebEnabled;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration.PolicyEnabled;
import ai.neargo.shop.food.dto.CreatePolicyReq;
import ai.neargo.shop.food.dto.GetPolicyQuery;
import ai.neargo.shop.food.dto.OperationVO;
import ai.neargo.shop.food.dto.StoreOperationPolicyResultVO;
import ai.neargo.shop.food.service.StoreOperationService;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** User scope is resolved by the platform; public parameters never define the merchant identity. */
@RestController
@Profile("api")
@ConditionalOnFoodWebEnabled
@Conditional(PolicyEnabled.class)
@Validated
public class BizStoreOperationPolicyController {
    private final StoreOperationService service;
    private final StoreDirectoryPort stores;
    private final FoodRequestScopeValidator scope;

    public BizStoreOperationPolicyController(StoreOperationService service, StoreDirectoryPort stores,
            FoodRequestScopeValidator scope) {
        this.service = service; this.stores = stores; this.scope = scope;
    }

    /** Returns configuration even while closed; reading policy is not acceptance of a dining order. */
    @GetMapping("/biz/x/food/stores/{storeNo}/policy")
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE + "')")
    public StoreOperationPolicyResultVO getPolicy(@PathVariable("storeNo") @NotBlank @Size(max = 32) String storeNo,
            @Valid @ModelAttribute GetPolicyQuery query, HttpServletRequest request) {
        BizContext context = requireStore(storeNo, request);
        return service.getPolicy(context.merchantNo(), storeNo, query);
    }

    /** Publishes under the platform actor and original client key; no caller-defined identity is accepted. */
    @PostMapping("/biz/x/food/stores/{storeNo}/policies")
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE + "')")
    public OperationVO createPolicy(@PathVariable("storeNo") @NotBlank @Size(max = 32) String storeNo,
            @Valid @RequestBody CreatePolicyReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key, HttpServletRequest request) {
        BizContext context = requireStore(storeNo, request);
        return service.createPolicy(context.merchantNo(), storeNo, SecurityUtils.currentUserNo(), req, key);
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
