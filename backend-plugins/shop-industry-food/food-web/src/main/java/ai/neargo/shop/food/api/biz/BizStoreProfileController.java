package ai.neargo.shop.food.api.biz;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.food.api.FoodBizStoreScope;
import ai.neargo.shop.food.api.FoodRequestScopeValidator;
import ai.neargo.shop.food.api.StoreProfileAssembler;
import ai.neargo.shop.food.config.ConditionalOnFoodWebEnabled;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration.PolicyEnabled;
import ai.neargo.shop.food.dto.StoreProfileVO;
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

/** 门店基本信息（POS 需求 B6）：时区、币种、当前营业日一次给全；读权限用店员与终端都有的订单查看。 */
@RestController
@Profile("api")
@ConditionalOnFoodWebEnabled
@Conditional(PolicyEnabled.class)
@Validated
public class BizStoreProfileController {
    private final StoreProfileAssembler profiles;
    private final FoodBizStoreScope scope;

    public BizStoreProfileController(StoreProfileAssembler profiles, StoreDirectoryPort stores,
            FoodRequestScopeValidator validator) {
        this.profiles = profiles;
        this.scope = new FoodBizStoreScope(stores, validator);
    }

    @GetMapping("/biz/x/food/stores/{storeNo}/profile")
    @PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')")
    public StoreProfileVO profile(@PathVariable @NotBlank @Size(max = 32) String storeNo, HttpServletRequest request) {
        var context = scope.path(storeNo, request);
        return profiles.profile(context.merchantNo(), storeNo);
    }
}
