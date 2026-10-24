package ai.neargo.shop.food.api.biz;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.api.FoodBizStoreScope;
import ai.neargo.shop.food.api.FoodRequestScopeValidator;
import ai.neargo.shop.food.config.ConditionalOnFoodWebEnabled;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration.PolicyEnabled;
import ai.neargo.shop.food.dto.DiningBatchSummaryVO;
import ai.neargo.shop.food.dto.ListDiningBatchesQuery;
import ai.neargo.shop.food.service.DiningBatchQueryService;
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
import org.springframework.web.bind.annotation.RestController;

/** Staff listing preserves original authenticated entity/store scope and never expands into financial querying. */
@RestController
@Profile("api")
@ConditionalOnFoodWebEnabled
@Conditional(PolicyEnabled.class)
@Validated
public class BizDiningBatchQueryController {
    private final DiningBatchQueryService service;
    private final FoodBizStoreScope scope;
    public BizDiningBatchQueryController(DiningBatchQueryService service,StoreDirectoryPort stores,
            FoodRequestScopeValidator validator) {
        this.service=service; scope=new FoodBizStoreScope(stores,validator);
    }
    @GetMapping("/biz/x/food/stores/{storeNo}/batches")
    @PreAuthorize("@perm.canBiz('"+BizPerms.ORDER_VIEW+"')")
    public PageData<DiningBatchSummaryVO> list(@PathVariable @NotBlank @Size(max=32) String storeNo,
            @Valid @ModelAttribute ListDiningBatchesQuery query,HttpServletRequest request) {
        var context=scope.path(storeNo,request);
        return service.list(context.merchantNo(),storeNo,SecurityUtils.currentUserNo(),query);
    }
}
