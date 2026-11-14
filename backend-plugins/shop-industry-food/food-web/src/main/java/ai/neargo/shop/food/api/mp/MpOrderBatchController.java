package ai.neargo.shop.food.api.mp;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.config.ConditionalOnFoodWebEnabled;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration.PolicyEnabled;
import ai.neargo.shop.food.dto.CartAccess;
import ai.neargo.shop.food.dto.CreateDiningOrderReq;
import ai.neargo.shop.food.dto.DiningOrderBatchVO;
import ai.neargo.shop.food.service.DiningOrderService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Client supplies the displayed quote and source revision, never a payer identity or amount. */
@RestController
@Profile("api")
@ConditionalOnFoodWebEnabled
@Conditional(PolicyEnabled.class)
@Validated
public class MpOrderBatchController {
    private final DiningOrderService service;
    public MpOrderBatchController(DiningOrderService service) { this.service = service; }
    @PostMapping("/mp/x/food/stores/{storeNo}/batches")
    public DiningOrderBatchVO place(@PathVariable @NotBlank @Size(max = 32) String storeNo,
            @Valid @RequestBody CreateDiningOrderReq body,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            @RequestParam(required = false) @Size(max = 32) String sessionNo,
            @RequestHeader(value = "X-Food-Session", required = false) @Size(max = 128) String token,
            HttpServletRequest request) {
        parameters(request); return service.customerPlace(storeNo, access(sessionNo, token), body, key);
    }
    @GetMapping("/mp/x/food/batches/{batchNo}")
    public DiningOrderBatchVO get(@PathVariable @NotBlank @Size(max = 32) String batchNo,
            @RequestParam(required = false) @Size(max = 32) String sessionNo,
            @RequestHeader(value = "X-Food-Session", required = false) @Size(max = 128) String token,
            HttpServletRequest request) {
        parameters(request); return service.customerGet(access(sessionNo, token), batchNo);
    }
    private static CartAccess access(String session, String token) {
        String buyer = SecurityUtils.currentUser().filter(u -> u.isConsumer()).map(u -> u.userNo()).orElse(null);
        return new CartAccess(session, token, buyer);
    }
    private static void parameters(HttpServletRequest request) {
        if (!Set.of("sessionNo").containsAll(request.getParameterMap().keySet())
                || request.getParameterMap().values().stream().anyMatch(v -> v.length != 1)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }
}
