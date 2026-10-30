package ai.neargo.shop.food.api.biz;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.api.FoodBizStoreScope;
import ai.neargo.shop.food.api.FoodRequestScopeValidator;
import ai.neargo.shop.food.config.ConditionalOnFoodWebEnabled;
import ai.neargo.shop.food.dto.BottleCustodyContracts.Custody;
import ai.neargo.shop.food.dto.BottleCustodyContracts.Deposit;
import ai.neargo.shop.food.dto.BottleCustodyContracts.Movement;
import ai.neargo.shop.food.dto.BottleCustodyContracts.Result;
import ai.neargo.shop.food.dto.BottleCustodyContracts.Withdraw;
import ai.neargo.shop.food.service.BottleCustodyService;
import ai.neargo.shop.spi.user.StoreDirectoryPort;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/**
 * Original current-store and permission guards apply to every custody mutation and property query.
 */
@RestController
@Profile("api")
@ConditionalOnFoodWebEnabled
@ConditionalOnProperty(name = "shop.food.custody.enabled", havingValue = "true")
@PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")
public class BizBottleCustodyController {
    private final BottleCustodyService service;
    private final FoodBizStoreScope scope;

    public BizBottleCustodyController(
            BottleCustodyService service,
            StoreDirectoryPort stores,
            FoodRequestScopeValidator validator) {
        this.service = service;
        this.scope = new FoodBizStoreScope(stores, validator);
    }

    @PostMapping("/biz/x/food/bottle-custody")
    public Result deposit(
            @RequestBody Deposit body,
            @RequestHeader("Idempotency-Key") String key,
            HttpServletRequest request) {
        query(request, Set.of());
        var current = scope.current(request);
        return service.deposit(
                current.merchantNo(),
                current.currentStoreNo(),
                SecurityUtils.currentUserNo(),
                body,
                key);
    }

    @PostMapping("/biz/x/food/bottle-custody/{custodyNo}/withdrawals")
    public Result withdraw(
            @PathVariable String custodyNo,
            @RequestBody Withdraw body,
            @RequestHeader("Idempotency-Key") String key,
            HttpServletRequest request) {
        query(request, Set.of());
        var current = scope.current(request);
        return service.withdraw(
                current.merchantNo(),
                current.currentStoreNo(),
                SecurityUtils.currentUserNo(),
                custodyNo,
                body,
                key);
    }

    @GetMapping("/biz/x/food/bottle-custody/{custodyNo}")
    public Custody get(@PathVariable String custodyNo, HttpServletRequest request) {
        query(request, Set.of());
        var current = scope.current(request);
        return service.get(current.merchantNo(), current.currentStoreNo(), custodyNo);
    }

    @GetMapping("/biz/x/food/bottle-custody")
    public PageData<Custody> list(
            @RequestParam String customerNo,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        query(request, Set.of("customerNo", "page", "size"));
        var current = scope.current(request);
        return service.list(current.merchantNo(), current.currentStoreNo(), customerNo, page, size);
    }

    @GetMapping("/biz/x/food/bottle-custody/{custodyNo}/movements")
    public PageData<Movement> movements(
            @PathVariable String custodyNo,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        query(request, Set.of("page", "size"));
        var current = scope.current(request);
        return service.movements(
                current.merchantNo(), current.currentStoreNo(), custodyNo, page, size);
    }

    private static void query(HttpServletRequest request, Set<String> allowed) {
        if (!allowed.containsAll(request.getParameterMap().keySet())
                || request.getParameterMap().values().stream()
                        .anyMatch(values -> values.length != 1))
            throw BizException.of(ErrorCode.BAD_REQUEST);
    }
}
