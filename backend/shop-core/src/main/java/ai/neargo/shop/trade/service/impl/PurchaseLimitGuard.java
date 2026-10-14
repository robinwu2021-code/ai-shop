package ai.neargo.shop.trade.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.platform.PlatformSwitchPort;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.TradeMappers.OrderItemMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.SubOrderMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 每人限购（待办设计 P1）。
 *
 * <p><b>这条规则此前一处都没拦</b>：商家在 B 端设了「每人限购 5 件」，C 端详情页也显示了，
 * 而下单链路从未读过它 —— 只有 c-app 的 mock 在拦，于是本机点一遍是对的，线上买 50 件照样成交。
 *
 * <p>口径（已拍板）：
 * <ul>
 *   <li>按<b>商品</b>算，不按规格 —— 商家填的是「这件货每人几件」；</li>
 *   <li><b>终身累计</b>，不设窗口；</li>
 *   <li>已买量 = 该用户在这件货上、子单<b>不是</b>已取消 / 已退款的行数量之和
 *       （待付款也算 —— 它占着库存，不算的话开十张待付款单就绕过去了）；赠品行不算。</li>
 * </ul>
 *
 * <p>开关 {@value #FLAG}（运营端功能开关，默认开）。关掉 = 回到只显示不拦，给误伤时紧急放行。
 * 下单、预览、加购三处共用这一份，<b>不各写一遍</b> —— 购物车说能加、结账却被拒，比两边都拒更糟。
 */
@Component
public class PurchaseLimitGuard {

    public static final String FLAG = "trade.purchase-limit.enforce";

    private final SubOrderMapper subOrderMapper;
    private final OrderItemMapper itemMapper;
    private final PlatformSwitchPort switchPort;

    public PurchaseLimitGuard(SubOrderMapper subOrderMapper, OrderItemMapper itemMapper,
                              PlatformSwitchPort switchPort) {
        this.subOrderMapper = subOrderMapper;
        this.itemMapper = itemMapper;
        this.switchPort = switchPort;
    }

    public boolean enforced() {
        return switchPort.bool(FLAG, true);
    }

    /**
     * 该用户在这些商品上已经买了几件（口径见类注释）。没买过的不在结果里。
     *
     * <p><b>绕开数据域</b>：代客下单是商家身份在调，接着域查 C 端用户的子单会静默查出 0 行，
     * 而 0 行恰好是「不拦」—— 闸门在最该生效的那条路上悄悄失效。
     */
    public Map<String, Integer> boughtQty(String userNo, Collection<String> goodsNos) {
        Map<String, Integer> out = new HashMap<>();
        if (userNo == null || goodsNos == null || goodsNos.isEmpty()) {
            return out;
        }
        List<String> subNos = DataScopeContext.executeWithoutScope(() -> subOrderMapper.selectList(
                        Wrappers.<OrdSubOrder>lambdaQuery()
                                .select(OrdSubOrder::getSubOrderNo)
                                .eq(OrdSubOrder::getUserNo, userNo)
                                .notIn(OrdSubOrder::getStatus, OrdSubOrder.CANCELLED, OrdSubOrder.REFUNDED)))
                .stream().map(OrdSubOrder::getSubOrderNo).toList();
        if (subNos.isEmpty()) {
            return out;
        }
        List<OrdItem> rows = DataScopeContext.executeWithoutScope(() -> itemMapper.selectList(
                Wrappers.<OrdItem>lambdaQuery()
                        .in(OrdItem::getSubOrderNo, subNos)
                        .in(OrdItem::getGoodsNo, goodsNos)));
        for (OrdItem r : rows) {
            if (Boolean.TRUE.equals(r.getIsGift()) || r.getQty() == null) {
                continue;
            }
            out.merge(r.getGoodsNo(), r.getQty(), Integer::sum);
        }
        return out;
    }

    /**
     * 这一次要买的量（按商品汇总）加上已买量超过限购就拒。开关关着时什么都不做。
     *
     * @param wanted 商品号 → 这一次的件数
     * @param limits 商品号 → 每人限购（只放设了限购的）
     */
    public void require(String userNo, Map<String, Integer> wanted, Map<String, Integer> limits) {
        if (limits.isEmpty() || !enforced()) {
            return;
        }
        Map<String, Integer> bought = boughtQty(userNo, limits.keySet());
        for (var e : limits.entrySet()) {
            int want = wanted.getOrDefault(e.getKey(), 0);
            int had = bought.getOrDefault(e.getKey(), 0);
            if (want + had > e.getValue()) {
                throw BizException.of(ErrorCode.PURCHASE_LIMIT_EXCEEDED, Math.max(0, e.getValue() - had));
            }
        }
    }
}
