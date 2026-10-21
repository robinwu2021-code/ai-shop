package ai.neargo.shop.food.api.biz;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.food.api.FoodBizStoreScope;
import ai.neargo.shop.food.api.FoodRequestScopeValidator;
import ai.neargo.shop.food.config.ConditionalOnFoodWebEnabled;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration.PolicyEnabled;
import ai.neargo.shop.food.dto.CheckResultVO;
import ai.neargo.shop.food.dto.SaveCheckSeatsReq;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Dining reception uses original platform permission and owner/store context. */
@RestController
@Profile("api")
@ConditionalOnFoodWebEnabled
@Conditional(PolicyEnabled.class)
@Validated
public class BizCheckSeatController {
    private final CheckService service;
    private final FoodBizStoreScope scope;
    public BizCheckSeatController(CheckService service,StoreDirectoryPort stores,FoodRequestScopeValidator validator) {
        this.service=service;this.scope=new FoodBizStoreScope(stores,validator);
    }
    @PutMapping("/biz/x/food/checks/{checkNo}/seats")
    @PreAuthorize("@perm.canBiz('"+BizPerms.VERIFY+"')")
    public CheckResultVO saveSeats(@PathVariable("checkNo") @NotBlank @Size(max=32) String check,
            @Valid @RequestBody SaveCheckSeatsReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,HttpServletRequest request) {
        var context=scope.current(request);
        return service.saveSeats(context.merchantNo(),context.currentStoreNo(),check,SecurityUtils.currentUserNo(),req,key);
    }
}
