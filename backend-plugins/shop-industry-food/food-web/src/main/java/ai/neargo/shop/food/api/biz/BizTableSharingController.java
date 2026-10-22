package ai.neargo.shop.food.api.biz;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.food.api.FoodBizStoreScope;
import ai.neargo.shop.food.api.FoodRequestScopeValidator;
import ai.neargo.shop.food.config.ConditionalOnFoodWebEnabled;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration.PolicyEnabled;
import ai.neargo.shop.food.dto.CheckResultVO;
import ai.neargo.shop.food.dto.OpenSharedCheckReq;
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
public class BizTableSharingController {
    private final CheckService service;
    private final FoodBizStoreScope scope;
    public BizTableSharingController(CheckService service,StoreDirectoryPort stores,FoodRequestScopeValidator validator) {
        this.service=service;this.scope=new FoodBizStoreScope(stores,validator);
    }
    @PostMapping("/biz/x/food/tables/{tableNo}:share")
    @PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')")
    public CheckResultVO openSharedCheck(@PathVariable("tableNo") @NotBlank @Size(max=32) String table,
            @Valid @RequestBody OpenSharedCheckReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,HttpServletRequest request) {
        var context=scope.current(request);
        return service.openSharedCheck(context.merchantNo(),context.currentStoreNo(),table,SecurityUtils.currentUserNo(),req,key);
    }
}
