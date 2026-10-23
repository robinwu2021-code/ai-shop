package ai.neargo.shop.food.api.mp;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.config.ConditionalOnFoodWebEnabled;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration.PolicyEnabled;
import ai.neargo.shop.food.dto.CartAccess;
import ai.neargo.shop.food.dto.DiningReturnApplyReq;
import ai.neargo.shop.food.dto.DiningReturnVO;
import ai.neargo.shop.food.service.DiningReturnService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Buyer identity is taken only from consumer login; the service verifies the original closed cart and batch. */
@RestController
@Profile("api")
@ConditionalOnFoodWebEnabled
@Conditional(PolicyEnabled.class)
@Validated
public class MpDiningReturnController {
    private final DiningReturnService service;
    public MpDiningReturnController(DiningReturnService service) { this.service = service; }
    @PostMapping("/mp/x/food/stores/{storeNo}/returns")
    public DiningReturnVO apply(@PathVariable @NotBlank @Size(max = 32) String storeNo,
            @Valid @RequestBody DiningReturnApplyReq body,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key, HttpServletRequest request) {
        parameters(request); return service.customerApply(storeNo, access(), body, key);
    }
    @GetMapping("/mp/x/food/returns/{returnNo}")
    public DiningReturnVO get(@PathVariable @NotBlank @Size(max = 32) String returnNo, HttpServletRequest request) {
        parameters(request); return service.customerGet(access(), returnNo);
    }
    @PostMapping("/mp/x/food/returns/{returnNo}:cancel")
    public DiningReturnVO cancel(@PathVariable @NotBlank @Size(max = 32) String returnNo,
            @RequestBody(required = false) byte[] body, HttpServletRequest request) {
        parameters(request);
        if (body != null && body.length != 0) throw BizException.of(ErrorCode.BAD_REQUEST);
        return service.customerCancel(access(), returnNo);
    }
    private static CartAccess access() {
        String buyer = SecurityUtils.currentUser().filter(user -> user.isConsumer()).map(user -> user.userNo())
                .orElseThrow(() -> BizException.of(ErrorCode.FORBIDDEN));
        return new CartAccess(null, null, buyer);
    }
    private static void parameters(HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) throw BizException.of(ErrorCode.BAD_REQUEST);
    }
}
