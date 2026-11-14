package ai.neargo.shop.food.api.biz;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.food.api.FoodBizStoreScope;
import ai.neargo.shop.food.api.FoodRequestScopeValidator;
import ai.neargo.shop.food.config.ConditionalOnFoodWebEnabled;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration.PolicyEnabled;
import ai.neargo.shop.food.dto.CounterSettlementContracts.CounterSettlementVO;
import ai.neargo.shop.food.dto.CounterSettlementContracts.FinalizeReq;
import ai.neargo.shop.food.service.CounterSettlementService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 柜台后付整桌结账（TDD-餐饮柜台后付结账）：冻结 → 外部记账收款 → 确认收齐 → 关单释放。
 * 收款本身走平台外部记账接口（{@code bizNo} = 结算单号），这里只编排冻结与确认。
 */
@RestController
@Profile("api")
@ConditionalOnFoodWebEnabled
@Conditional(PolicyEnabled.class)
@Validated
public class BizCounterSettlementController {
    private final CounterSettlementService service;
    private final FoodBizStoreScope scope;

    public BizCounterSettlementController(CounterSettlementService service, StoreDirectoryPort stores,
            FoodRequestScopeValidator validator) {
        this.service = service;
        this.scope = new FoodBizStoreScope(stores, validator);
    }

    /** 冻结整桌账单并生成结算单；同一个 Idempotency-Key 重放返回同一张单。 */
    @PostMapping("/biz/x/food/checks/{checkNo}/counter-settlements")
    @PreAuthorize("@perm.canBiz('" + BizPerms.CASH_RECEIVE + "')")
    public CounterSettlementVO create(@PathVariable @NotBlank @Size(max = 32) String checkNo,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key, HttpServletRequest request) {
        var context = scope.current(request);
        return service.create(context.merchantNo(), context.currentStoreNo(), SecurityUtils.currentUserNo(), checkNo, key);
    }

    /** 确认收齐：核心核验外部记账并把整桌后付单推到已付，随后关单、尝试释放桌台。可重放。 */
    @PostMapping("/biz/x/food/counter-settlements/{settlementNo}:finalize")
    @PreAuthorize("@perm.canBiz('" + BizPerms.CASH_RECEIVE + "')")
    public CounterSettlementVO finalizeSettlement(@PathVariable @NotBlank @Size(max = 32) String settlementNo,
            @Valid @RequestBody FinalizeReq req, HttpServletRequest request) {
        var context = scope.current(request);
        return service.finalize(context.merchantNo(), context.currentStoreNo(), SecurityUtils.currentUserNo(),
                settlementNo, req.offlineRecordNos());
    }

    /** 放弃结账、解冻账单；核心已确认收款后不可撤销。 */
    @PostMapping("/biz/x/food/counter-settlements/{settlementNo}:cancel")
    @PreAuthorize("@perm.canBiz('" + BizPerms.CASH_RECEIVE + "')")
    public CounterSettlementVO cancel(@PathVariable @NotBlank @Size(max = 32) String settlementNo,
            HttpServletRequest request) {
        var context = scope.current(request);
        return service.cancel(context.merchantNo(), context.currentStoreNo(), SecurityUtils.currentUserNo(), settlementNo);
    }

    /** 读用订单查看权限（规则 R6）。 */
    @GetMapping("/biz/x/food/counter-settlements/{settlementNo}")
    @PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')")
    public CounterSettlementVO get(@PathVariable @NotBlank @Size(max = 32) String settlementNo,
            HttpServletRequest request) {
        var context = scope.current(request);
        return service.get(context.merchantNo(), context.currentStoreNo(), settlementNo);
    }
}
