package ai.neargo.shop.food.api.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizContextFilter;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.api.FoodRequestScopeValidator;
import ai.neargo.shop.food.config.ConditionalOnFoodWebEnabled;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration.PolicyEnabled;
import ai.neargo.shop.food.dto.OperationResultVO;
import ai.neargo.shop.food.dto.RevokeTableTokenReq;
import ai.neargo.shop.food.dto.RotateTableTokenReq;
import ai.neargo.shop.food.dto.TableTokenVO;
import ai.neargo.shop.food.service.DiningSessionService;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Table configuration shares the platform store scope, trusted actor and original permission. */
@RestController
@Profile("api")
@ConditionalOnFoodWebEnabled
@Conditional(PolicyEnabled.class)
@Validated
public class BizTableTokenController {
    private final DiningSessionService service;
    private final StoreDirectoryPort stores;
    private final FoodRequestScopeValidator scope;
    public BizTableTokenController(DiningSessionService service, StoreDirectoryPort stores, FoodRequestScopeValidator scope) {
        this.service=service;
        this.stores=stores;
        this.scope=scope;
    }

    @PostMapping("/biz/x/food/tables/{tableNo}:rotate-token")
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE + "')")
    public TableTokenVO rotateTableToken(@PathVariable("tableNo") @NotBlank @Size(max=32) String tableNo,
            @Valid @RequestBody RotateTableTokenReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key, HttpServletRequest request) {
        var context=requireStore(BizContext.current().currentStoreNo(),request);
        return service.rotateTableToken(context.merchantNo(),context.currentStoreNo(),tableNo,
                SecurityUtils.currentUserNo(),req,key);
    }
    @PostMapping("/biz/x/food/tables/{tableNo}:revoke-token")
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE + "')")
    public OperationResultVO revokeTableToken(@PathVariable("tableNo") @NotBlank @Size(max=32) String tableNo,
            @Valid @RequestBody RevokeTableTokenReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key, HttpServletRequest request) {
        var context=requireStore(BizContext.current().currentStoreNo(),request);
        return service.revokeTableToken(context.merchantNo(),context.currentStoreNo(),tableNo,
                SecurityUtils.currentUserNo(),req,key);
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
