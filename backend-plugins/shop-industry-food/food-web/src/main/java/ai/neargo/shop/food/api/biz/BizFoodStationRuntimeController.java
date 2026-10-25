package ai.neargo.shop.food.api.biz;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.food.api.FoodBizStoreScope;
import ai.neargo.shop.food.api.FoodRequestScopeValidator;
import ai.neargo.shop.food.config.ConditionalOnFoodWebEnabled;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration;
import ai.neargo.shop.food.dto.FoodStationRuntimeContracts.CommandVO;
import ai.neargo.shop.food.dto.FoodStationRuntimeContracts.ControlReq;
import ai.neargo.shop.food.dto.FoodStationRuntimeContracts.StateVO;
import ai.neargo.shop.food.dto.FoodStationRuntimeContracts.VoicePoll;
import ai.neargo.shop.food.dto.FoodStationRuntimeContracts.VoiceReceipt;
import ai.neargo.shop.food.service.FoodStationRuntimeService;
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

/**
 * Keeps the BizFoodStationRuntimeController protocol scoped to original restaurant facts and
 * lifecycle authority.
 */
@RestController
@Profile("api")
@ConditionalOnFoodWebEnabled
@Conditional(FoodWebAutoConfiguration.PolicyEnabled.class)
@Validated
public class BizFoodStationRuntimeController {
    private final FoodStationRuntimeService service;
    private final FoodBizStoreScope stores;

    public BizFoodStationRuntimeController(
            FoodStationRuntimeService service,
            StoreDirectoryPort directory,
            FoodRequestScopeValidator validator) {
        this.service = service;
        this.stores = new FoodBizStoreScope(directory, validator);
    }

    @GetMapping("/biz/x/food/stations/{stationNo}/runtime")
    @PreAuthorize("@perm.canBiz('" + BizPerms.RECEIVE + "')")
    public StateVO state(
            @PathVariable @NotBlank @Size(max = 32) String stationNo, HttpServletRequest request) {
        var c = stores.current(request);
        return service.state(c.merchantNo(), c.currentStoreNo(), stationNo);
    }

    @PostMapping("/biz/x/food/stations/{stationNo}/runtime")
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')")
    public CommandVO control(
            @PathVariable @NotBlank @Size(max = 32) String stationNo,
            @Valid @RequestBody ControlReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest request) {
        var c = stores.current(request);
        return service.control(
                c.merchantNo(),
                c.currentStoreNo(),
                SecurityUtils.currentUserNo(),
                stationNo,
                req,
                key);
    }

    @PostMapping("/biz/x/food/kitchen-subscriptions/{subscriptionNo}/voice:claim")
    @PreAuthorize("@perm.canBiz('" + BizPerms.RECEIVE + "')")
    public VoicePoll claim(
            @PathVariable @NotBlank @Size(max = 32) String subscriptionNo,
            HttpServletRequest request) {
        var c = stores.current(request);
        return new VoicePoll(
                service.claim(
                        c.merchantNo(),
                        c.currentStoreNo(),
                        SecurityUtils.currentUserNo(),
                        subscriptionNo));
    }

    @PostMapping("/biz/x/food/kitchen-subscriptions/{subscriptionNo}/voice:acknowledge")
    @PreAuthorize("@perm.canBiz('" + BizPerms.RECEIVE + "')")
    public CommandVO acknowledge(
            @PathVariable @NotBlank @Size(max = 32) String subscriptionNo,
            @Valid @RequestBody VoiceReceipt req,
            HttpServletRequest request) {
        var c = stores.current(request);
        return service.acknowledge(
                c.merchantNo(),
                c.currentStoreNo(),
                SecurityUtils.currentUserNo(),
                subscriptionNo,
                req);
    }
}
