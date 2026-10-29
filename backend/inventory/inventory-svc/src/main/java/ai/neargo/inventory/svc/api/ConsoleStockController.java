package ai.neargo.inventory.svc.api;

import ai.neargo.inventory.api.InventoryError;
import ai.neargo.inventory.api.InventoryException;
import ai.neargo.inventory.domain.dto.InventoryVOs.BalanceVO;
import ai.neargo.inventory.domain.dto.InventoryVOs.CrossStoreVO;
import ai.neargo.inventory.domain.dto.InventoryVOs.ItemDetailVO;
import ai.neargo.inventory.domain.dto.InventoryVOs.LedgerPageVO;
import ai.neargo.inventory.domain.dto.InventoryVOs.SummaryVO;
import ai.neargo.inventory.domain.service.HostReferenceService;
import ai.neargo.inventory.domain.service.ItemService;
import ai.neargo.inventory.domain.service.LocationService;
import ai.neargo.inventory.domain.service.StockCountService;
import ai.neargo.inventory.domain.service.StockQueryService;
import ai.neargo.inventory.guard.InventoryRequestContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 商家后台 · 库存（M8 T8.3，读写全切）：商城 {@code BizStockController} 的服务端对应，返回结构逐个相同，
 * 商城对已翻到服务的商家原样转发。判权、数据域在商城做完；这里只按凭证 + 业主头限定这本账。
 *
 * <p>{@code storeRef} = 商城的当前门店号：门店库位没有就建、按发货源落到实际扣库存的库位（与内嵌同一份逻辑）。
 */
@RestController
public class ConsoleStockController {

    private static final String BASE = "/api/inventory/v1/console/stock";
    private static final int PAGE_MAX = 100;

    private final StockQueryService query;
    private final StockCountService counts;
    private final ItemService items;
    private final LocationService locations;
    private final HostReferenceService refs;

    public ConsoleStockController(StockQueryService query, StockCountService counts, ItemService items,
                                  LocationService locations, HostReferenceService refs) {
        this.query = query;
        this.counts = counts;
        this.items = items;
        this.locations = locations;
        this.refs = refs;
    }

    @GetMapping(BASE + "/summary")
    public SummaryVO summary(@RequestParam(required = false) String storeRef) {
        return query.summary(owner(), store(storeRef));
    }

    @GetMapping(BASE + "/balances")
    public List<BalanceVO> balances(@RequestParam(required = false) String filter,
                                    @RequestParam(required = false) String locationId,
                                    @RequestParam(required = false) String storeRef,
                                    @RequestParam(defaultValue = "50") int size) {
        return query.balances(owner(), locationId == null || locationId.isBlank() ? store(storeRef) : mine(locationId),
                filter, Math.min(size, PAGE_MAX));
    }

    @GetMapping(BASE + "/cross-store")
    public List<CrossStoreVO> crossStore(@RequestParam(required = false) String filter,
                                         @RequestParam(defaultValue = "50") int size) {
        return query.crossStore(owner(), filter, Math.min(size, PAGE_MAX));
    }

    @GetMapping(BASE + "/pickable")
    public List<BalanceVO> pickable(@RequestParam(required = false) String q,
                                    @RequestParam(required = false) String storeRef,
                                    @RequestParam(defaultValue = "200") int size) {
        return query.pickableItems(owner(), store(storeRef), q, Math.min(size, PAGE_MAX));
    }

    /** 没命中回 null（「这个码还没绑过」是常态），与商城端点一致 */
    @GetMapping(BASE + "/items/by-barcode")
    public BalanceVO byBarcode(@RequestParam String code, @RequestParam(required = false) String storeRef) {
        return query.byBarcode(owner(), store(storeRef), code);
    }

    /** 按 SKU 反查这件货的账；没建账、别家的 SKU 都回 null（不泄露「这个 SKU 存在」） */
    @GetMapping(BASE + "/item-by-sku")
    public ItemDetailVO itemBySku(@RequestParam String skuNo) {
        String owner = owner();
        String itemId = refs.itemIdOf(owner, skuNo);
        return itemId == null ? null : query.itemDetail(owner, itemId);
    }

    @GetMapping(BASE + "/items/{itemId}")
    public ItemDetailVO item(@PathVariable String itemId) {
        return query.itemDetail(owner(), itemId);
    }

    @GetMapping(BASE + "/ledger")
    public LedgerPageVO ledger(@RequestParam(required = false) String itemId,
                               @RequestParam(required = false) String docNo,
                               @RequestParam(required = false) Long cursor,
                               @RequestParam(required = false) String storeRef,
                               @RequestParam(defaultValue = "20") int size) {
        return query.ledger(owner(), itemId, docNo, store(storeRef), cursor, Math.min(size, PAGE_MAX));
    }

    /** 单件改数：底下是一张单件盘点单 */
    @PostMapping(BASE + "/adjust")
    public void adjust(@RequestBody Adjust req) {
        var caller = InventoryRequestContext.caller();
        counts.adjustOne(caller.ownerId(), store(req.storeRef()), req.itemId(), req.countedQty(), req.reasonCode(),
                operator(caller));
    }

    @PutMapping(BASE + "/safety-stock")
    public void safetyStock(@RequestBody SafetyStock req) {
        var caller = InventoryRequestContext.caller();
        items.setSafetyStock(caller.ownerId(), req.itemId(),
                req.locationId() == null || req.locationId().isBlank() ? null : mine(req.locationId()), req.qty(),
                operator(caller));
    }

    public record Adjust(String itemId, int countedQty, String reasonCode, String storeRef) {
    }

    public record SafetyStock(String itemId, String locationId, Integer qty) {
    }

    private static String owner() {
        return InventoryRequestContext.caller().ownerId();
    }

    private String store(String storeRef) {
        return refs.storeLocationOf(owner(), storeRef);
    }

    /** 端上传来的库位号必须是这本账的：不校验的话改一个字符就能读到别家的库存 */
    private String mine(String locationId) {
        boolean mine = locations.list(owner()).stream().anyMatch(l -> locationId.equals(l.getLocationId()));
        if (!mine) {
            throw InventoryException.of(InventoryError.FORBIDDEN, "location");
        }
        return locationId;
    }

    private static String operator(ai.neargo.inventory.domain.service.Caller caller) {
        return caller.actor() == null ? caller.systemCode() : caller.actor();
    }
}
