package ai.neargo.shop.food.api.biz;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.api.FoodBizStoreScope;
import ai.neargo.shop.food.api.FoodRequestScopeValidator;
import ai.neargo.shop.food.config.ConditionalOnFoodWebEnabled;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration.PolicyEnabled;
import ai.neargo.shop.food.dto.BusinessReturnContracts.CancelReq;
import ai.neargo.shop.food.dto.BusinessReturnContracts.CreateReq;
import ai.neargo.shop.food.dto.BusinessReturnContracts.ReturnVO;
import ai.neargo.shop.food.service.BusinessReturnService;
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

/** Store staff request local disposition; no route grants authority to refund or adjust the original amount. */
@RestController
@Profile("api")
@ConditionalOnFoodWebEnabled
@Conditional(PolicyEnabled.class)
@Validated
public class BizBusinessReturnController {
    private final BusinessReturnService service;
    private final FoodBizStoreScope scope;
    public BizBusinessReturnController(BusinessReturnService service,StoreDirectoryPort stores,
            FoodRequestScopeValidator validator) { this.service=service;scope=new FoodBizStoreScope(stores,validator); }
    @PostMapping("/biz/x/food/batches/{batchNo}/business-returns")
    @PreAuthorize("@perm.canBiz('"+BizPerms.AFTERSALE+"')")
    public ReturnVO create(@PathVariable @NotBlank @Size(max=32) String batchNo,@Valid @RequestBody CreateReq request,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,HttpServletRequest http) {
        noQuery(http);var context=scope.current(http);
        return service.create(context.merchantNo(),context.currentStoreNo(),SecurityUtils.currentUserNo(),
                batchNo,request,key);
    }
    @GetMapping("/biz/x/food/business-returns/{returnNo}")
    @PreAuthorize("@perm.canBiz('"+BizPerms.ORDER_VIEW+"')")
    public ReturnVO get(@PathVariable @NotBlank @Size(max=32) String returnNo,HttpServletRequest http) {
        noQuery(http);var context=scope.current(http);
        return service.get(context.merchantNo(),context.currentStoreNo(),returnNo);
    }
    @PostMapping("/biz/x/food/business-returns/{returnNo}:cancel")
    @PreAuthorize("@perm.canBiz('"+BizPerms.AFTERSALE+"')")
    public ReturnVO cancel(@PathVariable @NotBlank @Size(max=32) String returnNo,@Valid @RequestBody CancelReq request,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,HttpServletRequest http) {
        noQuery(http);var context=scope.current(http);
        return service.cancel(context.merchantNo(),context.currentStoreNo(),SecurityUtils.currentUserNo(),
                returnNo,request,key);
    }
    private static void noQuery(HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) throw BizException.of(ErrorCode.BAD_REQUEST);
    }
}
