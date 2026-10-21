package ai.neargo.shop.food.api.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizContextFilter;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.api.FoodRequestScopeValidator;
import ai.neargo.shop.food.config.ConditionalOnFoodWebEnabled;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration.PolicyEnabled;
import ai.neargo.shop.food.dto.CreateMenuChannelBindingReq;
import ai.neargo.shop.food.dto.UpdateMenuChannelBindingReq;
import ai.neargo.shop.food.dto.MenuChannelBindingVO;
import ai.neargo.shop.food.service.MenuChannelBindingService;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

/** Channel references stay within the trusted current store and original goods management permission. */
@RestController
@Profile("api")
@ConditionalOnFoodWebEnabled
@Conditional(PolicyEnabled.class)
@Validated
public class BizMenuChannelBindingController {
    private final MenuChannelBindingService service;
    private final StoreDirectoryPort stores;
    private final FoodRequestScopeValidator scope;

    public BizMenuChannelBindingController(MenuChannelBindingService service,StoreDirectoryPort stores,
            FoodRequestScopeValidator scope) {
        this.service = service; this.stores = stores; this.scope = scope;
    }

    @PostMapping("/biz/x/food/stores/{storeNo}/menu-channel-bindings")
    @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")
    public MenuChannelBindingVO createBinding(@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo,
            @Valid @RequestBody CreateMenuChannelBindingReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,HttpServletRequest request) {
        queryFields(request,Set.of()); var c=requireStore(storeNo,request);
        return service.createBinding(c.merchantNo(),storeNo,SecurityUtils.currentUserNo(),req,key);
    }
    @GetMapping("/biz/x/food/stores/{storeNo}/menu-channel-bindings")
    @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")
    public PageData<MenuChannelBindingVO> listBindings(
            @PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo,
            @RequestParam(value="page",required=false) Integer page,
            @RequestParam(value="size",required=false) Integer size,
            @RequestParam(value="provider",required=false) String provider,
            @RequestParam(value="menuNo",required=false) String menuNo,HttpServletRequest request) {
        queryFields(request,Set.of("page","size","provider","menuNo")); var c=requireStore(storeNo,request);
        return service.listBindings(c.merchantNo(),storeNo,page,size,provider,menuNo);
    }
    @GetMapping("/biz/x/food/menu-channel-bindings/{bindingNo}")
    @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")
    public MenuChannelBindingVO getBinding(@PathVariable("bindingNo") @NotBlank @Size(max=32) String number,
            HttpServletRequest request) {
        queryFields(request,Set.of()); var c=BizContext.current();
        if(c.currentStoreNo()==null) throw BizException.of(ErrorCode.BAD_REQUEST);
        requireStore(c.currentStoreNo(),request);
        return service.getBinding(c.merchantNo(),c.currentStoreNo(),number);
    }
    @PutMapping("/biz/x/food/menu-channel-bindings/{bindingNo}")
    @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")
    public MenuChannelBindingVO updateBinding(@PathVariable("bindingNo") @NotBlank @Size(max=32) String number,
            @Valid @RequestBody UpdateMenuChannelBindingReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,HttpServletRequest request) {
        queryFields(request,Set.of()); var c=BizContext.current();
        if(c.currentStoreNo()==null) throw BizException.of(ErrorCode.BAD_REQUEST);
        requireStore(c.currentStoreNo(),request);
        return service.updateBinding(c.merchantNo(),c.currentStoreNo(),number,SecurityUtils.currentUserNo(),req,key);
    }

    private static void queryFields(HttpServletRequest request, Set<String> allowed) {
        if (!allowed.containsAll(request.getParameterMap().keySet())
                || request.getParameterMap().values().stream().anyMatch(values -> values.length != 1)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }

    private BizContext requireStore(String storeNo, HttpServletRequest request) {
        var context = BizContext.current();
        scope.requireStoreTarget(context.merchantNo(), context.currentStoreNo(), context.merchantNo(), storeNo,
                request.getHeader(BizContextFilter.STORE_HEADER), context.storeNos());
        var card = stores.cards(List.of(storeNo)).get(storeNo);
        if (card == null) throw BizException.of(ErrorCode.NOT_FOUND);
        scope.requireStoreTarget(context.merchantNo(), context.currentStoreNo(), card.entityNo(), card.storeNo(),
                request.getHeader(BizContextFilter.STORE_HEADER), context.storeNos());
        return context;
    }
}
