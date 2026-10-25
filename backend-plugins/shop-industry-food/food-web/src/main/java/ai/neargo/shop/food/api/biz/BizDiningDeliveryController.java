package ai.neargo.shop.food.api.biz;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.food.api.FoodBizStoreScope;
import ai.neargo.shop.food.api.FoodRequestScopeValidator;
import ai.neargo.shop.food.config.ConditionalOnFoodWebEnabled;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration;
import ai.neargo.shop.food.dto.DiningDeliveryContracts.DeliverySnapshot;
import ai.neargo.shop.food.dto.DiningDeliveryContracts.SaveReq;
import ai.neargo.shop.food.service.DiningDeliveryService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Keeps the BizDiningDeliveryController protocol scoped to original restaurant facts and lifecycle
 * authority.
 */
@RestController
@Profile("api")
@ConditionalOnFoodWebEnabled
@Conditional(FoodWebAutoConfiguration.PolicyEnabled.class)
@Validated
public class BizDiningDeliveryController {
    private final DiningDeliveryService service;
    private final FoodBizStoreScope scope;

    public BizDiningDeliveryController(
            DiningDeliveryService service,
            StoreDirectoryPort stores,
            FoodRequestScopeValidator validator) {
        this.service = service;
        scope = new FoodBizStoreScope(stores, validator);
    }

    @GetMapping("/biz/x/food/carts/{cartNo}/delivery")
    @PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')")
    public DeliverySnapshot get(
            @PathVariable @NotBlank @Size(max = 32) String cartNo, HttpServletRequest request) {
        var c = scope.current(request);
        return service.getStaff(c.merchantNo(), c.currentStoreNo(), cartNo);
    }

    @PutMapping("/biz/x/food/carts/{cartNo}/delivery")
    @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")
    public DeliverySnapshot save(
            @PathVariable @NotBlank @Size(max = 32) String cartNo,
            @Valid @RequestBody SaveReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest request) {
        var c = scope.current(request);
        return service.saveStaff(
                c.merchantNo(),
                c.currentStoreNo(),
                SecurityUtils.currentUserNo(),
                cartNo,
                req,
                key);
    }
}
