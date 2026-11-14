package ai.neargo.shop.food.api.biz;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.CancelDiningOrderReq;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.food.api.FoodBizStoreScope;
import ai.neargo.shop.food.api.FoodRequestScopeValidator;
import ai.neargo.shop.food.config.ConditionalOnFoodWebEnabled;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration.PolicyEnabled;
import ai.neargo.shop.food.dto.DiningOrderBatchVO;
import ai.neargo.shop.food.dto.CreateStaffDiningOrderReq;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import ai.neargo.shop.food.service.DiningOrderService;
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
import org.springframework.web.bind.annotation.RestController;

/** Original current-store scope remains mandatory for every staff read. */
@RestController
@Profile("api")
@ConditionalOnFoodWebEnabled
@Conditional(PolicyEnabled.class)
@Validated
public class BizOrderBatchController {
    private final DiningOrderService service;
    private final FoodBizStoreScope scope;
    public BizOrderBatchController(DiningOrderService service, StoreDirectoryPort stores,
            FoodRequestScopeValidator validator) {
        this.service = service; this.scope = new FoodBizStoreScope(stores, validator);
    }
    /** Staff scope comes from the current authenticated store; the body cannot choose an employee. */
    @PostMapping("/biz/x/food/stores/{storeNo}/batches")
    @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")
    public DiningOrderBatchVO place(@PathVariable @NotBlank @Size(max = 32) String storeNo,
            @Valid @RequestBody CreateStaffDiningOrderReq body,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest request) {
        var context = scope.path(storeNo, request);
        return service.staffPlace(context.merchantNo(), storeNo, SecurityUtils.currentUserNo(), body, key);
    }
    /** The server records staff intent and preserves unknown results until the original owner resolves them. */
    @PostMapping("/biz/x/food/batches/{batchNo}:cancel")
    @PreAuthorize("@perm.canBiz('" + BizPerms.VERIFY + "')")
    public DiningOrderBatchVO cancel(@PathVariable @NotBlank @Size(max = 32) String batchNo,
            @Valid @RequestBody CancelDiningOrderReq body,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) throw BizException.of(ErrorCode.BAD_REQUEST);
        var context = scope.current(request);
        return service.staffCancel(context.merchantNo(), context.currentStoreNo(), SecurityUtils.currentUserNo(),
                batchNo, body, key);
    }
    @GetMapping("/biz/x/food/batches/{batchNo}")
    @PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')")
    public DiningOrderBatchVO get(@PathVariable @NotBlank @Size(max = 32) String batchNo,
            HttpServletRequest request) {
        var context = scope.current(request);
        return service.staffGet(context.merchantNo(), context.currentStoreNo(), SecurityUtils.currentUserNo(), batchNo);
    }
}
