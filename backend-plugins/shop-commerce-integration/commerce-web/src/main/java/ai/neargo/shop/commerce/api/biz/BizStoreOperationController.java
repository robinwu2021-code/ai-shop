package ai.neargo.shop.commerce.api.biz;

import ai.neargo.shop.auth.BizContext;
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
 * Store context always comes from the original authenticated scope; recovery only schedules an
 * existing workflow.
 */
@RestController
@Profile("api")
@ConditionalOnCommerceWebEnabled
@Validated
@PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')")
public class BizStoreOperationController {
    private final ChannelOperationService service;
    private final CommerceRequestScope scope;

    public BizStoreOperationController(
            ChannelOperationService service, CommerceRequestScope scope) {
        this.service = service;
        this.scope = scope;
    }

    @GetMapping("/biz/x/commerce/stores/{storeNo}/operations")
    public PageData<OperationVO> list(
            @PathVariable @NotBlank @Size(max = 32) String storeNo,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        CommerceRequestScope.query(request, Set.of("type", "status", "page", "size"));
        return service.listOperations(
                scope.store(storeNo, request),
                storeNo,
                new OperationContracts.Query(type, status, page, size));
    }

    @GetMapping("/biz/x/commerce/operations/{operationNo}/attempts")
    public PageData<OperationContracts.Attempt> attempts(
            @PathVariable @NotBlank @Size(max = 32) String operationNo,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        CommerceRequestScope.query(request, Set.of("page", "size"));
        String store = BizContext.requireStoreNo();
        return service.listAttempts(scope.store(store, request), store, operationNo, page, size);
    }

    @PostMapping("/biz/x/commerce/operations/{operationNo}:refresh")
    public OperationVO refresh(
            @PathVariable @NotBlank @Size(max = 32) String operationNo,
            @Valid @RequestBody OperationContracts.Schedule body,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest request) {
        return schedule(operationNo, body, key, request, true);
    }

    @PostMapping("/biz/x/commerce/operations/{operationNo}:recover")
    public OperationVO recover(
            @PathVariable @NotBlank @Size(max = 32) String operationNo,
            @Valid @RequestBody OperationContracts.Schedule body,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest request) {
        return schedule(operationNo, body, key, request, false);
    }

    private OperationVO schedule(
            String no,
            OperationContracts.Schedule body,
            String key,
            HttpServletRequest request,
            boolean refreshOnly) {
        CommerceRequestScope.query(request, Set.of());
        String store = BizContext.requireStoreNo();
        return service.scheduleRecovery(
                scope.store(store, request),
                store,
                no,
                SecurityUtils.currentUserNo(),
                body,
                key,
                refreshOnly);
    }
}
