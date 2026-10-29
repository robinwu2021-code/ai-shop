package ai.neargo.inventory.svc.api;

import ai.neargo.inventory.api.InventoryError;
import ai.neargo.inventory.api.InventoryException;
import ai.neargo.inventory.domain.dto.InventoryVOs.BalanceVO;
import ai.neargo.inventory.domain.dto.InventoryVOs.LedgerPageVO;
import ai.neargo.inventory.domain.service.LocationService;
import ai.neargo.inventory.domain.service.OpenApiCredentialService;
import ai.neargo.inventory.domain.service.StockCountService;
import ai.neargo.inventory.domain.service.StockQueryService;
import ai.neargo.inventory.guard.InventoryRequestContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 运营端与旧开放接口的服务端对应（M8 T8.3，读写全切）：商城 {@code OpsInventoryController}、
 * {@code OpsOpenCredentialController}、{@code OpenInventoryController} 对已翻商家转发到这里。
 *
 * <p>开放接口的 AppKey 仍由商城认（服务不对外），认出业主后按商家号转发；凭证吊销核对归属 ——
 * 内嵌时期只按凭证号吊销，这里多一道「是这本账的」。
 */
@RestController
public class ConsoleOpsController {

    private static final String BASE = "/api/inventory/v1/console/platform";
    private static final int PAGE_MAX = 200;

    private final StockQueryService query;
    private final StockCountService counts;
    private final OpenApiCredentialService credentials;
    private final LocationService locations;

    public ConsoleOpsController(StockQueryService query, StockCountService counts,
                                OpenApiCredentialService credentials, LocationService locations) {
        this.query = query;
        this.counts = counts;
        this.credentials = credentials;
        this.locations = locations;
    }

    /** 不限库位（运营端）或指定库位（开放接口）的余额 */
    @GetMapping(BASE + "/balances")
    public List<BalanceVO> balances(@RequestParam(defaultValue = "todo") String type,
                                    @RequestParam(required = false) String locationId,
                                    @RequestParam(defaultValue = "100") int size) {
        return query.balances(owner(), locationId == null || locationId.isBlank() ? null : mine(locationId), type,
                Math.min(size, PAGE_MAX));
    }

    @GetMapping(BASE + "/ledger")
    public LedgerPageVO ledger(@RequestParam(required = false) String itemId,
                               @RequestParam(required = false) Long cursor,
                               @RequestParam(defaultValue = "50") int size) {
        return query.ledger(owner(), itemId, null, null, cursor, Math.min(size, PAGE_MAX));
    }

    @GetMapping(BASE + "/credentials")
    public List<OpenApiCredentialService.Listed> credentials() {
        return credentials.list(owner());
    }

    @PostMapping(BASE + "/credentials")
    public OpenApiCredentialService.Issued issue(@RequestBody IssueReq req) {
        return credentials.issue(owner(), req.name(), req.scopes(), req.expiresAt());
    }

    @PostMapping(BASE + "/credentials/{credentialId}/revoke")
    public void revoke(@PathVariable String credentialId) {
        if (!owner().equals(credentials.ownerIdOfCredential(credentialId))) {
            throw InventoryException.of(InventoryError.NOT_FOUND, "credential");
        }
        credentials.revoke(credentialId);
    }

    /** 开放接口的库存同步：落成盘点单，幂等号进操作人（与内嵌一致） */
    @PostMapping(BASE + "/stock-sync")
    public SyncResult sync(@RequestBody SyncReq req) {
        String owner = owner();
        String location = mine(req.locationId());
        for (SyncLine l : req.lines()) {
            counts.adjustOne(owner, location, l.itemId(), l.qty(), "OTHER", "OPENAPI:" + req.requestId());
        }
        return new SyncResult(req.requestId(), req.lines().size());
    }

    public record IssueReq(String name, String scopes, LocalDateTime expiresAt) {
    }

    public record SyncReq(String requestId, String locationId, List<SyncLine> lines) {
    }

    public record SyncLine(String itemId, int qty) {
    }

    public record SyncResult(String requestId, int applied) {
    }

    private String mine(String locationId) {
        if (locationId == null || locations.list(owner()).stream().noneMatch(l -> locationId.equals(l.getLocationId()))) {
            throw InventoryException.of(InventoryError.FORBIDDEN, "location");
        }
        return locationId;
    }

    private static String owner() {
        return InventoryRequestContext.caller().ownerId();
    }
}
