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
import ai.neargo.shop.food.dto.ConfigRevisionVO;
import ai.neargo.shop.food.dto.ListConfigRevisionsQuery;
import ai.neargo.shop.food.dto.PublishConfigReq;
import ai.neargo.shop.food.dto.PublishConfigResultVO;
import ai.neargo.shop.food.service.ConfigRevisionService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Original goods permissions protect the entity option library; the caller cannot provide owner or actor. */
@RestController
@Profile("api")
@ConditionalOnFoodWebEnabled
@Conditional(PolicyEnabled.class)
@Validated
public class BizConfigRevisionController {
    private final ConfigRevisionService service;
    private final StoreDirectoryPort stores;
    private final FoodRequestScopeValidator scope;

    public BizConfigRevisionController(ConfigRevisionService service, StoreDirectoryPort stores,
            FoodRequestScopeValidator scope) {
        this.service = service;
        this.stores = stores;
        this.scope = scope;
    }

    /** Recipe ownership remains a store fact even though the public path omits the store number. */
    @PostMapping("/biz/x/food/recipes/{recipeNo}:publish")
    @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")
    public PublishConfigResultVO publishRecipe(
            @PathVariable("recipeNo") @NotBlank @Size(max=32) String recipeNo,
            @Valid @RequestBody PublishConfigReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key, HttpServletRequest request) {
        var context = BizContext.current();
        if (context.currentStoreNo() == null) throw BizException.of(ErrorCode.BAD_REQUEST);
        requireStore(context.currentStoreNo(), request);
        queryFields(request, Set.of());
        return service.publishRecipe(context.merchantNo(), context.currentStoreNo(), recipeNo,
                SecurityUtils.currentUserNo(), req, key);
    }

    /** Publishes a complete bundle for the authorized store through the original configuration revision owner. */
    @PostMapping("/biz/x/food/stores/{storeNo}/bundles/{configNo}:publish")
    @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")
    public PublishConfigResultVO publishBundle(
            @PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo,
            @PathVariable("configNo") @NotBlank @Size(max=32) String configNo,
            @Valid @RequestBody PublishConfigReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key, HttpServletRequest request) {
        queryFields(request, Set.of());
        var context = requireStore(storeNo, request);
        return service.publishBundle(context.merchantNo(), storeNo, configNo,
                SecurityUtils.currentUserNo(), req, key);
    }

    /** Only a store-authorized actor may freeze an entity definition for that store. */
    @PostMapping("/biz/x/food/stores/{storeNo}/option-groups/{configNo}:publish")
    @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")
    public PublishConfigResultVO publishOptionGroup(
            @PathVariable("storeNo") @NotBlank @Size(max=32) String storeNo,
            @PathVariable("configNo") @NotBlank @Size(max=32) String configNo,
            @Valid @RequestBody PublishConfigReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key, HttpServletRequest request) {
        queryFields(request, Set.of());
        var context = requireStore(storeNo, request);
        return service.publishOptionGroup(context.merchantNo(), storeNo, configNo,
                SecurityUtils.currentUserNo(), req, key);
    }

    /** The current store comes from the original platform context, never from publication contents. */
    @PostMapping("/biz/x/food/modifiers/{modifierNo}:publish")
    @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")
    public PublishConfigResultVO publishModifier(
            @PathVariable("modifierNo") @NotBlank @Size(max=32) String modifierNo,
            @Valid @RequestBody PublishConfigReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key, HttpServletRequest request) {
        var context = BizContext.current();
        if (context.currentStoreNo() == null) throw BizException.of(ErrorCode.BAD_REQUEST);
        requireStore(context.currentStoreNo(), request); queryFields(request, Set.of());
        return service.publishModifier(context.merchantNo(), context.currentStoreNo(), modifierNo,
                SecurityUtils.currentUserNo(), req, key);
    }

    /** Frozen scope is filtered before count/pagination; no client-provided owner is accepted. */
    @GetMapping("/biz/x/food/config-revisions")
    @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")
    public PageData<ConfigRevisionVO> listRevisions(@Valid @ModelAttribute ListConfigRevisionsQuery query,
            HttpServletRequest request) {
        var context = BizContext.current();
        if (context.currentStoreNo() == null) throw BizException.of(ErrorCode.BAD_REQUEST);
        requireStore(context.currentStoreNo(), request);
        queryFields(request, Set.of("configKind", "configNo", "page", "size"));
        return service.listRevisions(context.merchantNo(), context.currentStoreNo(), query);
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
