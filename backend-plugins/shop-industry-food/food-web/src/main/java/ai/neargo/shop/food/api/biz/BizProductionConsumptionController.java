package ai.neargo.shop.food.api.biz;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.api.FoodBizStoreScope;
import ai.neargo.shop.food.api.FoodRequestScopeValidator;
import ai.neargo.shop.food.config.ConditionalOnFoodWebEnabled;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration.PolicyEnabled;
import ai.neargo.shop.food.dto.ProductionContracts;
import ai.neargo.shop.food.dto.ProductionContracts.ConsumptionVO;
import ai.neargo.shop.food.dto.ProductionContracts.ListQuery;
import ai.neargo.shop.food.service.ProductionConsumptionService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Staff retries reference an existing source event; no stock quantity or monetary amount is
 * accepted.
 */
@RestController
@Profile("api")
@ConditionalOnFoodWebEnabled
@Conditional(PolicyEnabled.class)
@Validated
public class BizProductionConsumptionController {
    private final ProductionConsumptionService service;
    private final FoodBizStoreScope scope;

    public BizProductionConsumptionController(
            ProductionConsumptionService service,
            StoreDirectoryPort stores,
            FoodRequestScopeValidator validator) {
        this.service = service;
        scope = new FoodBizStoreScope(stores, validator);
    }

    @GetMapping("/biz/x/food/stores/{storeNo}/production-consumptions")
    @PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')")
    public PageData<ConsumptionVO> list(
            @PathVariable @NotBlank @Size(max = 32) String storeNo,
            @RequestParam LocalDate from,
            @RequestParam LocalDate to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String skuNo,
            @RequestParam(required = false) String itemRef,
            @RequestParam(required = false) String status,
            HttpServletRequest request) {
        var c = scope.path(storeNo, request);
        return service.list(
                c.merchantNo(),
                storeNo,
                SecurityUtils.currentUserNo(),
                new ListQuery(page, size, from, to, skuNo, itemRef, status));
    }

    @PostMapping("/biz/x/food/production-events/{eventNo}:consume")
    @PreAuthorize("@perm.canBiz('" + BizPerms.STOCK + "')")
    public ConsumptionVO consume(
            @PathVariable @NotBlank @Size(max = 32) String eventNo, HttpServletRequest request) {
        var c = scope.current(request);
        return service.consume(
                c.merchantNo(), c.currentStoreNo(), SecurityUtils.currentUserNo(), eventNo);
    }

    @PostMapping("/biz/x/food/production-tasks/{taskNo}:adjust")
    @PreAuthorize("@perm.canBiz('" + BizPerms.STOCK + "')")
    public ConsumptionVO adjust(
            @PathVariable @NotBlank @Size(max = 32) String taskNo,
            @RequestBody ProductionContracts.AdjustmentReq body,
            @RequestHeader("Idempotency-Key") String key,
            HttpServletRequest request) {
        var c = scope.current(request);
        return service.adjust(
                c.merchantNo(),
                c.currentStoreNo(),
                SecurityUtils.currentUserNo(),
                taskNo,
                body,
                key);
    }

    @PostMapping("/biz/x/food/production-events/{eventNo}:reverse")
    @PreAuthorize("@perm.canBiz('" + BizPerms.STOCK + "')")
    public ConsumptionVO reverse(
            @PathVariable @NotBlank @Size(max = 32) String eventNo,
            @RequestBody ProductionContracts.ReverseReq body,
            @RequestHeader("Idempotency-Key") String key,
            HttpServletRequest request) {
        var c = scope.current(request);
        return service.reverse(
                c.merchantNo(),
                c.currentStoreNo(),
                SecurityUtils.currentUserNo(),
                eventNo,
                body,
                key);
    }

    @GetMapping("/biz/x/food/production-events/{eventNo}")
    @PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')")
    public ConsumptionVO get(
            @PathVariable @NotBlank @Size(max = 32) String eventNo, HttpServletRequest request) {
        var c = scope.current(request);
        return service.get(
                c.merchantNo(), c.currentStoreNo(), SecurityUtils.currentUserNo(), eventNo);
    }
}
