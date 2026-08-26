package ai.neargo.shop.invbridge.port;

import ai.neargo.shop.inventory.service.InventoryAclService;
import ai.neargo.shop.inventory.service.LocationService;
import ai.neargo.shop.inventory.service.ReservationService;
import ai.neargo.shop.spi.product.StockPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 把交易域的库存调用接到**进销存**上 —— D2 的真相源切换开关。
 *
 * <h2>默认不生效</h2>
 * 只有 {@code shop.inventory.stock-authority=INVENTORY} 时才装配。
 * 默认值是 {@code PLATFORM}，此时本类整个不存在，交易域拿到的仍是
 * {@code StockPortImpl}（写 {@code prd_sku} / {@code prd_store_stock}）——
 * **代码合进来当天，一个字节的行为都不变**。
 *
 * <h2>切换的前提是 G3，不是「代码写好了」</h2>
 * 开发计划里那道闸门的原话：<b>对差连续 N 天为零才准切</b>。
 * 直接切等于「切换那天开始超卖」，而无从回溯是从哪一刻起的 ——
 * 对差看板（{@code /ops/inventory/recon}）就是为这一刻准备的。
 * 所以这里是一个配置项而不是一次发版：<b>发现不对，改回去只要重启。</b>
 *
 * <h2>为什么在 shop-app 而不在 shop-core</h2>
 * 它要同时认识 {@code StockPort}（spi）与进销存的 Service。
 * 放进商品域就是域间直连，而那正是架构守卫拦的东西；
 * 装配层是唯一能同时看到两边的位置，与 {@code InventoryBackfillService} 同理。
 *
 * <h2>翻译只有两处</h2>
 * {@code skuNo → itemId}、{@code storeNo → locationId}，都走防腐层。
 * 交易域**一行不改** —— 它给的仍然是 {@code SkuQty}。
 */
@Component
@Primary
@ConditionalOnProperty(prefix = "shop.inventory", name = "stock-authority", havingValue = "INVENTORY")
public class InventoryStockPort implements StockPort {

    /**
     * 预留的存活时长。与平台的「未支付自动关单」同一个量级 ——
     * 比关单短会让还能付款的单先被释放，比它长会让已关的单继续占着货。
     */
    private static final long RESERVE_TTL_SECONDS = 30 * 60L;

    private final ReservationService reservations;
    private final InventoryAclService acl;
    private final LocationService locations;

    public InventoryStockPort(ReservationService reservations, InventoryAclService acl,
                              LocationService locations) {
        this.reservations = reservations;
        this.acl = acl;
        this.locations = locations;
    }

    @Override
    public List<String> lock(String lockNo, List<SkuQty> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        String owner = acl.ownerOfSku(items.get(0).skuNo());
        List<ReservationService.Line> lines = toLines(owner, items);
        /*
         * **不吞异常**：库存不足时 reserve 抛 STOCK_NOT_ENOUGH，让它一路上去。
         *
         * 旧签名的约定是「返回不足的 SKU 列表」，而新协议把这件事放进错误体里
         * （带上是哪几件、各差多少）。这里返回空列表 = 全部成功；
         * 失败走异常 —— 熔断的方向是拒绝不是放行，返回空列表会被上游当成成功。
         */
        reservations.reserve(owner, lockNo, lines, RESERVE_TTL_SECONDS);
        return List.of();
    }

    @Override
    public void release(String lockNo) {
        // 释放拿不到 items，只能按订单号找 —— 而预留本来就是按 externalRef 存的
        reservations.releaseByRef(lockNo);
    }

    @Override
    public void confirm(String lockNo) {
        reservations.commitByRef(lockNo, "SYSTEM");
    }

    @Override
    public void restore(String restoreNo, List<SkuQty> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        String owner = acl.ownerOfSku(items.get(0).skuNo());
        reservations.restore(owner, restoreNo, toLines(owner, items), "SYSTEM");
    }

    /**
     * 翻译只有两处：{@code skuNo → itemId}、{@code storeNo → locationId}。
     *
     * <p><b>业主取第一行的</b>：交易域按子单拆过了，一次调用里的行属于同一个商家。
     * {@code SkuQty} 上没有主体号，靠外部引用表反查 —— 而 SKU 在平台内全局唯一，
     * 所以这一条是确定的。
     *
     * <p>门店设了发货源就扣源仓的；空门店走默认库位，那是存量主体级库存的落点。
     */
    private List<ReservationService.Line> toLines(String owner, List<SkuQty> items) {
        List<ReservationService.Line> lines = new ArrayList<>();
        for (SkuQty q : items) {
            String locationId = locations.resolveStockLocation(
                    owner, acl.locationOfStore(owner, q.storeNo()));
            lines.add(new ReservationService.Line(acl.itemIdOfSku(q.skuNo()), locationId, q.qty()));
        }
        return lines;
    }
}
