package ai.neargo.shop.food.api.biz;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.food.api.FoodBizStoreScope;
import ai.neargo.shop.food.api.FoodRequestScopeValidator;
import ai.neargo.shop.food.config.ConditionalOnFoodWebEnabled;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration.PolicyEnabled;
import ai.neargo.shop.food.dto.OperationResultVO;
import ai.neargo.shop.food.service.CheckService;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Dining reception uses original platform permission and owner/store context. */
@RestController
@Profile("api")
@ConditionalOnFoodWebEnabled
@Conditional(PolicyEnabled.class)
@Validated
public class BizTableOperationController {
    private final CheckService service;
    private final FoodBizStoreScope scope;
    public BizTableOperationController(CheckService service,StoreDirectoryPort stores,FoodRequestScopeValidator validator) {
        this.service=service;this.scope=new FoodBizStoreScope(stores,validator);
    }
    @GetMapping("/biz/x/food/table-operations/{operationNo}")
    @PreAuthorize("@perm.canBiz('"+BizPerms.ORDER_VIEW+"')")
    public OperationResultVO getTableOperation(@PathVariable("operationNo") @NotBlank @Size(max=32) String number,
            HttpServletRequest request) {
        var context=scope.current(request);return service.getTableOperation(context.merchantNo(),context.currentStoreNo(),number);
    }
}
