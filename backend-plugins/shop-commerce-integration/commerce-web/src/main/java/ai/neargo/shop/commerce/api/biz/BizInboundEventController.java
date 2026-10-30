package ai.neargo.shop.commerce.api.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.commerce.api.CommerceRequestScope;
import ai.neargo.shop.commerce.config.ConditionalOnCommerceWebEnabled;
import ai.neargo.shop.commerce.dto.InboundContracts;
import ai.neargo.shop.commerce.service.ChannelInboundService;
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
 * Queries disclose only safe receipt metadata; recovery cannot override a signature conflict or
 * original business outcome.
 */
@RestController
@Profile("api")
@ConditionalOnCommerceWebEnabled
@Validated
@PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')")
public class BizInboundEventController {
    private final ChannelInboundService service;
    private final CommerceRequestScope scope;

    public BizInboundEventController(ChannelInboundService service, CommerceRequestScope scope) {
        this.service = service;
        this.scope = scope;
    }

    @GetMapping("/biz/x/commerce/stores/{storeNo}/inbound-events")
    public PageData<InboundContracts.Event> list(
            @PathVariable @NotBlank @Size(max = 32) String storeNo,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String eventType,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        CommerceRequestScope.query(request, Set.of("status", "eventType", "page", "size"));
        return service.listEvents(
                scope.store(storeNo, request),
                storeNo,
                new InboundContracts.Query(status, eventType, page, size));
    }

    @GetMapping("/biz/x/commerce/inbound-events/{eventNo}/receipts")
    public PageData<InboundContracts.Receipt> receipts(
            @PathVariable @NotBlank @Size(max = 32) String eventNo,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        CommerceRequestScope.query(request, Set.of("page", "size"));
        String store = BizContext.requireStoreNo();
        return service.listReceipts(scope.store(store, request), store, eventNo, page, size);
    }

    @PostMapping("/biz/x/commerce/inbound-events/{eventNo}:recover")
    public InboundContracts.Event recover(
            @PathVariable @NotBlank @Size(max = 32) String eventNo,
            @Valid @RequestBody InboundContracts.Recovery body,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest request) {
        CommerceRequestScope.query(request, Set.of());
        String store = BizContext.requireStoreNo();
        return service.recover(
                scope.store(store, request),
                store,
                eventNo,
                SecurityUtils.currentUserNo(),
                body,
                key);
    }
}
