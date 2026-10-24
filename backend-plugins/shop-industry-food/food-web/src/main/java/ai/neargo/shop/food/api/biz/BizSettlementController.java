package ai.neargo.shop.food.api.biz;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.food.api.FoodBizStoreScope;
import ai.neargo.shop.food.api.FoodRequestScopeValidator;
import ai.neargo.shop.food.config.ConditionalOnFoodWebEnabled;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration.PolicyEnabled;
import ai.neargo.shop.food.dto.DiningSettlementVO;
import ai.neargo.shop.food.service.DiningCheckoutService;
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

/** Actual store-authorized read only; staff funding/consent and cash require their own future contracts. */
@RestController
@Profile("api")
@ConditionalOnFoodWebEnabled
@Conditional(PolicyEnabled.class)
@Validated
public class BizSettlementController {
    private final DiningCheckoutService service;
    private final FoodBizStoreScope scope;
    public BizSettlementController(DiningCheckoutService service, StoreDirectoryPort stores, FoodRequestScopeValidator validator) {
        this.service = service; this.scope = new FoodBizStoreScope(stores, validator);
    }
    @GetMapping("/biz/x/food/settlements/{settlementNo}")
    @PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')")
    public DiningSettlementVO get(@PathVariable @NotBlank @Size(max = 32) String settlementNo, HttpServletRequest request) {
        var context = scope.current(request);
        return service.staffGet(context.merchantNo(), context.currentStoreNo(), SecurityUtils.currentUserNo(), settlementNo);
    }
}
