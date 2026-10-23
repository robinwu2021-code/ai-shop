package ai.neargo.shop.food.api.biz;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.food.api.FoodBizStoreScope;
import ai.neargo.shop.food.api.FoodRequestScopeValidator;
import ai.neargo.shop.food.config.ConditionalOnFoodWebEnabled;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration.PolicyEnabled;
import ai.neargo.shop.food.dto.BusinessCloseContracts.DayVO;
import ai.neargo.shop.food.dto.BusinessCloseContracts.ReportVO;
import ai.neargo.shop.food.dto.BusinessCloseContracts.CloseVO;
import ai.neargo.shop.food.dto.BusinessCloseContracts.PreviewReq;
import ai.neargo.shop.food.dto.BusinessCloseContracts.SignReq;
import ai.neargo.shop.food.dto.BusinessCloseContracts.AdjustmentReq;
import ai.neargo.shop.food.dto.OperationVO;
import ai.neargo.shop.food.service.BusinessCloseService;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;

/** Store-authorized non-cash reports and closing evidence. */
@RestController
@Profile("api")
@ConditionalOnFoodWebEnabled
@Conditional(PolicyEnabled.class)
@Validated
public class BizBusinessCloseController {
    private final BusinessCloseService service;
    private final FoodBizStoreScope scope;
    public BizBusinessCloseController(BusinessCloseService service,StoreDirectoryPort stores,
            FoodRequestScopeValidator validator) { this.service=service; scope=new FoodBizStoreScope(stores,validator); }
    @PostMapping("/biz/x/food/business-days/{date}:open")
    @PreAuthorize("@perm.canBiz('"+BizPerms.FINANCE+"')")
    public DayVO open(@PathVariable LocalDate date,@RequestHeader("Idempotency-Key") String key,
            HttpServletRequest request) {
        var c=scope.current(request);
        return service.open(c.merchantNo(),c.currentStoreNo(),SecurityUtils.currentUserNo(),date,key);
    }
    @GetMapping("/biz/x/food/reports/business-day")
    @PreAuthorize("@perm.canBiz('"+BizPerms.ORDER_VIEW+"')")
    public ReportVO report(@RequestParam LocalDate businessDate,@RequestParam String currency,
            HttpServletRequest request) {
        var c=scope.current(request);
        return service.report(c.merchantNo(),c.currentStoreNo(),SecurityUtils.currentUserNo(),
                new PreviewReq(businessDate,currency));
    }
    @PostMapping("/biz/x/food/closes:preview")
    @PreAuthorize("@perm.canBiz('"+BizPerms.FINANCE+"')")
    public CloseVO preview(@Valid @RequestBody PreviewReq body,@RequestHeader("Idempotency-Key") String key,
            HttpServletRequest request) {
        var c=scope.current(request);
        return service.preview(c.merchantNo(),c.currentStoreNo(),SecurityUtils.currentUserNo(),body,key);
    }
    @GetMapping("/biz/x/food/closes/{closeNo}")
    @PreAuthorize("@perm.canBiz('"+BizPerms.ORDER_VIEW+"')")
    public CloseVO get(@PathVariable @NotBlank @Size(max=32) String closeNo,HttpServletRequest request) {
        var c=scope.current(request);
        return service.get(c.merchantNo(),c.currentStoreNo(),SecurityUtils.currentUserNo(),closeNo);
    }
    @PostMapping("/biz/x/food/closes:sign")
    @PreAuthorize("@perm.canBiz('"+BizPerms.FINANCE+"')")
    public CloseVO sign(@Valid @RequestBody SignReq body,@RequestHeader("Idempotency-Key") String key,
            HttpServletRequest request) {
        var c=scope.current(request);
        return service.sign(c.merchantNo(),c.currentStoreNo(),SecurityUtils.currentUserNo(),body,key);
    }
    @PostMapping("/biz/x/food/closes:adjust")
    @PreAuthorize("@perm.canBiz('"+BizPerms.FINANCE+"')")
    public OperationVO adjust(@Valid @RequestBody AdjustmentReq body,@RequestHeader("Idempotency-Key") String key,
            HttpServletRequest request) {
        var c=scope.current(request);
        return service.adjust(c.merchantNo(),c.currentStoreNo(),SecurityUtils.currentUserNo(),body,key);
    }
}
