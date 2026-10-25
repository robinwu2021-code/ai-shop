package ai.neargo.shop.food.api.biz;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.api.FoodBizStoreScope;
import ai.neargo.shop.food.api.FoodRequestScopeValidator;
import ai.neargo.shop.food.config.ConditionalOnFoodWebEnabled;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration;
import ai.neargo.shop.food.dto.FoodKdsContracts.GrantReq;
import ai.neargo.shop.food.dto.FoodKdsContracts.ReplayVO;
import ai.neargo.shop.food.dto.FoodKdsContracts.RevokeReq;
import ai.neargo.shop.food.dto.FoodKdsContracts.SubscriptionVO;
import ai.neargo.shop.food.service.FoodKdsService;
import ai.neargo.shop.food.service.FoodKdsStreamService;
import ai.neargo.shop.spi.user.StoreDirectoryPort;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Keeps the BizFoodKdsController protocol scoped to original restaurant facts and lifecycle
 * authority.
 */
@RestController
@Profile("api")
@ConditionalOnFoodWebEnabled
@Conditional(FoodWebAutoConfiguration.PolicyEnabled.class)
@Validated
public class BizFoodKdsController {
    private final FoodKdsService service;
    private final FoodKdsStreamService streams;
    private final FoodBizStoreScope scope;

    public BizFoodKdsController(
            FoodKdsService service,
            FoodKdsStreamService streams,
            StoreDirectoryPort stores,
            FoodRequestScopeValidator validator) {
        this.service = service;
        this.streams = streams;
        scope = new FoodBizStoreScope(stores, validator);
    }

    @PostMapping("/biz/x/food/kitchen-subscriptions")
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')")
    public SubscriptionVO grant(
            @Valid @RequestBody GrantReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest request) {
        var c = scope.current(request);
        return service.grant(
                c.merchantNo(), c.currentStoreNo(), SecurityUtils.currentUserNo(), req, key);
    }

    @PostMapping("/biz/x/food/kitchen-subscriptions/{subscriptionNo}:revoke")
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')")
    public SubscriptionVO revoke(
            @PathVariable @NotBlank @Size(max = 32) String subscriptionNo,
            @Valid @RequestBody RevokeReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest request) {
        var c = scope.current(request);
        return service.revoke(
                c.merchantNo(),
                c.currentStoreNo(),
                SecurityUtils.currentUserNo(),
                subscriptionNo,
                req,
                key);
    }

    @GetMapping("/biz/x/food/kitchen-events")
    @PreAuthorize("@perm.canBiz('" + BizPerms.RECEIVE + "')")
    public ReplayVO replay(
            @RequestParam @NotBlank @Size(max = 32) String subscriptionNo,
            @RequestParam(defaultValue = "0") @Min(0) long after,
            @RequestParam(defaultValue = "100") @Min(1) @Max(100) int limit,
            HttpServletRequest request) {
        var c = scope.current(request);
        return service.replay(
                c.merchantNo(),
                c.currentStoreNo(),
                SecurityUtils.currentUserNo(),
                subscriptionNo,
                after,
                limit);
    }

    @GetMapping(value = "/biz/x/food/kitchen-stream", produces = "text/event-stream")
    @PreAuthorize("@perm.canBiz('" + BizPerms.RECEIVE + "')")
    public SseEmitter stream(
            @RequestParam @NotBlank @Size(max = 32) String subscriptionNo,
            @RequestParam(defaultValue = "0") @Min(0) long after,
            @RequestHeader(value = "Last-Event-ID", required = false) String last,
            HttpServletRequest request) {
        long cursor = after;
        if (last != null) {
            try {
                cursor = Long.parseLong(last);
                if (cursor < 0) {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException ex) {
                throw BizException.of(ErrorCode.BAD_REQUEST);
            }
        }
        var c = scope.current(request);
        return streams.subscribe(
                c.merchantNo(),
                c.currentStoreNo(),
                SecurityUtils.currentUserNo(),
                subscriptionNo,
                cursor);
    }
}
