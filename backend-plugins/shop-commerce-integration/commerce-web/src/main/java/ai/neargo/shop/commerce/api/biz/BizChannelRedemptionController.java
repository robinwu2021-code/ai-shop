package ai.neargo.shop.commerce.api.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.commerce.api.CommerceRequestScope;
import ai.neargo.shop.commerce.config.ConditionalOnCommerceWebEnabled;
import ai.neargo.shop.commerce.dto.OperationVO;
import ai.neargo.shop.commerce.dto.RedemptionContracts;
import ai.neargo.shop.commerce.service.ChannelRedemptionService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/**
 * Public external certificate operations accept only controlled handles and original business
 * consumption references.
 */
@RestController
@Profile("api")
@ConditionalOnCommerceWebEnabled
@Validated
@ConditionalOnProperty(name = "shop.commerce.redemption.enabled", havingValue = "true")
@PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')")
public class BizChannelRedemptionController {
    private final ChannelRedemptionService service;
    private final CommerceRequestScope scope;

    public BizChannelRedemptionController(
            ChannelRedemptionService service, CommerceRequestScope scope) {
        this.service = service;
        this.scope = scope;
    }

    @PostMapping("/biz/x/commerce/stores/{storeNo}/redemption-previews")
    public RedemptionContracts.Previewed preview(
            @PathVariable @NotBlank @Size(max = 32) String storeNo,
            @Valid @RequestBody RedemptionContracts.Preview body,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest request) {
        CommerceRequestScope.query(request, Set.of());
        return service.preview(
                scope.store(storeNo, request), storeNo, SecurityUtils.currentUserNo(), body, key);
    }

    @PostMapping("/biz/x/commerce/stores/{storeNo}/redemptions")
    public OperationVO redeem(
            @PathVariable @NotBlank @Size(max = 32) String storeNo,
            @Valid @RequestBody RedemptionContracts.Redeem body,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest request) {
        CommerceRequestScope.query(request, Set.of());
        return service.redeem(
                scope.store(storeNo, request), storeNo, SecurityUtils.currentUserNo(), body, key);
    }

    @GetMapping("/biz/x/commerce/redemptions/{redemptionNo}")
    public RedemptionContracts.Redemption get(
            @PathVariable @NotBlank @Size(max = 32) String redemptionNo,
            HttpServletRequest request) {
        CommerceRequestScope.query(request, Set.of());
        String store = BizContext.requireStoreNo();
        return service.getRedemption(scope.store(store, request), store, redemptionNo);
    }

    @PostMapping("/biz/x/commerce/redemptions/{redemptionNo}:reverse")
    public OperationVO reverse(
            @PathVariable @NotBlank @Size(max = 32) String redemptionNo,
            @Valid @RequestBody RedemptionContracts.Reverse body,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest request) {
        CommerceRequestScope.query(request, Set.of());
        String store = BizContext.requireStoreNo();
        return service.reverse(
                scope.store(store, request),
                store,
                SecurityUtils.currentUserNo(),
                redemptionNo,
                body,
                key);
    }
}
