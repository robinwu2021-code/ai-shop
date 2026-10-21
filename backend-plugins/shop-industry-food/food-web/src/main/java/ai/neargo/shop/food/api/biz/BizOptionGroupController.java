package ai.neargo.shop.food.api.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizContextFilter;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.dto.OptionGroupConfigVO;
import ai.neargo.shop.food.dto.OptionGroupResultVO;
import ai.neargo.shop.food.dto.ListOptionGroupsQuery;

import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.api.FoodRequestScopeValidator;
import ai.neargo.shop.food.config.ConditionalOnFoodWebEnabled;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration.PolicyEnabled;
import ai.neargo.shop.food.dto.CreateOptionGroupResultVO;
import ai.neargo.shop.food.dto.CreateOptionGroupReq;
import ai.neargo.shop.food.service.OptionGroupService;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Original goods permissions protect the entity option library; the caller cannot provide owner or actor. */
@RestController
@Profile("api")
@ConditionalOnFoodWebEnabled
@Conditional(PolicyEnabled.class)
@Validated
public class BizOptionGroupController {
    private final OptionGroupService service;
    private final StoreDirectoryPort stores;
    private final FoodRequestScopeValidator scope;

    public BizOptionGroupController(OptionGroupService service, StoreDirectoryPort stores,
            FoodRequestScopeValidator scope) {
        this.service = service;
        this.stores = stores;
        this.scope = scope;
    }

    /** Creates a shared draft through an authorized store without accepting a client-supplied owner or number. */
    @PostMapping("/biz/x/food/stores/{storeNo}/option-groups")
    @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")
    public CreateOptionGroupResultVO createOptionGroup(
            @PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo,
            @Valid @RequestBody CreateOptionGroupReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key, HttpServletRequest request) {
        var context = requireStore(storeNo, request);
        return service.createOptionGroup(context.merchantNo(), storeNo, SecurityUtils.currentUserNo(), req, key);
    }

    /** The underlying library is entity-shared; store authorization does not change ownership. */
    @GetMapping("/biz/x/food/stores/{storeNo}/option-groups")
    @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")
    public PageData<OptionGroupConfigVO> listOptionGroups(
            @PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo,
            @Valid @ModelAttribute ListOptionGroupsQuery query, HttpServletRequest request) {
        queryFields(request, Set.of("page", "size"));
        var context = requireStore(storeNo, request);
        return service.listOptionGroups(context.merchantNo(), storeNo, query);
    }

    /** Returns the real working definition; publication and sale validation use separate APIs. */
    @GetMapping("/biz/x/food/stores/{storeNo}/option-groups/{configNo}")
    @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")
    public OptionGroupResultVO getOptionGroup(@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo,
            @PathVariable("configNo") @NotBlank @Size(max=32) String configNo, HttpServletRequest request) {
        queryFields(request, Set.of());
        var context = requireStore(storeNo, request);
        return service.getOptionGroup(context.merchantNo(), storeNo, configNo);
    }

    private static void queryFields(HttpServletRequest request, Set<String> allowed) {
        if (!allowed.containsAll(request.getParameterMap().keySet())
                || request.getParameterMap().values().stream().anyMatch(values -> values.length != 1)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }

    private BizContext requireStore(String storeNo, HttpServletRequest request) {
        BizContext context = BizContext.current();
        // Check path/header before directory lookup as well as against the directory's actual owner.
        scope.requireStoreTarget(context.merchantNo(), context.currentStoreNo(), context.merchantNo(), storeNo,
                request.getHeader(BizContextFilter.STORE_HEADER), context.storeNos());
        var card = stores.cards(List.of(storeNo)).get(storeNo);
        if (card == null) throw BizException.of(ErrorCode.NOT_FOUND);
        scope.requireStoreTarget(context.merchantNo(), context.currentStoreNo(), card.entityNo(), card.storeNo(),
                request.getHeader(BizContextFilter.STORE_HEADER), context.storeNos());
        return context;
    }
}
