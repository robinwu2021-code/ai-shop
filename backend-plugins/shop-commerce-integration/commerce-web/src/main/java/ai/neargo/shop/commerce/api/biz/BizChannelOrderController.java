package ai.neargo.shop.commerce.api.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.commerce.api.CommerceRequestScope;
import ai.neargo.shop.commerce.config.ConditionalOnCommerceWebEnabled;
import ai.neargo.shop.commerce.dto.ChannelOrderContracts;
import ai.neargo.shop.commerce.dto.OperationVO;
import ai.neargo.shop.commerce.service.ChannelOrderService;
import ai.neargo.shop.common.PageData;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.context.annotation.Profile;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Set;

/**
 * Original operator scope applies to every order/revision query; raw external recipient data is not
 * exposed.
 */
@RestController
@Profile("api")
@ConditionalOnCommerceWebEnabled
@Validated
@PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')")
public class BizChannelOrderController {
    private final ChannelOrderService service;
    private final CommerceRequestScope scope;

    public BizChannelOrderController(ChannelOrderService service, CommerceRequestScope scope) {
        this.service = service;
        this.scope = scope;
    }

    @GetMapping("/biz/x/commerce/stores/{storeNo}/orders")
    public PageData<ChannelOrderContracts.Order> list(
            @PathVariable @NotBlank @Size(max = 32) String storeNo,
            @RequestParam(required = false) String connectionNo,
            @RequestParam(required = false) String externalOrderRef,
            @RequestParam(required = false) String sourceOwner,
            @RequestParam(required = false) String importStatus,
            @RequestParam(required = false) String platformStatus,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        CommerceRequestScope.query(
                request,
                Set.of(
                        "connectionNo",
                        "externalOrderRef",
                        "sourceOwner",
                        "importStatus",
                        "platformStatus",
                        "from",
                        "to",
                        "page",
                        "size"));
        return service.listOrders(
                scope.store(storeNo, request),
                storeNo,
                new ChannelOrderContracts.Query(
                        connectionNo,
                        externalOrderRef,
                        sourceOwner,
                        importStatus,
                        platformStatus,
                        from,
                        to,
                        page,
                        size));
    }

    @GetMapping("/biz/x/commerce/orders/{channelOrderNo}")
    public ChannelOrderContracts.Detail get(
            @PathVariable @NotBlank @Size(max = 32) String channelOrderNo,
            HttpServletRequest request) {
        CommerceRequestScope.query(request, Set.of());
        String store = BizContext.requireStoreNo();
        return service.getOrder(scope.store(store, request), store, channelOrderNo);
    }

    @GetMapping("/biz/x/commerce/orders/{channelOrderNo}/revisions")
    public PageData<ChannelOrderContracts.Revision> revisions(
            @PathVariable @NotBlank @Size(max = 32) String channelOrderNo,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        CommerceRequestScope.query(request, Set.of("page", "size"));
        String store = BizContext.requireStoreNo();
        return service.listRevisions(
                scope.store(store, request), store, channelOrderNo, page, size);
    }

    /** Historical line projections retain the selected original revision. */
    @GetMapping("/biz/x/commerce/orders/{channelOrderNo}/lines")
    public PageData<ChannelOrderContracts.Line> lines(
            @PathVariable @NotBlank @Size(max = 32) String channelOrderNo,
            @RequestParam(required = false) String orderRevisionNo,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        CommerceRequestScope.query(request, Set.of("orderRevisionNo", "page", "size"));
        String store = BizContext.requireStoreNo();
        return service.listLines(
                scope.store(store, request), store, channelOrderNo, orderRevisionNo, page, size);
    }

    /** Only queues an authoritative provider read. */
    @PostMapping("/biz/x/commerce/orders/{channelOrderNo}:refresh")
    public OperationVO refresh(
            @PathVariable @NotBlank @Size(max = 32) String channelOrderNo,
            @Valid @RequestBody ChannelOrderContracts.Refresh body,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest request) {
        CommerceRequestScope.query(request, Set.of());
        String store = BizContext.requireStoreNo();
        return service.refreshOrder(
                scope.store(store, request),
                store,
                channelOrderNo,
                SecurityUtils.currentUserNo(),
                body,
                key);
    }

    /** Only wakes original-key recovery; uncertain acceptance is not resent. */
    @PostMapping("/biz/x/commerce/orders/{channelOrderNo}:recover-import")
    public OperationVO recoverImport(
            @PathVariable @NotBlank @Size(max = 32) String channelOrderNo,
            @Valid @RequestBody ChannelOrderContracts.Recover body,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest request) {
        CommerceRequestScope.query(request, Set.of());
        String store = BizContext.requireStoreNo();
        return service.recoverImport(
                scope.store(store, request),
                store,
                channelOrderNo,
                SecurityUtils.currentUserNo(),
                body,
                key);
    }

    @PostMapping("/biz/x/commerce/orders/{channelOrderNo}:accept")
    public OperationVO accept(
            @PathVariable @NotBlank @Size(max = 32) String channelOrderNo,
            @Valid @RequestBody ChannelOrderContracts.Accept body,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest request) {
        CommerceRequestScope.query(request, Set.of());
        String store = BizContext.requireStoreNo();
        return service.accept(
                scope.store(store, request),
                store,
                channelOrderNo,
                SecurityUtils.currentUserNo(),
                body,
                key);
    }

    @PostMapping("/biz/x/commerce/orders/{channelOrderNo}:reject")
    public OperationVO reject(
            @PathVariable @NotBlank @Size(max = 32) String channelOrderNo,
            @Valid @RequestBody ChannelOrderContracts.Reject body,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest request) {
        CommerceRequestScope.query(request, Set.of());
        String store = BizContext.requireStoreNo();
        return service.reject(
                scope.store(store, request),
                store,
                channelOrderNo,
                SecurityUtils.currentUserNo(),
                body,
                key);
    }
}
