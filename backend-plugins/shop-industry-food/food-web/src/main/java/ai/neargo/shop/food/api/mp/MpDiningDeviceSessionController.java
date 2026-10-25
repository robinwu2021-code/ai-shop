package ai.neargo.shop.food.api.mp;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.config.ConditionalOnFoodWebEnabled;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration;
import ai.neargo.shop.food.dto.DiningIdentityContracts.DeviceSessionReq;
import ai.neargo.shop.food.dto.DiningIdentityContracts.SessionCredentialVO;
import ai.neargo.shop.food.service.DiningIdentityService;
import ai.neargo.shop.spi.user.StoreDirectoryPort;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Credential admission creates only a restricted CART_DEVICE session, never a platform login. */
@RestController
@Profile("api")
@ConditionalOnFoodWebEnabled
@Conditional(FoodWebAutoConfiguration.PolicyEnabled.class)
@Validated
public class MpDiningDeviceSessionController {
    private final DiningIdentityService service;
    private final StoreDirectoryPort stores;

    public MpDiningDeviceSessionController(
            DiningIdentityService service, StoreDirectoryPort stores) {
        this.service = service;
        this.stores = stores;
    }

    @PostMapping("/mp/x/food/stores/{storeNo}/dining-device-sessions")
    public SessionCredentialVO create(
            @PathVariable @NotBlank @Size(max = 32) String storeNo,
            @RequestHeader("X-Food-Device") @NotBlank @Size(max = 32) String device,
            @RequestHeader("X-Food-Device-Credential") @NotBlank @Size(max = 128) String credential,
            @Valid @RequestBody DeviceSessionReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key) {
        var card = stores.cards(List.of(storeNo)).get(storeNo);
        if (card == null || !card.active() || !storeNo.equals(card.storeNo())) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return service.createDeviceSession(card.entityNo(), storeNo, device, credential, req, key);
    }
}
