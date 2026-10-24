package ai.neargo.shop.food.api.biz;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.food.api.FoodBizStoreScope;
import ai.neargo.shop.food.api.FoodRequestScopeValidator;
import ai.neargo.shop.food.config.ConditionalOnFoodWebEnabled;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration.PolicyEnabled;
import ai.neargo.shop.food.dto.DiningQuoteReq;
import ai.neargo.shop.food.dto.DiningQuoteConfirmReq;
import ai.neargo.shop.food.service.DiningPricingService;
import ai.neargo.shop.spi.trade.PricingCheckoutPort.QuoteVO;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
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

/** Staff quote preview and final revalidation grant neither a charge nor manual-discount authority. */
@RestController
@Profile("api")
@ConditionalOnFoodWebEnabled
@Conditional(PolicyEnabled.class)
@Validated
public class BizDiningPricingController {
    private final DiningPricingService service;
    private final FoodBizStoreScope scope;
    public BizDiningPricingController(DiningPricingService service, StoreDirectoryPort stores,
            FoodRequestScopeValidator validator) {
        this.service = service; this.scope = new FoodBizStoreScope(stores, validator);
    }
    @PostMapping("/biz/x/food/stores/{storeNo}/quotes")
    @PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')")
    public QuoteVO quote(@PathVariable @NotBlank @Size(max = 32) String storeNo,
            @Valid @RequestBody DiningQuoteReq body,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest request) {
        var context = scope.path(storeNo, request);
        return service.staffQuote(context.merchantNo(), storeNo, SecurityUtils.currentUserNo(), body, key);
    }
    @GetMapping("/biz/x/food/quotes/{quoteNo}")
    @PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')")
    public QuoteVO get(@PathVariable @NotBlank @Size(max = 32) String quoteNo,
            @RequestParam @NotBlank @Size(max = 32) String cartNo, @RequestParam @Min(0) long version,
            HttpServletRequest request) {
        var context = scope.current(request);
        return service.staffGet(context.merchantNo(), context.currentStoreNo(), SecurityUtils.currentUserNo(),
                cartNo, version, quoteNo);
    }
    @PostMapping("/biz/x/food/quotes/{quoteNo}:confirm")
    @PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')")
    public QuoteVO confirm(@PathVariable @NotBlank @Size(max = 32) String quoteNo,
            @Valid @RequestBody DiningQuoteConfirmReq body, HttpServletRequest request) {
        var context = scope.current(request);
        return service.staffConfirm(context.merchantNo(), context.currentStoreNo(), SecurityUtils.currentUserNo(),
                quoteNo, body);
    }
}
