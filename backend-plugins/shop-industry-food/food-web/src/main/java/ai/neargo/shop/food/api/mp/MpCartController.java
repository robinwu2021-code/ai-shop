package ai.neargo.shop.food.api.mp;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.config.ConditionalOnFoodWebEnabled;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration.PolicyEnabled;
import ai.neargo.shop.food.dto.CartAccess;
import ai.neargo.shop.food.dto.CartResultVO;
import ai.neargo.shop.food.dto.CartVersionReq;
import ai.neargo.shop.food.dto.CartVersionVO;
import ai.neargo.shop.food.dto.CreateCustomerCartReq;
import ai.neargo.shop.food.dto.ReplaceCartLineReq;
import ai.neargo.shop.food.service.CheckCartService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Set;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Consumer identity comes from login; anonymous QR credentials retain their original guest session. */
@RestController
@Profile("api")
@ConditionalOnFoodWebEnabled
@Conditional(PolicyEnabled.class)
@Validated
public class MpCartController {
    private final CheckCartService service;
    public MpCartController(CheckCartService service) { this.service = service; }
    @GetMapping("/mp/x/food/carts/{cartNo}")
    public CartResultVO customerGetCart(
            @PathVariable("cartNo") @NotBlank @Size(max = 32) String cartNo,
            @RequestParam(value = "sessionNo", required = false) @Size(max = 32) String sessionNo,
            @RequestHeader(value = "X-Food-Session", required = false) @Size(max = 128) String token,
            HttpServletRequest request) {
        parameters(request, Set.of("sessionNo"));
        var access = access(sessionNo, token);
        return service.customerGetCart(cartNo, access);
    }
    @PostMapping("/mp/x/food/stores/{storeNo}/carts")
    public CartResultVO customerCreateCart(
            @PathVariable("storeNo") @NotBlank @Size(max = 32) String storeNo,
            @Valid @RequestBody CreateCustomerCartReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            @RequestHeader(value = "X-Food-Session", required = false) @Size(max = 128) String token,
            HttpServletRequest request) {
        parameters(request, Set.of());
        var access = access(req.sessionNo(), token);
        return service.customerCreateCart(storeNo, access, req, key);
    }
    @PutMapping("/mp/x/food/carts/{cartNo}/lines/{sourceLineRef}")
    public CartVersionVO customerReplaceLine(
            @PathVariable("cartNo") @NotBlank @Size(max = 32) String cartNo,
            @PathVariable("sourceLineRef") @NotBlank @Size(max = 64) String sourceLineRef,
            @Valid @RequestBody ReplaceCartLineReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            @RequestParam(value = "sessionNo", required = false) @Size(max = 32) String sessionNo,
            @RequestHeader(value = "X-Food-Session", required = false) @Size(max = 128) String token,
            HttpServletRequest request) {
        parameters(request, Set.of("sessionNo"));
        var access = access(sessionNo, token);
        return service.customerReplaceLine(cartNo, sourceLineRef, access, req, key);
    }
    @PostMapping("/mp/x/food/carts/{cartNo}/lines/{sourceLineRef}:archive")
    public CartVersionVO customerArchiveLine(
            @PathVariable("cartNo") @NotBlank @Size(max = 32) String cartNo,
            @PathVariable("sourceLineRef") @NotBlank @Size(max = 64) String sourceLineRef,
            @Valid @RequestBody CartVersionReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            @RequestParam(value = "sessionNo", required = false) @Size(max = 32) String sessionNo,
            @RequestHeader(value = "X-Food-Session", required = false) @Size(max = 128) String token,
            HttpServletRequest request) {
        parameters(request, Set.of("sessionNo"));
        var access = access(sessionNo, token);
        return service.customerArchiveLine(cartNo, sourceLineRef, access, req, key);
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
