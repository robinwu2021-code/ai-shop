package ai.neargo.shop.food.api.biz;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.api.FoodBizStoreScope;
import ai.neargo.shop.food.api.FoodRequestScopeValidator;
import ai.neargo.shop.food.config.ConditionalOnFoodWebEnabled;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration.PolicyEnabled;
import ai.neargo.shop.food.dto.BusinessShareContracts.CancelReq;
import ai.neargo.shop.food.dto.BusinessShareContracts.ConfirmReq;
import ai.neargo.shop.food.dto.BusinessShareContracts.CreateReq;
import ai.neargo.shop.food.dto.BusinessShareContracts.PlanVO;
import ai.neargo.shop.food.service.BusinessShareService;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Employees allocate responsibility in their trusted store; these routes grant no customer settlement authority. */
@RestController
@Profile("api")
@ConditionalOnFoodWebEnabled
@Conditional(PolicyEnabled.class)
@Validated
public class BizBusinessShareController {
    private final BusinessShareService service;
    private final FoodBizStoreScope scope;
    public BizBusinessShareController(BusinessShareService service,StoreDirectoryPort stores,
            FoodRequestScopeValidator validator) { this.service=service;scope=new FoodBizStoreScope(stores,validator); }
    @PostMapping("/biz/x/food/checks/{checkNo}/business-share-plans")
    @PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')")
    public PlanVO create(@PathVariable @NotBlank @Size(max=32) String checkNo,@Valid @RequestBody CreateReq request,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,HttpServletRequest http) {
        noQuery(http);var context=scope.current(http);
        return service.create(context.merchantNo(),context.currentStoreNo(),SecurityUtils.currentUserNo(),
                checkNo,request,key);
    }
    @GetMapping("/biz/x/food/checks/{checkNo}/business-share-plans/{planNo}")
    @PreAuthorize("@perm.canBiz('"+BizPerms.ORDER_VIEW+"')")
    public PlanVO get(@PathVariable @NotBlank @Size(max=32) String checkNo,
            @PathVariable @NotBlank @Size(max=32) String planNo,HttpServletRequest http) {
        noQuery(http);var context=scope.current(http);
        return service.get(context.merchantNo(),context.currentStoreNo(),checkNo,planNo);
    }
    @PostMapping("/biz/x/food/checks/{checkNo}/business-share-plans/{planNo}:confirm")
    @PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')")
    public PlanVO confirm(@PathVariable @NotBlank @Size(max=32) String checkNo,
            @PathVariable @NotBlank @Size(max=32) String planNo,@Valid @RequestBody ConfirmReq request,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,HttpServletRequest http) {
        noQuery(http);var context=scope.current(http);
        return service.confirm(context.merchantNo(),context.currentStoreNo(),SecurityUtils.currentUserNo(),
                checkNo,planNo,request,key);
    }
    @PostMapping("/biz/x/food/checks/{checkNo}/business-share-plans/{planNo}:cancel")
    @PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')")
    public PlanVO cancel(@PathVariable @NotBlank @Size(max=32) String checkNo,
            @PathVariable @NotBlank @Size(max=32) String planNo,@Valid @RequestBody CancelReq request,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,HttpServletRequest http) {
        noQuery(http);var context=scope.current(http);
        return service.cancel(context.merchantNo(),context.currentStoreNo(),SecurityUtils.currentUserNo(),
                checkNo,planNo,request,key);
    }
    private static void noQuery(HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) throw BizException.of(ErrorCode.BAD_REQUEST);
    }
}
