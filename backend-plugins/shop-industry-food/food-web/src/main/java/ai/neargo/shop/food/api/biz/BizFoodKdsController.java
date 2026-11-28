package ai.neargo.shop.food.api.biz;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.food.api.FoodBizStoreScope;
import ai.neargo.shop.food.api.FoodRequestScopeValidator;
import ai.neargo.shop.food.api.stream.FoodKdsStreamTransport;
import ai.neargo.shop.food.config.ConditionalOnFoodWebEnabled;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration;
import ai.neargo.shop.food.dto.FoodKdsContracts.GrantReq;
import ai.neargo.shop.food.dto.FoodKdsContracts.RevokeReq;
import ai.neargo.shop.food.dto.FoodKdsContracts.SubscriptionVO;
import ai.neargo.shop.food.service.FoodKdsService;
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
    private final FoodKdsStreamTransport streams;
    private final FoodBizStoreScope scope;

    public BizFoodKdsController(
            FoodKdsService service,
            FoodKdsStreamTransport streams,
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

    /**
     * 屏的刷新通知流（TDD-餐饮域迁移新库 §2.8 D-3）：只推「来刷新」，不推业务数据。连上即推一次全量刷新，
     * 之后有变化就推、每 30 秒兜底推一次；屏收到后按工位调后厨任务列表、排号列表拉当前状态。
     */
    @GetMapping(value = "/biz/x/food/kitchen-stream", produces = "text/event-stream")
    @PreAuthorize("@perm.canBiz('" + BizPerms.RECEIVE + "')")
    public SseEmitter stream(
            @RequestParam @NotBlank @Size(max = 32) String subscriptionNo,
            HttpServletRequest request) {
        var c = scope.current(request);
        return streams.subscribe(
                c.merchantNo(), c.currentStoreNo(), SecurityUtils.currentUserNo(), subscriptionNo);
    }
}
