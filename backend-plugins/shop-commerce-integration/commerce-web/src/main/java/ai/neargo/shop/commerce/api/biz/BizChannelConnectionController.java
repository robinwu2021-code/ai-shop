package ai.neargo.shop.commerce.api.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.commerce.api.CommerceRequestScope;
import ai.neargo.shop.commerce.config.ConditionalOnCommerceWebEnabled;
import ai.neargo.shop.commerce.dto.ChannelConnectionVO;
import ai.neargo.shop.commerce.dto.ConnectionLifecycleContracts.Configuration;
import ai.neargo.shop.commerce.dto.ConnectionLifecycleContracts.SetStatus;
import ai.neargo.shop.commerce.dto.ConnectionLifecycleContracts.Verify;
import ai.neargo.shop.commerce.dto.CreateChannelConnectionReq;
import ai.neargo.shop.commerce.dto.OperationVO;
import ai.neargo.shop.commerce.service.ChannelConnectionService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/** 创建只保存草稿，不能通过前端参数打开外卖订单导入。 */
@RestController
@Profile("api")
@ConditionalOnCommerceWebEnabled
@Validated
@PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')")
public class BizChannelConnectionController {
    private final ChannelConnectionService service;
    private final CommerceRequestScope scope;

    public BizChannelConnectionController(
            ChannelConnectionService service, CommerceRequestScope scope) {
        this.service = service;
        this.scope = scope;
    }

    @PostMapping("/biz/x/commerce/stores/{storeNo}/connections")
    public ChannelConnectionVO createConnection(
            @PathVariable("storeNo") @NotBlank @Size(max = 32) String storeNo,
            @Valid @RequestBody CreateChannelConnectionReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest request) {
        CommerceRequestScope.query(request, Set.of());
        return service.createConnection(
                scope.store(storeNo, request), storeNo, SecurityUtils.currentUserNo(), req, key);
    }

    @GetMapping("/biz/x/commerce/stores/{storeNo}/connections")
    public PageData<ChannelConnectionVO> listConnections(
            @PathVariable("storeNo") @NotBlank @Size(max = 32) String storeNo,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            HttpServletRequest request) {
        CommerceRequestScope.query(request, Set.of("page", "size"));
        return service.listConnections(scope.store(storeNo, request), storeNo, page, size);
    }

    @PutMapping("/biz/x/commerce/connections/{connectionNo}")
    public ChannelConnectionVO saveConfiguration(
            @PathVariable @NotBlank @Size(max = 32) String connectionNo,
            @Valid @RequestBody Configuration body,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest request) {
        CommerceRequestScope.query(request, Set.of());
        String store = BizContext.requireStoreNo();
        return service.saveConfiguration(
                scope.store(store, request),
                store,
                connectionNo,
                SecurityUtils.currentUserNo(),
                body,
                key);
    }

    @PostMapping("/biz/x/commerce/connections/{connectionNo}:verify")
    public OperationVO verify(
            @PathVariable @NotBlank @Size(max = 32) String connectionNo,
            @Valid @RequestBody Verify body,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest request) {
        CommerceRequestScope.query(request, Set.of());
        String store = BizContext.requireStoreNo();
        return service.verify(
                scope.store(store, request),
                store,
                connectionNo,
                SecurityUtils.currentUserNo(),
                body,
                key);
    }

    @PostMapping("/biz/x/commerce/connections/{connectionNo}:set-status")
    public ChannelConnectionVO setStatus(
            @PathVariable @NotBlank @Size(max = 32) String connectionNo,
            @Valid @RequestBody SetStatus body,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest request) {
        CommerceRequestScope.query(request, Set.of());
        String store = BizContext.requireStoreNo();
        return service.setStatus(
                scope.store(store, request),
                store,
                connectionNo,
                SecurityUtils.currentUserNo(),
                body,
                key);
    }

    @GetMapping("/biz/x/commerce/connections/{connectionNo}")
    public ChannelConnectionVO getConnection(
            @PathVariable("connectionNo") @NotBlank @Size(max = 32) String connectionNo,
            HttpServletRequest request) {
        CommerceRequestScope.query(request, Set.of());
        String store = BizContext.requireStoreNo();
        return service.getConnection(scope.store(store, request), store, connectionNo);
    }
}
