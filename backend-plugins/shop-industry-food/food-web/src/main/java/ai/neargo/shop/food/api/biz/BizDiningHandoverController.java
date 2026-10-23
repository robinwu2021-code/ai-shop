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
import ai.neargo.shop.food.dto.KitchenContracts.HandoverReq;
import ai.neargo.shop.food.dto.KitchenContracts.HandoverVO;
import ai.neargo.shop.food.service.KitchenTaskService;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Local handover evidence is scoped to the trusted store and remains explicitly pending core delivery. */
@RestController
@Profile("api")
@ConditionalOnFoodWebEnabled
@Conditional(PolicyEnabled.class)
@Validated
public class BizDiningHandoverController {
    private final KitchenTaskService service;
    private final StoreDirectoryPort stores;
    private final FoodRequestScopeValidator scope;
    public BizDiningHandoverController(KitchenTaskService service,StoreDirectoryPort stores,
            FoodRequestScopeValidator scope) { this.service=service; this.stores=stores; this.scope=scope; }

    @PostMapping("/biz/x/food/batches/{batchNo}:handover")
    @PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')")
    public HandoverVO handover(@PathVariable @NotBlank @Size(max=32) String batchNo,
            @Valid @RequestBody HandoverReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,HttpServletRequest request) {
        var c=BizContext.current(); requireStore(c.currentStoreNo(),request,Set.of());
        return service.handover(c.merchantNo(),c.currentStoreNo(),SecurityUtils.currentUserNo(),batchNo,req,key);
    }
    private BizContext requireStore(String store,HttpServletRequest request,Set<String> fields) {
        if (!fields.containsAll(request.getParameterMap().keySet())
                || request.getParameterMap().values().stream().anyMatch(v -> v.length!=1)) throw bad();
        var c=BizContext.current();
        scope.requireStoreTarget(c.merchantNo(),c.currentStoreNo(),c.merchantNo(),store,
                request.getHeader(BizContextFilter.STORE_HEADER),c.storeNos());
        var card=stores.cards(List.of(store)).get(store); if (card==null) throw BizException.of(ErrorCode.NOT_FOUND);
        scope.requireStoreTarget(c.merchantNo(),c.currentStoreNo(),card.entityNo(),card.storeNo(),
                request.getHeader(BizContextFilter.STORE_HEADER),c.storeNos()); return c;
    }
    private static BizException bad() { return BizException.of(ErrorCode.BAD_REQUEST); }
}
