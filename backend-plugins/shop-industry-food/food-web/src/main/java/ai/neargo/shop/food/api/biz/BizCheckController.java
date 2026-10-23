package ai.neargo.shop.food.api.biz;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.api.FoodBizStoreScope;
import ai.neargo.shop.food.api.FoodRequestScopeValidator;
import ai.neargo.shop.food.config.ConditionalOnFoodWebEnabled;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration.PolicyEnabled;
import ai.neargo.shop.food.dto.CheckDetailVO;
import ai.neargo.shop.food.dto.CheckResultVO;
import ai.neargo.shop.food.dto.CheckVO;
import ai.neargo.shop.food.dto.ClearCheckReq;
import ai.neargo.shop.food.dto.CombineTablesReq;
import ai.neargo.shop.food.dto.ListChecksQuery;
import ai.neargo.shop.food.dto.OpenCheckReq;
import ai.neargo.shop.food.dto.OperationResultVO;
import ai.neargo.shop.food.dto.OperationVO;
import ai.neargo.shop.food.dto.TransferCheckReq;
import ai.neargo.shop.food.service.CheckService;
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
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Dining reception uses original platform permission and owner/store context. */
@RestController
@Profile("api")
@ConditionalOnFoodWebEnabled
@Conditional(PolicyEnabled.class)
@Validated
public class BizCheckController {
    private final CheckService service;
    private final FoodBizStoreScope scope;
    public BizCheckController(CheckService service,StoreDirectoryPort stores,FoodRequestScopeValidator validator) {
        this.service=service;this.scope=new FoodBizStoreScope(stores,validator);
    }
    @GetMapping("/biz/x/food/stores/{storeNo}/checks")
    @PreAuthorize("@perm.canBiz('"+BizPerms.ORDER_VIEW+"')")
    public PageData<CheckVO> listChecks(@PathVariable("storeNo") @NotBlank @Size(max=32) String store,
            @Valid @ModelAttribute ListChecksQuery query,HttpServletRequest request) {
        var context=scope.path(store,request);
        return service.listChecks(context.merchantNo(),store,query);
    }
    @GetMapping("/biz/x/food/checks/{checkNo}")
    @PreAuthorize("@perm.canBiz('"+BizPerms.ORDER_VIEW+"')")
    public CheckDetailVO getCheck(@PathVariable("checkNo") @NotBlank @Size(max=32) String check,HttpServletRequest request) {
        var context=scope.current(request);return service.getCheck(context.merchantNo(),context.currentStoreNo(),check);
    }
    @PostMapping("/biz/x/food/stores/{storeNo}/checks")
    @PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')")
    public CheckResultVO openCheck(@PathVariable("storeNo") @NotBlank @Size(max=32) String store,
            @Valid @RequestBody OpenCheckReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,HttpServletRequest request) {
        var context=scope.path(store,request);
        return service.openCheck(context.merchantNo(),store,SecurityUtils.currentUserNo(),req,key);
    }
    @PostMapping("/biz/x/food/checks/{checkNo}:clear")
    @PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')")
    public OperationVO clearCheck(@PathVariable("checkNo") @NotBlank @Size(max=32) String check,
            @Valid @RequestBody ClearCheckReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,HttpServletRequest request) {
        var context=scope.current(request);
        return service.clearCheck(context.merchantNo(),context.currentStoreNo(),check,SecurityUtils.currentUserNo(),req,key);
    }
    @PostMapping("/biz/x/food/checks/{checkNo}:transfer")
    @PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')")
    public OperationResultVO transferCheck(@PathVariable("checkNo") @NotBlank @Size(max=32) String check,
            @Valid @RequestBody TransferCheckReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,HttpServletRequest request) {
        var context=scope.current(request);
        return service.transferCheck(context.merchantNo(),context.currentStoreNo(),check,SecurityUtils.currentUserNo(),req,key);
    }
    @PostMapping("/biz/x/food/checks/{checkNo}:combine-tables")
    @PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')")
    public OperationResultVO combineTables(@PathVariable("checkNo") @NotBlank @Size(max=32) String check,
            @Valid @RequestBody CombineTablesReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,HttpServletRequest request) {
        var context=scope.current(request);
        return service.combineTables(context.merchantNo(),context.currentStoreNo(),check,SecurityUtils.currentUserNo(),req,key);
    }
}
