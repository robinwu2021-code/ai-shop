package ai.neargo.shop.food.api.biz;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.food.api.FoodBizStoreScope;
import ai.neargo.shop.food.api.FoodRequestScopeValidator;
import ai.neargo.shop.food.config.ConditionalOnFoodWebEnabled;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration.PolicyEnabled;
import ai.neargo.shop.food.dto.AuthorizeCartSessionReq;
import ai.neargo.shop.food.dto.CartResultVO;
import ai.neargo.shop.food.dto.CartSessionGrantVO;
import ai.neargo.shop.food.dto.CartVersionReq;
import ai.neargo.shop.food.dto.CartVersionVO;
import ai.neargo.shop.food.dto.CreateCartReq;
import ai.neargo.shop.food.dto.OperationResultVO;
import ai.neargo.shop.food.dto.ReplaceCartLineReq;
import ai.neargo.shop.food.service.CheckCartService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Staff ownership and store scope are resolved from the authenticated platform context. */
@RestController
@Profile("api")
@ConditionalOnFoodWebEnabled
@Conditional(PolicyEnabled.class)
@Validated
public class BizCartController {
    private final CheckCartService service;
    private final FoodBizStoreScope scope;
    public BizCartController(CheckCartService service, StoreDirectoryPort stores, FoodRequestScopeValidator validator) {
        this.service = service;
        this.scope = new FoodBizStoreScope(stores, validator);
    }
    @GetMapping("/biz/x/food/carts/{cartNo}")
    @PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')")
    public CartResultVO getCart(
            @PathVariable("cartNo") @NotBlank @Size(max = 32) String cartNo,
            HttpServletRequest request) {
        var context = scope.current(request);
        return service.getCart(context.merchantNo(), context.currentStoreNo(), cartNo);
    }
    @PostMapping("/biz/x/food/stores/{storeNo}/carts")
    @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")
    public CartResultVO createCart(
            @PathVariable("storeNo") @NotBlank @Size(max = 32) String storeNo,
            @Valid @RequestBody CreateCartReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest request) {
        var context = scope.path(storeNo, request);
        return service.createCart(context.merchantNo(), storeNo, SecurityUtils.currentUserNo(), req, key);
    }
    @PutMapping("/biz/x/food/carts/{cartNo}/lines/{sourceLineRef}")
    @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")
    public CartVersionVO replaceLine(
            @PathVariable("cartNo") @NotBlank @Size(max = 32) String cartNo,
            @PathVariable("sourceLineRef") @NotBlank @Size(max = 64) String sourceLineRef,
            @Valid @RequestBody ReplaceCartLineReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest request) {
        var context = scope.current(request);
        return service.replaceLine(context.merchantNo(), context.currentStoreNo(), cartNo, sourceLineRef,
                SecurityUtils.currentUserNo(), req, key);
    }
    @PostMapping("/biz/x/food/carts/{cartNo}/lines/{sourceLineRef}:archive")
    @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")
    public CartVersionVO archiveLine(
            @PathVariable("cartNo") @NotBlank @Size(max = 32) String cartNo,
            @PathVariable("sourceLineRef") @NotBlank @Size(max = 64) String sourceLineRef,
            @Valid @RequestBody CartVersionReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest request) {
        var context = scope.current(request);
        return service.archiveLine(context.merchantNo(), context.currentStoreNo(), cartNo, sourceLineRef,
                SecurityUtils.currentUserNo(), req, key);
    }
    @PostMapping("/biz/x/food/carts/{cartNo}:archive")
    @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")
    public OperationResultVO archiveCart(
            @PathVariable("cartNo") @NotBlank @Size(max = 32) String cartNo,
            @Valid @RequestBody CartVersionReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest request) {
        var context = scope.current(request);
        return service.archiveCart(context.merchantNo(), context.currentStoreNo(), cartNo,
                SecurityUtils.currentUserNo(), req, key);
    }
    @PostMapping("/biz/x/food/checks/{checkNo}/cart-sessions")
    @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")
    public CartSessionGrantVO authorizeSession(
            @PathVariable("checkNo") @NotBlank @Size(max = 32) String checkNo,
            @Valid @RequestBody AuthorizeCartSessionReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest request) {
        var context = scope.current(request);
        return service.authorizeSession(context.merchantNo(), context.currentStoreNo(), checkNo,
                SecurityUtils.currentUserNo(), req, key);
    }
}
