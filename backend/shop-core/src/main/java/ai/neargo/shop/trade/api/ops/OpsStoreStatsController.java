package ai.neargo.shop.trade.api.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.trade.service.MerchantOrderService;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 平台端 · 门店经营状况（P-11.2.1d，TDD-运营端门店与商品治理）。
 *
 * <p>在 trade 域而不是与门店档案同在 merchant 域：数据源
 * {@link MerchantOrderService#stats} 在这里，shop-merchant 是兄弟模块够不着。
 * 为省一次前端并行请求去开跨域聚合 Port 不值得 —— 前端把档案与经营两块合并展示。
 *
 * <p>复用 B 端商家自己看的那套统计（{@code stats(merchantNo, storeNos)}），
 * 不另存计数器：另存的迟早出现「总览说 3 单、点进去只有 2 单」。
 */
@Profile("ops")
@RestController
@Validated
public class OpsStoreStatsController {

    private final MerchantOrderService orderService;
    private final MerchantQueryPort merchantPort;

    public OpsStoreStatsController(MerchantOrderService orderService, MerchantQueryPort merchantPort) {
        this.orderService = orderService;
        this.merchantPort = merchantPort;
    }

    /**
     * 门店经营状况：今日/本月订单与 GMV + 待办堆积。
     *
     * <p>待办只取**门店维度**三项（待发货/待自送/缺货）——
     * 核销与分拣是自提点维度且不限商家（B-6 的形状），摆进门店页会被读成「这家店的活」。
     */
    @GetMapping("/ops/stores/{storeNo}/stats")
    @PreAuthorize("@perm.can('" + Perms.MERCHANT_READ + "')")
    public StoreStatsVO storeStats(@PathVariable String storeNo) {
        String entityNo = merchantPort.entityOfStores(List.of(storeNo)).get(storeNo);
        if (entityNo == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        var stats = orderService.stats(entityNo, List.of(storeNo));
        var todo = orderService.todo(entityNo, List.of(storeNo), List.of());
        return new StoreStatsVO(storeNo, entityNo,
                stats.todayOrders(), stats.todayGmvMinor(),
                stats.monthOrders(), stats.monthGmvMinor(), stats.ownedTrafficRate(),
                todo.toShip(), todo.toDeliver(), todo.toStock());
    }

    /**
     * @param ownedTrafficRate 自带客流占比 —— 直接对应这家店少付的佣金（ADR-004）
     * @param toStock          缺货待补 —— 运营看它判断「这家店是不是没人管了」
     */
    public record StoreStatsVO(String storeNo, String merchantNo,
                               int todayOrders, long todayGmvMinor,
                               int monthOrders, long monthGmvMinor,
                               double ownedTrafficRate,
                               int toShip, int toDeliver, int toStock) {
    }
}
