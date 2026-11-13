package ai.neargo.shop.food.api.biz;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.food.api.FoodBizStoreScope;
import ai.neargo.shop.food.api.FoodRequestScopeValidator;
import ai.neargo.shop.food.config.ConditionalOnFoodWebEnabled;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration.PolicyEnabled;
import ai.neargo.shop.food.dto.CashShiftContracts.CashShiftVO;
import ai.neargo.shop.food.dto.CashShiftContracts.SubmitReq;
import ai.neargo.shop.food.service.CashShiftService;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import jakarta.servlet.http.HttpServletRequest;
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

/** 现金交班单次提交（POS B14 / B15）：交班在店里、常离线，POS 是权威，云端存档与对账。 */
@RestController
@Profile("api")
@ConditionalOnFoodWebEnabled
@Conditional(PolicyEnabled.class)
@Validated
public class BizCashShiftController {
    private final CashShiftService service;
    private final FoodBizStoreScope scope;

    public BizCashShiftController(CashShiftService service, StoreDirectoryPort stores,
            FoodRequestScopeValidator validator) {
        this.service = service;
        this.scope = new FoodBizStoreScope(stores, validator);
    }

    @PostMapping("/biz/x/food/stores/{storeNo}/cash-shifts")
    @PreAuthorize("@perm.canBiz('" + BizPerms.CASH_RECEIVE + "')")
    public CashShiftVO submit(@PathVariable @NotBlank @Size(max = 32) String storeNo, @RequestBody SubmitReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key, HttpServletRequest request) {
        var context = scope.path(storeNo, request);
        return service.submit(context.merchantNo(), storeNo, SecurityUtils.currentUserNo(), req, key);
    }

    @GetMapping("/biz/x/food/stores/{storeNo}/cash-shifts/{shiftNo}")
    @PreAuthorize("@perm.canBiz('" + BizPerms.CASH_RECEIVE + "')")
    public CashShiftVO get(@PathVariable @NotBlank @Size(max = 32) String storeNo,
            @PathVariable @NotBlank @Size(max = 32) String shiftNo, HttpServletRequest request) {
        var context = scope.path(storeNo, request);
        return service.get(context.merchantNo(), storeNo, shiftNo);
    }
}
