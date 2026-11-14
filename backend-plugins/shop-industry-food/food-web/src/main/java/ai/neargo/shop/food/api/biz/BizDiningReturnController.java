package ai.neargo.shop.food.api.biz;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.api.FoodBizStoreScope;
import ai.neargo.shop.food.api.FoodRequestScopeValidator;
import ai.neargo.shop.food.config.ConditionalOnFoodWebEnabled;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration.PolicyEnabled;
import ai.neargo.shop.food.dto.DiningReturnVO;
import ai.neargo.shop.food.service.DiningReturnService;
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

/** Store-scoped FOOD source read only; no merchant approval, kitchen handling or refund execution is implied. */
@RestController
@Profile("api")
@ConditionalOnFoodWebEnabled
@Conditional(PolicyEnabled.class)
@Validated
public class BizDiningReturnController {
    private final DiningReturnService service;
    private final FoodBizStoreScope scope;
    public BizDiningReturnController(DiningReturnService service, StoreDirectoryPort stores,
                                    FoodRequestScopeValidator validator) {
        this.service = service; scope = new FoodBizStoreScope(stores, validator);
    }
    @GetMapping("/biz/x/food/returns/{returnNo}")
    @PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')")
    public DiningReturnVO get(@PathVariable @NotBlank @Size(max = 32) String returnNo, HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) throw BizException.of(ErrorCode.BAD_REQUEST);
        var actor = scope.current(request);
        return service.staffGet(actor.merchantNo(), actor.currentStoreNo(), SecurityUtils.currentUserNo(), returnNo);
    }
}
