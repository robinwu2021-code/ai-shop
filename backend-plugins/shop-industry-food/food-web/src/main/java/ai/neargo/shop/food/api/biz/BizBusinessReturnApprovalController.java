package ai.neargo.shop.food.api.biz;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.food.api.FoodBizStoreScope;
import ai.neargo.shop.food.api.FoodRequestScopeValidator;
import ai.neargo.shop.food.config.ConditionalOnFoodWebEnabled;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration;
import ai.neargo.shop.food.dto.BusinessReturnApprovalContracts.ApprovalVO;
import ai.neargo.shop.food.dto.BusinessReturnApprovalContracts.ApproveReq;
import ai.neargo.shop.food.dto.BusinessReturnApprovalContracts.ReplacementReq;
import ai.neargo.shop.food.service.BusinessReturnApprovalService;
import ai.neargo.shop.spi.user.StoreDirectoryPort;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("api")
@ConditionalOnFoodWebEnabled
@Conditional(FoodWebAutoConfiguration.PolicyEnabled.class)
public class BizBusinessReturnApprovalController {
    private final BusinessReturnApprovalService service;
    private final FoodBizStoreScope scope;

    public BizBusinessReturnApprovalController(
            BusinessReturnApprovalService service,
            StoreDirectoryPort stores,
            FoodRequestScopeValidator validator) {
        this.service = service;
        scope = new FoodBizStoreScope(stores, validator);
    }

    @PostMapping("/biz/x/food/business-returns/{returnNo}:approve")
    @PreAuthorize("@perm.canBiz('" + BizPerms.AFTERSALE + "')")
    public ApprovalVO approve(
            @PathVariable String returnNo,
            @RequestBody ApproveReq req,
            @RequestHeader("Idempotency-Key") String key,
            HttpServletRequest http) {
        var c = scope.current(http);
        return service.approve(
                c.merchantNo(),
                c.currentStoreNo(),
                SecurityUtils.currentUserNo(),
                returnNo,
                req,
                key);
    }

    @GetMapping("/biz/x/food/business-returns/{returnNo}/approval")
    @PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')")
    public ApprovalVO get(@PathVariable String returnNo, HttpServletRequest http) {
        var c = scope.current(http);
        return service.get(
                c.merchantNo(), c.currentStoreNo(), SecurityUtils.currentUserNo(), returnNo);
    }

    @PostMapping("/biz/x/food/business-returns/{returnNo}:link-replacement")
    @PreAuthorize("@perm.canBiz('" + BizPerms.AFTERSALE + "')")
    public ApprovalVO link(
            @PathVariable String returnNo,
            @RequestBody ReplacementReq req,
            @RequestHeader("Idempotency-Key") String key,
            HttpServletRequest http) {
        var c = scope.current(http);
        return service.linkReplacement(
                c.merchantNo(),
                c.currentStoreNo(),
                SecurityUtils.currentUserNo(),
                returnNo,
                req,
                key);
    }
}
