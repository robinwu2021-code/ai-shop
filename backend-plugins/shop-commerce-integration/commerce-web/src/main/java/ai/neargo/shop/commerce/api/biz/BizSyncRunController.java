package ai.neargo.shop.commerce.api.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.commerce.api.CommerceRequestScope;
import ai.neargo.shop.commerce.config.ConditionalOnCommerceWebEnabled;
import ai.neargo.shop.commerce.dto.CatalogContracts.CancelQueuedSyncReq;
import ai.neargo.shop.commerce.dto.CatalogContracts.SubmitSyncRunReq;
import ai.neargo.shop.commerce.dto.CatalogContracts.SyncRunItemVO;
import ai.neargo.shop.commerce.dto.CatalogContracts.SyncRunVO;
import ai.neargo.shop.commerce.service.SyncRunService;
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

/** 手动任务与自动任务共用原队列；取消入口只作用于尚未发送的意图。 */
@RestController
@Profile("api")
@ConditionalOnCommerceWebEnabled
@Validated
@PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')")
public class BizSyncRunController {
    private final SyncRunService service;
    private final CommerceRequestScope scope;

    public BizSyncRunController(SyncRunService service, CommerceRequestScope scope) {
        this.service = service;
        this.scope = scope;
    }

    @PostMapping("/biz/x/commerce/stores/{storeNo}/sync-runs")
    public SyncRunVO submit(
            @PathVariable("storeNo") @NotBlank @Size(max = 32) String storeNo,
            @Valid @RequestBody SubmitSyncRunReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest request) {
        CommerceRequestScope.query(request, Set.of());
        return service.submit(
                scope.store(storeNo, request), storeNo, SecurityUtils.currentUserNo(), req, key);
    }

    @GetMapping("/biz/x/commerce/stores/{storeNo}/sync-runs")
    public PageData<SyncRunVO> listRuns(
            @PathVariable("storeNo") @NotBlank @Size(max = 32) String storeNo,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            @RequestParam(value = "connectionNo", required = false) String connectionNo,
            @RequestParam(value = "status", required = false) String status,
            HttpServletRequest request) {
        CommerceRequestScope.query(request, Set.of("page", "size", "connectionNo", "status"));
        return service.listRuns(
                scope.store(storeNo, request), storeNo, page, size, connectionNo, status);
    }

    @GetMapping("/biz/x/commerce/sync-runs/{runNo}")
    public SyncRunVO getRun(
            @PathVariable("runNo") @NotBlank @Size(max = 32) String runNo,
            HttpServletRequest request) {
        CommerceRequestScope.query(request, Set.of());
        String store = BizContext.requireStoreNo();
        return service.getRun(scope.store(store, request), store, runNo);
    }

    @GetMapping("/biz/x/commerce/sync-runs/{runNo}/items")
    public PageData<SyncRunItemVO> listItems(
            @PathVariable("runNo") @NotBlank @Size(max = 32) String runNo,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            HttpServletRequest request) {
        CommerceRequestScope.query(request, Set.of("page", "size"));
        String store = BizContext.requireStoreNo();
        return service.listItems(scope.store(store, request), store, runNo, page, size);
    }

    @PostMapping("/biz/x/commerce/sync-runs/{runNo}:cancel-queued")
    public SyncRunVO cancelQueued(
            @PathVariable("runNo") @NotBlank @Size(max = 32) String runNo,
            @Valid @RequestBody CancelQueuedSyncReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest request) {
        CommerceRequestScope.query(request, Set.of());
        String store = BizContext.requireStoreNo();
        return service.cancelQueued(
                scope.store(store, request), store, runNo, SecurityUtils.currentUserNo(), req, key);
    }
}
