package ai.neargo.shop.food.api.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizContextFilter;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.dto.UpdateModifierReq;
import ai.neargo.shop.food.dto.ModifierMutationVO;
import org.springframework.web.bind.annotation.PutMapping;
import ai.neargo.shop.food.dto.ModifierConfigVO;
import ai.neargo.shop.food.dto.ListModifiersQuery;

import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.api.FoodRequestScopeValidator;
import ai.neargo.shop.food.config.ConditionalOnFoodWebEnabled;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration.PolicyEnabled;
import ai.neargo.shop.food.dto.CreateModifierResultVO;
import ai.neargo.shop.food.dto.CreateModifierReq;
import ai.neargo.shop.food.service.ModifierService;
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
public class BizModifierController {
    private final ModifierService service;
    private final StoreDirectoryPort stores;
    private final FoodRequestScopeValidator scope;

    public BizModifierController(ModifierService service, StoreDirectoryPort stores,
            FoodRequestScopeValidator scope) {
        this.service = service;
        this.stores = stores;
        this.scope = scope;
    }

    /** A store-authorized entry creates an entity-shared draft, never a customer-priced modifier. */
    @PostMapping("/biz/x/food/stores/{storeNo}/modifiers")
    @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")
    public CreateModifierResultVO createModifier(@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo,
            @Valid @RequestBody CreateModifierReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key, HttpServletRequest request) {
        queryFields(request, Set.of());
        var context = requireStore(storeNo, request);
        return service.createModifier(context.merchantNo(), storeNo, SecurityUtils.currentUserNo(), req, key);
    }

    /** Uses the platform current store; callers cannot move ownership through the request body. */
    @PutMapping("/biz/x/food/modifiers/{modifierNo}")
    @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")
    public ModifierMutationVO updateModifier(@PathVariable("modifierNo") @NotBlank @Size(max=32) String modifierNo,
            @Valid @RequestBody UpdateModifierReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key, HttpServletRequest request) {
        var context = BizContext.current();
        if (context.currentStoreNo() == null) throw BizException.of(ErrorCode.BAD_REQUEST);
        requireStore(context.currentStoreNo(), request);
        queryFields(request, Set.of());
        return service.updateModifier(context.merchantNo(), context.currentStoreNo(), modifierNo,
                SecurityUtils.currentUserNo(), req, key);
    }

    /** Library visibility is entity-scoped; store sale availability is a later publication concern. */
    @GetMapping("/biz/x/food/stores/{storeNo}/modifiers")
    @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")
    public PageData<ModifierConfigVO> listModifiers(@PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo,
            @Valid @ModelAttribute ListModifiersQuery query, HttpServletRequest request) {
        queryFields(request, Set.of("page", "size"));
        var context = requireStore(storeNo, request);
        return service.listModifiers(context.merchantNo(), storeNo, query);
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
