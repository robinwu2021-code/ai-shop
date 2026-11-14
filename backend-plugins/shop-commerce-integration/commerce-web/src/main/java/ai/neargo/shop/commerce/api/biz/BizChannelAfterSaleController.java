package ai.neargo.shop.commerce.api.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.commerce.api.CommerceRequestScope;
import ai.neargo.shop.commerce.config.ConditionalOnCommerceWebEnabled;
import ai.neargo.shop.commerce.dto.AfterSaleContracts;
import ai.neargo.shop.commerce.dto.OperationVO;
import ai.neargo.shop.commerce.service.ChannelAfterSaleService;
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

import java.time.Instant;
import java.util.Set;

/**
 * COM052-054 consume source decision references only under the original authenticated
 * merchant/store scope.
 */
@RestController
@Profile("api")
@ConditionalOnCommerceWebEnabled
@Validated
@PreAuthorize("@perm.canBiz('" + BizPerms.AFTERSALE + "')")
public class BizChannelAfterSaleController {
    private final ChannelAfterSaleService service;
    private final CommerceRequestScope scope;

    public BizChannelAfterSaleController(
            ChannelAfterSaleService service, CommerceRequestScope scope) {
        this.service = service;
        this.scope = scope;
    }

    @GetMapping("/biz/x/commerce/stores/{storeNo}/after-sales")
    public PageData<AfterSaleContracts.Link> list(
            @PathVariable @NotBlank @Size(max = 32) String storeNo,
            @RequestParam(required = false) String connectionNo,
            @RequestParam(required = false) String channelOrderNo,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        CommerceRequestScope.query(
                request,
                Set.of(
                        "connectionNo",
                        "channelOrderNo",
                        "type",
                        "status",
                        "from",
                        "to",
                        "page",
                        "size"));
        return service.listCases(
                scope.store(storeNo, request),
                storeNo,
                new AfterSaleContracts.Query(
                        connectionNo, channelOrderNo, type, status, from, to, page, size));
    }

    @GetMapping("/biz/x/commerce/after-sales/{caseNo}")
    public AfterSaleContracts.Link get(
            @PathVariable @NotBlank @Size(max = 32) String caseNo, HttpServletRequest request) {
        CommerceRequestScope.query(request, Set.of());
        String store = BizContext.requireStoreNo();
        return service.getCase(scope.store(store, request), store, caseNo);
    }

    @PostMapping("/biz/x/commerce/after-sales/{caseNo}:apply-decision")
    public OperationVO apply(
            @PathVariable @NotBlank @Size(max = 32) String caseNo,
            @Valid @RequestBody AfterSaleContracts.ApplyDecision body,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest request) {
        CommerceRequestScope.query(request, Set.of());
        String store = BizContext.requireStoreNo();
        return service.applyDecision(
                scope.store(store, request),
                store,
                SecurityUtils.currentUserNo(),
                caseNo,
                body,
                key);
    }
}
