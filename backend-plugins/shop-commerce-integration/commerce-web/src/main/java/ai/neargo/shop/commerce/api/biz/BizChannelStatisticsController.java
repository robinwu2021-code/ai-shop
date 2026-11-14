package ai.neargo.shop.commerce.api.biz;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.commerce.api.CommerceRequestScope;
import ai.neargo.shop.commerce.config.ConditionalOnCommerceWebEnabled;
import ai.neargo.shop.commerce.dto.ChannelStatisticsContracts;
import ai.neargo.shop.commerce.service.ChannelStatisticsService;
import ai.neargo.shop.common.PageData;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/** Technical monitoring stays under the same original owner/store scope as channel management. */
@RestController
@Profile("api")
@ConditionalOnCommerceWebEnabled
@Validated
@PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')")
public class BizChannelStatisticsController {
    private final ChannelStatisticsService service;
    private final CommerceRequestScope scope;

    public BizChannelStatisticsController(
            ChannelStatisticsService service, CommerceRequestScope scope) {
        this.service = service;
        this.scope = scope;
    }

    @GetMapping("/biz/x/commerce/stores/{storeNo}/statistics/sync")
    public ChannelStatisticsContracts.Sync sync(
            @PathVariable @NotBlank @Size(max = 32) String storeNo,
            @Valid @ModelAttribute ChannelStatisticsContracts.Query query,
            HttpServletRequest request) {
        CommerceRequestScope.query(
                request, Set.of("from", "to", "providerNo", "productCode", "type", "connectionNo"));
        return service.syncStatistics(scope.store(storeNo, request), storeNo, query);
    }

    @GetMapping("/biz/x/commerce/stores/{storeNo}/statistics/providers")
    public PageData<ChannelStatisticsContracts.Provider> providers(
            @PathVariable @NotBlank @Size(max = 32) String storeNo,
            @Valid @ModelAttribute ChannelStatisticsContracts.Query query,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        CommerceRequestScope.query(
                request,
                Set.of(
                        "from",
                        "to",
                        "providerNo",
                        "productCode",
                        "type",
                        "connectionNo",
                        "page",
                        "size"));
        return service.providerStatistics(
                scope.store(storeNo, request), storeNo, query, page, size);
    }

    @GetMapping("/biz/x/commerce/stores/{storeNo}/statistics/order-imports")
    public ChannelStatisticsContracts.Imports imports(
            @PathVariable @NotBlank @Size(max = 32) String storeNo,
            @Valid @ModelAttribute ChannelStatisticsContracts.Query query,
            HttpServletRequest request) {
        CommerceRequestScope.query(
                request, Set.of("from", "to", "providerNo", "productCode", "connectionNo"));
        return service.importStatistics(scope.store(storeNo, request), storeNo, query);
    }

    @GetMapping("/biz/x/commerce/stores/{storeNo}/integration-health")
    public ChannelStatisticsContracts.Health health(
            @PathVariable @NotBlank @Size(max = 32) String storeNo,
            @RequestParam(required = false) String connectionNo,
            @RequestParam(required = false) String providerNo,
            HttpServletRequest request) {
        CommerceRequestScope.query(request, Set.of("connectionNo", "providerNo"));
        return service.health(scope.store(storeNo, request), storeNo, connectionNo, providerNo);
    }
}
