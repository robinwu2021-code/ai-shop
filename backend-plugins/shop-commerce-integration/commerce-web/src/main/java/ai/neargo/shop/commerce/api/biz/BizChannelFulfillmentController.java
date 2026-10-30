package ai.neargo.shop.commerce.api.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.commerce.api.CommerceRequestScope;
import ai.neargo.shop.commerce.config.ConditionalOnCommerceWebEnabled;
import ai.neargo.shop.commerce.dto.FulfillmentContracts;
import ai.neargo.shop.commerce.dto.OperationVO;
import ai.neargo.shop.commerce.service.ChannelFulfillmentService;
import ai.neargo.shop.common.PageData;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

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

import java.util.Set;

/**
 * Every fulfillment query/publication uses the original merchant and current-store authorization.
 */
@RestController
@Profile("api")
@ConditionalOnCommerceWebEnabled
@Validated
@PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')")
public class BizChannelFulfillmentController {
    private final ChannelFulfillmentService service;
    private final CommerceRequestScope scope;

    public BizChannelFulfillmentController(
            ChannelFulfillmentService service, CommerceRequestScope scope) {
        this.service = service;
        this.scope = scope;
    }

    /**
     * Lists original fact references and provider publication status without disclosing raw source
     * payloads.
     */
    @GetMapping("/biz/x/commerce/orders/{channelOrderNo}/fulfillments")
    public PageData<FulfillmentContracts.Link> list(
            @PathVariable @NotBlank @Size(max = 32) String channelOrderNo,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        CommerceRequestScope.query(request, Set.of("page", "size"));
        String store = BizContext.requireStoreNo();
        return service.listFulfillments(
                scope.store(store, request), store, channelOrderNo, page, size);
    }

    /**
     * The request names a committed original fulfillment; it cannot self-report shipped quantity or
     * delivery success.
     */
    @PostMapping("/biz/x/commerce/stores/{storeNo}/fulfillment-publications")
    public OperationVO publish(
            @PathVariable @NotBlank @Size(max = 32) String storeNo,
            @Valid @RequestBody FulfillmentContracts.Publish body,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest request) {
        CommerceRequestScope.query(request, Set.of());
        return service.publishFulfillment(
                scope.store(storeNo, request), storeNo, SecurityUtils.currentUserNo(), body, key);
    }
}
