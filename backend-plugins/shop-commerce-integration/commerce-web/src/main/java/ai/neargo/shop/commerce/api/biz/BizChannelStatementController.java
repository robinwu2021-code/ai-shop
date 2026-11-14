package ai.neargo.shop.commerce.api.biz;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.commerce.api.CommerceRequestScope;
import ai.neargo.shop.commerce.config.ConditionalOnCommerceWebEnabled;
import ai.neargo.shop.commerce.dto.StatementContracts;
import ai.neargo.shop.commerce.service.ChannelReconciliationService;
import ai.neargo.shop.common.PageData;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Set;

/**
 * Account-wide evidence requires the original merchant owner; store queries always enforce current
 * store scope.
 */
@RestController
@Profile("api")
@ConditionalOnCommerceWebEnabled
@Validated
@ConditionalOnProperty(name = "shop.commerce.statement.enabled", havingValue = "true")
@PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')")
public class BizChannelStatementController {
    private final ChannelReconciliationService service;
    private final CommerceRequestScope scope;

    public BizChannelStatementController(
            ChannelReconciliationService service, CommerceRequestScope scope) {
        this.service = service;
        this.scope = scope;
    }

    @PostMapping("/biz/x/commerce/accounts/{accountNo}/statement-imports")
    public StatementContracts.Imported importStatement(
            @PathVariable @NotBlank @Size(max = 32) String accountNo,
            @Valid @RequestBody StatementContracts.Import body,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest request) {
        CommerceRequestScope.query(request, Set.of());
        return service.importStatement(
                scope.owner(), accountNo, SecurityUtils.currentUserNo(), body, key);
    }

    @GetMapping("/biz/x/commerce/accounts/{accountNo}/statements")
    public PageData<StatementContracts.Statement> statements(
            @PathVariable @NotBlank @Size(max = 32) String accountNo,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        CommerceRequestScope.query(
                request, Set.of("from", "to", "currency", "status", "page", "size"));
        return service.listStatements(
                scope.owner(),
                accountNo,
                new StatementContracts.Query(from, to, currency, status, page, size));
    }

    @GetMapping("/biz/x/commerce/stores/{storeNo}/statement-lines")
    public PageData<StatementContracts.Line> lines(
            @PathVariable @NotBlank @Size(max = 32) String storeNo,
            @RequestParam(required = false) String statementNo,
            @RequestParam(required = false) String connectionNo,
            @RequestParam(required = false) String differenceCode,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        CommerceRequestScope.query(
                request, Set.of("statementNo", "connectionNo", "differenceCode", "page", "size"));
        return service.listStoreLines(
                scope.store(storeNo, request),
                storeNo,
                new StatementContracts.LineQuery(
                        statementNo, connectionNo, differenceCode, page, size));
    }
}
