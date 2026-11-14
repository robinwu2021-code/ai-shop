package ai.neargo.shop.commerce.api.biz;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.commerce.api.CommerceRequestScope;
import ai.neargo.shop.commerce.config.ConditionalOnCommerceWebEnabled;
import ai.neargo.shop.commerce.dto.OperationContracts;
import ai.neargo.shop.commerce.dto.OperationVO;
import ai.neargo.shop.commerce.service.ChannelOperationService;
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
 * Account operations have ENTITY journal scope and a null store; store actions can never be
 * recovered through this API.
 */
@RestController
@Profile("api")
@ConditionalOnCommerceWebEnabled
@Validated
@PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')")
public class BizAccountOperationController {
    private final ChannelOperationService service;
    private final CommerceRequestScope scope;

    public BizAccountOperationController(
            ChannelOperationService service, CommerceRequestScope scope) {
        this.service = service;
        this.scope = scope;
    }

    @GetMapping("/biz/x/commerce/account-operations")
    public PageData<OperationVO> list(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        CommerceRequestScope.query(request, Set.of("type", "status", "page", "size"));
        return service.listOperations(
                scope.owner(), null, new OperationContracts.Query(type, status, page, size));
    }

    @GetMapping("/biz/x/commerce/account-operations/{operationNo}/attempts")
    public PageData<OperationContracts.Attempt> attempts(
            @PathVariable @NotBlank @Size(max = 32) String operationNo,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        CommerceRequestScope.query(request, Set.of("page", "size"));
        return service.listAttempts(scope.owner(), null, operationNo, page, size);
    }

    @PostMapping("/biz/x/commerce/account-operations/{operationNo}:refresh")
    public OperationVO refresh(
            @PathVariable @NotBlank @Size(max = 32) String operationNo,
            @Valid @RequestBody OperationContracts.Schedule body,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest request) {
        return schedule(operationNo, body, key, request, true);
    }

    @PostMapping("/biz/x/commerce/account-operations/{operationNo}:recover")
    public OperationVO recover(
            @PathVariable @NotBlank @Size(max = 32) String operationNo,
            @Valid @RequestBody OperationContracts.Schedule body,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest request) {
        return schedule(operationNo, body, key, request, false);
    }

    private OperationVO schedule(
            String number,
            OperationContracts.Schedule body,
            String key,
            HttpServletRequest request,
            boolean refreshOnly) {
        CommerceRequestScope.query(request, Set.of());
        return service.scheduleRecovery(
                scope.owner(), null, number, SecurityUtils.currentUserNo(), body, key, refreshOnly);
    }
}
