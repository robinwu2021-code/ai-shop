package ai.neargo.shop.food.api.mp;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.food.config.ConditionalOnFoodWebEnabled;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration;
import ai.neargo.shop.food.dto.CartAccess;
import ai.neargo.shop.food.dto.DiningDeliveryContracts.DeliverySnapshot;
import ai.neargo.shop.food.dto.DiningDeliveryContracts.SaveReq;
import ai.neargo.shop.food.service.DiningDeliveryService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Keeps the MpDiningDeliveryController protocol scoped to original restaurant facts and lifecycle
 * authority.
 */
@RestController
@Profile("api")
@ConditionalOnFoodWebEnabled
@Conditional(FoodWebAutoConfiguration.PolicyEnabled.class)
@Validated
public class MpDiningDeliveryController {
    private final DiningDeliveryService service;

    public MpDiningDeliveryController(DiningDeliveryService service) {
        this.service = service;
    }

    @GetMapping("/mp/x/food/carts/{cartNo}/delivery")
    public DeliverySnapshot get(
            @PathVariable @NotBlank @Size(max = 32) String cartNo,
            @RequestParam(value = "sessionNo", required = false) @Size(max = 32) String session,
            @RequestHeader(value = "X-Food-Session", required = false) @Size(max = 128)
                    String token) {
        return service.getCustomer(cartNo, access(session, token));
    }

    @PutMapping("/mp/x/food/carts/{cartNo}/delivery")
    public DeliverySnapshot save(
            @PathVariable @NotBlank @Size(max = 32) String cartNo,
            @RequestParam(value = "sessionNo", required = false) @Size(max = 32) String session,
            @RequestHeader(value = "X-Food-Session", required = false) @Size(max = 128)
                    String token,
            @Valid @RequestBody SaveReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key) {
        return service.saveCustomer(cartNo, access(session, token), req, key);
    }

    private CartAccess access(String session, String token) {
        return new CartAccess(
                session,
                token,
                SecurityUtils.currentUser()
                        .filter(u -> u.isConsumer())
                        .map(u -> u.userNo())
                        .orElse(null));
    }
}
