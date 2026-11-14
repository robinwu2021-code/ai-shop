package ai.neargo.shop.food.api.mp;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.config.ConditionalOnFoodWebEnabled;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration.PolicyEnabled;
import ai.neargo.shop.food.dto.CartAccess;
import ai.neargo.shop.food.dto.DiningQuoteReq;
import ai.neargo.shop.food.dto.DiningQuoteConfirmReq;
import ai.neargo.shop.food.service.DiningPricingService;
import ai.neargo.shop.spi.trade.PricingCheckoutPort.QuoteVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Set;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Buyer identity comes only from login; QR credentials are passed unchanged to the cart authority. */
@RestController
@Profile("api")
@ConditionalOnFoodWebEnabled
@Conditional(PolicyEnabled.class)
@Validated
public class MpDiningPricingController {
    private final DiningPricingService service;
    public MpDiningPricingController(DiningPricingService service) { this.service = service; }
    @PostMapping("/mp/x/food/stores/{storeNo}/quotes")
    public QuoteVO quote(@PathVariable @NotBlank @Size(max = 32) String storeNo,
            @Valid @RequestBody DiningQuoteReq body,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            @RequestParam(required = false) @Size(max = 32) String sessionNo,
            @RequestHeader(value = "X-Food-Session", required = false) @Size(max = 128) String token,
            HttpServletRequest request) {
        parameters(request, Set.of("sessionNo"));
        return service.customerQuote(storeNo, access(sessionNo, token), body, key);
    }
    @GetMapping("/mp/x/food/quotes/{quoteNo}")
    public QuoteVO get(@PathVariable @NotBlank @Size(max = 32) String quoteNo,
            @RequestParam @NotBlank @Size(max = 32) String cartNo, @RequestParam @Min(0) long version,
            @RequestParam(required = false) @Size(max = 32) String sessionNo,
            @RequestHeader(value = "X-Food-Session", required = false) @Size(max = 128) String token,
            HttpServletRequest request) {
        parameters(request, Set.of("cartNo", "version", "sessionNo"));
        return service.customerGet(access(sessionNo, token), cartNo, version, quoteNo);
    }
    @PostMapping("/mp/x/food/quotes/{quoteNo}:confirm")
    public QuoteVO confirm(@PathVariable @NotBlank @Size(max = 32) String quoteNo,
            @Valid @RequestBody DiningQuoteConfirmReq body,
            @RequestParam(required = false) @Size(max = 32) String sessionNo,
            @RequestHeader(value = "X-Food-Session", required = false) @Size(max = 128) String token,
            HttpServletRequest request) {
        parameters(request, Set.of("sessionNo"));
        return service.customerConfirm(access(sessionNo, token), quoteNo, body);
    }
    private static CartAccess access(String session, String token) {
        String buyer = SecurityUtils.currentUser().filter(u -> u.isConsumer()).map(u -> u.userNo()).orElse(null);
        return new CartAccess(session, token, buyer);
    }
    private static void parameters(HttpServletRequest request, Set<String> allowed) {
        if (!allowed.containsAll(request.getParameterMap().keySet())
                || request.getParameterMap().values().stream().anyMatch(v -> v.length != 1)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }
}
