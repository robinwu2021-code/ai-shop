package ai.neargo.shop.food.api.mp;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.config.ConditionalOnFoodWebEnabled;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration.PolicyEnabled;
import ai.neargo.shop.food.dto.CartAccess;
import ai.neargo.shop.food.dto.CreateDiningSettlementReq;
import ai.neargo.shop.food.dto.PayDiningSettlementReq;
import ai.neargo.shop.food.dto.DiningSettlementVersionReq;
import ai.neargo.shop.food.dto.DiningSettlementVO;
import ai.neargo.shop.food.service.DiningCheckoutService;
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

/** Initial checkout accepts the authenticated buyer's original full batch and no caller monetary fields. */
@RestController
@Profile("api")
@ConditionalOnFoodWebEnabled
@Conditional(PolicyEnabled.class)
@Validated
public class MpSettlementController {
    private final DiningCheckoutService service;
    public MpSettlementController(DiningCheckoutService service) { this.service = service; }
    @PostMapping("/mp/x/food/batches/{batchNo}/settlements")
    public DiningSettlementVO create(@PathVariable @NotBlank @Size(max = 32) String batchNo,
            @Valid @RequestBody CreateDiningSettlementReq body,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key, HttpServletRequest request) {
        parameters(request); return service.customerCreate(access(), batchNo, body, key);
    }
    @GetMapping("/mp/x/food/settlements/{settlementNo}")
    public DiningSettlementVO get(@PathVariable @NotBlank @Size(max = 32) String settlementNo, HttpServletRequest request) {
        parameters(request); return service.customerQuery(access(), settlementNo);
    }
    @PostMapping("/mp/x/food/settlements/{settlementNo}:pay")
    public DiningSettlementVO pay(@PathVariable @NotBlank @Size(max = 32) String settlementNo,
            @Valid @RequestBody PayDiningSettlementReq body,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key, HttpServletRequest request) {
        parameters(request); return service.customerPay(access(), settlementNo, body, key);
    }
    @PostMapping("/mp/x/food/settlements/{settlementNo}:finalize")
    public DiningSettlementVO finalizeCheckout(@PathVariable @NotBlank @Size(max = 32) String settlementNo, HttpServletRequest request) {
        parameters(request); return service.customerFinalize(access(), settlementNo);
    }
    @PostMapping("/mp/x/food/settlements/{settlementNo}:cancel")
    public DiningSettlementVO cancel(@PathVariable @NotBlank @Size(max = 32) String settlementNo,
            @Valid @RequestBody DiningSettlementVersionReq body, HttpServletRequest request) {
        parameters(request); return service.customerCancel(access(), settlementNo, body);
    }
    private static CartAccess access() {
        String buyer = SecurityUtils.currentUser().filter(u -> u.isConsumer()).map(u -> u.userNo()).orElse(null);
        if (buyer == null) throw BizException.of(ErrorCode.FORBIDDEN); return new CartAccess(null, null, buyer);
    }
    private static void parameters(HttpServletRequest request) { if (!request.getParameterMap().isEmpty()) throw BizException.of(ErrorCode.BAD_REQUEST); }
}
