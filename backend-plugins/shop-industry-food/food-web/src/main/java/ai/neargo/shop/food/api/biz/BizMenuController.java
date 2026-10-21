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
import ai.neargo.shop.food.dto.CreateMenuReq;
import ai.neargo.shop.food.dto.ListMenusQuery;
import ai.neargo.shop.food.dto.MenuResultVO;
import ai.neargo.shop.food.dto.CreateMenuResultVO;
import ai.neargo.shop.food.dto.MenuConfigResultVO;
import ai.neargo.shop.food.dto.MenuVO;
import ai.neargo.shop.food.dto.ReplaceMenuScheduleReq;
import ai.neargo.shop.food.dto.MenuScheduleResultVO;
import ai.neargo.shop.food.dto.UpdateMenuReq;
import ai.neargo.shop.food.service.MenuService;
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
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Original goods permission and store directory protect actual menu bindings. */
@RestController
@Profile("api")
@ConditionalOnFoodWebEnabled
@Conditional(PolicyEnabled.class)
@Validated
public class BizMenuController {
    private final MenuService service;
    private final StoreDirectoryPort stores;
    private final FoodRequestScopeValidator scope;

    public BizMenuController(MenuService service, StoreDirectoryPort stores, FoodRequestScopeValidator scope) {
        this.service = service; this.stores = stores; this.scope = scope;
    }

    /** Creates a named menu without accepting ownership or publication facts. */
    @PostMapping("/biz/x/food/stores/{storeNo}/menus")
    @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")
    public CreateMenuResultVO createMenu(@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo,
            @Valid @RequestBody CreateMenuReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key, HttpServletRequest request) {
        queryFields(request, Set.of());
        var context = requireStore(storeNo, request);
        return service.createMenu(context.merchantNo(), storeNo, SecurityUtils.currentUserNo(), req, key);
    }

    /** Updates a working header; the platform resolves entity, current store, and actor. */
    @PutMapping("/biz/x/food/menus/{menuNo}")
    @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")
    public MenuResultVO updateMenu(@PathVariable("menuNo") @NotBlank @Size(max=32) String menuNo,
            @Valid @RequestBody UpdateMenuReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key, HttpServletRequest request) {
        queryFields(request, Set.of());
        var context = BizContext.current();
        if (context.currentStoreNo() == null) throw BizException.of(ErrorCode.BAD_REQUEST);
        requireStore(context.currentStoreNo(), request);
        return service.updateMenu(context.merchantNo(), context.currentStoreNo(), menuNo,
                SecurityUtils.currentUserNo(), req, key);
    }

    /** Only the explicit schedule section is supported until composition contracts are implemented. */
    @PutMapping("/biz/x/food/menus/{menuNo}/definition")
    @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")
    public MenuScheduleResultVO replaceSchedule(@PathVariable("menuNo") @NotBlank @Size(max=32) String number,
            @Valid @RequestBody ReplaceMenuScheduleReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key, HttpServletRequest request) {
        queryFields(request, Set.of());
        var context = BizContext.current();
        if (context.currentStoreNo() == null) throw BizException.of(ErrorCode.BAD_REQUEST);
        requireStore(context.currentStoreNo(), request);
        return service.replaceSchedule(context.merchantNo(), context.currentStoreNo(), number,
                SecurityUtils.currentUserNo(), req, key);
    }

    /** Reads the bound working head without inferring an effective published menu. */
    @GetMapping("/biz/x/food/stores/{storeNo}/menus/{configNo}")
    @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")
    public MenuConfigResultVO getMenu(@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo,
            @PathVariable("configNo") @NotBlank @Size(max=32) String number, HttpServletRequest request) {
        queryFields(request, Set.of());
        var context = requireStore(storeNo, request);
        return service.getMenu(context.merchantNo(), storeNo, number);
    }

    /** Filters and total operate on the current store draft library. */
    @GetMapping("/biz/x/food/stores/{storeNo}/menus")
    @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")
    public PageData<MenuVO> listMenus(@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo,
            @Valid @ModelAttribute ListMenusQuery query, HttpServletRequest request) {
        queryFields(request, Set.of("page", "size", "status", "keyword"));
        var context = requireStore(storeNo, request);
        return service.listMenus(context.merchantNo(), storeNo, query);
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
