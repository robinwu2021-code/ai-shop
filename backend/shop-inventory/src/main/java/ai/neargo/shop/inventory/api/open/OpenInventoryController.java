package ai.neargo.shop.inventory.api.open;

import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.shop.inventory.dto.InventoryVOs.BalanceVO;
import ai.neargo.shop.inventory.dto.InventoryVOs.LedgerPageVO;
import ai.neargo.shop.inventory.service.OpenApiCredentialService;
import ai.neargo.shop.inventory.service.StockCountService;
import ai.neargo.shop.inventory.service.StockQueryService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 对外接口（商家自己的 ERP / 收银系统调）。
 *
 * <p><b>独立的 {@code @Profile("openapi")}</b>：外部流量 QPS 不可控、要单独限流、
 * 要独立故障域。与下单同进程的话，一个对接方拉一年流水就能拖慢所有人下单。
 *
 * <p><b>先拉后推，不做 webhook</b>：webhook 要重试队列、签名、对方不可用时的堆积治理。
 * 拉够用了再说。
 *
 * <p>鉴权在方法里显式做，不挂 Spring Security 链：这一面只有三个端点、
 * 一种凭证，加一条链要配的东西比它本身还多。
 *
 * <p><b>订单不在这里</b>：那是交易域的数据，本域没有也不该有。
 * ERP 要拉订单是平台的 Open API，与这一面是两回事。
 */
@Profile("openapi")
@ConditionalOnInventory
@RestController
public class OpenInventoryController {

    private static final int PAGE_MAX = 200;
    private static final String SCOPE_READ = "read";
    private static final String SCOPE_SYNC = "stock:sync";

    private final OpenApiCredentialService credentials;
    private final StockQueryService query;
    private final StockCountService counts;

    public OpenInventoryController(OpenApiCredentialService credentials, StockQueryService query,
                                   StockCountService counts) {
        this.credentials = credentials;
        this.query = query;
        this.counts = counts;
    }

    /*
     * 三个口的鉴权头都是 **required = false**。
     *
     * 默认（必填）的话，对方漏带头会被 Spring 在进 service 之前挡下来，
     * 抛 MissingRequestHeaderException → 落到通用错误上，对方看到的是
     * **10500「服务器内部错」** —— 于是他会拿它来报「你们服务端坏了」，
     * 而真相是他自己少带了一个头。
     *
     * 更糟的是 {@code OpenApiCredentialService.ownerOf} 里那句
     * `if (appKey == null) throw UNAUTHORIZED` **永远走不到** ——
     * 一段看着周全、实际是死的防御。放进来才让它活过来，
     * 也才对得上那句「四种失败一个错码」。
     */

    /** 物料与结存：ERP 按 {@code merchant_sku_code} / {@code barcode} 对上自己的货。 */
    @GetMapping("/open/v1/items")
    public List<BalanceVO> items(@RequestHeader(value = "X-App-Key", required = false) String key,
                                 @RequestHeader(value = "X-App-Secret", required = false) String secret,
                                 @RequestParam(required = false) String locationId,
                                 @RequestParam(defaultValue = "100") int size) {
        String owner = credentials.ownerOf(key, secret, SCOPE_READ);
        return query.balances(owner, locationId, "all", Math.min(size, PAGE_MAX));
    }

    /**
     * 流水增量。
     *
     * <p><b>游标是 {@code id} 不是时间</b>：时钟回拨会让时间游标漏行，
     * 而漏的那几行不会有任何报错 —— 对方的账从此少一块，且查不出少在哪。
     */
    @GetMapping("/open/v1/stock-ledger")
    public LedgerPageVO ledger(@RequestHeader(value = "X-App-Key", required = false) String key,
                               @RequestHeader(value = "X-App-Secret", required = false) String secret,
                               @RequestParam(required = false) Long since,
                               @RequestParam(defaultValue = "100") int size) {
        String owner = credentials.ownerOf(key, secret, SCOPE_READ);
        return query.ledger(owner, null, null, null, since, Math.min(size, PAGE_MAX));
    }

    /**
     * 库存同步（ERP 权威时用）。
     *
     * <p><b>落的是一张盘点单，不是直接改数</b> —— 外部推进来的量与商家自己盘出来的
     * 是同一件事：「实际有多少」。走同一个口，账上才分得清这一笔是谁改的。
     *
     * <p>幂等靠 {@code requestId}：它进操作人字段，而盘点单本身的单号唯一 ——
     * 不另建一张幂等表，那样多一个要过期清理的东西。
     */
    @PostMapping("/open/v1/stock:sync")
    public SyncResult sync(@RequestHeader(value = "X-App-Key", required = false) String key,
                           @RequestHeader(value = "X-App-Secret", required = false) String secret,
                           @RequestBody SyncReq req) {
        String owner = credentials.ownerOf(key, secret, SCOPE_SYNC);
        for (SyncLine l : req.lines()) {
            counts.adjustOne(owner, req.locationId(), l.itemId(), l.qty(),
                    "OTHER", "OPENAPI:" + req.requestId());
        }
        return new SyncResult(req.requestId(), req.lines().size());
    }

    /** @param requestId 调用方给的幂等键，落到操作人上，出了事查得到是哪一次推送 */
    public record SyncReq(String requestId, String locationId, List<SyncLine> lines) {
    }

    public record SyncLine(String itemId, int qty) {
    }

    public record SyncResult(String requestId, int applied) {
    }
}
