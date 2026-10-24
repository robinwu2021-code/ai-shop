package ai.neargo.shop.food.api.biz;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.food.api.FoodBizStoreScope;
import ai.neargo.shop.food.api.FoodRequestScopeValidator;
import ai.neargo.shop.food.config.ConditionalOnFoodWebEnabled;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration.PolicyEnabled;
import ai.neargo.shop.spi.printing.PrintPort;
import ai.neargo.shop.spi.printing.PrintPort.Scope;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ai.neargo.shop.spi.printing.PrintPort.Configuration;
import ai.neargo.shop.spi.printing.PrintPort.RouteReq;
import ai.neargo.shop.spi.printing.PrintPort.TemplateReq;

/** Store administrators publish immutable template versions and exact station/device routes. */
@RestController
@Profile("api")
@ConditionalOnFoodWebEnabled
@Conditional(PolicyEnabled.class)
@Validated
public class BizPrintConfigurationController {
    private final PrintPort printing;
    private final FoodBizStoreScope stores;
    public BizPrintConfigurationController(PrintPort printing,StoreDirectoryPort directory,
            FoodRequestScopeValidator validator) {
        this.printing=printing; this.stores=new FoodBizStoreScope(directory,validator);
    }
    @GetMapping("/biz/x/food/print-configuration")
    @PreAuthorize("@perm.canBiz('"+BizPerms.STORE_ADMIN+"')")
    public Configuration configuration(HttpServletRequest request) { return printing.configuration(scope(request)); }
    @PostMapping("/biz/x/food/print-configuration/templates")
    @PreAuthorize("@perm.canBiz('"+BizPerms.STORE_ADMIN+"')")
    public TemplateReq publish(@Valid @RequestBody TemplateReq req,HttpServletRequest request) {
        return printing.publishTemplate(scope(request),req);
    }
    @PostMapping("/biz/x/food/print-configuration/routes")
    @PreAuthorize("@perm.canBiz('"+BizPerms.STORE_ADMIN+"')")
    public RouteReq route(@Valid @RequestBody RouteReq req,HttpServletRequest request) {
        return printing.putRoute(scope(request),req);
    }
    private Scope scope(HttpServletRequest request) {
        var context=stores.current(request);
        return new Scope(context.merchantNo(),context.currentStoreNo(),SecurityUtils.currentUserNo());
    }
}
