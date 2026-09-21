package ai.neargo.shop.trade.service.impl;

import ai.neargo.shop.trade.service.CartService;

import ai.neargo.shop.spi.product.GoodsQueryPort;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.trade.dto.CartItemVO;
import ai.neargo.shop.trade.entity.TrdCartItem;
import ai.neargo.shop.trade.mapper.TradeMappers.CartItemMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class CartServiceImpl implements CartService {

    private final CartItemMapper cartMapper;
    private final GoodsQueryPort goodsPort;
    private final MerchantQueryPort merchantPort;

    public CartServiceImpl(CartItemMapper cartMapper, GoodsQueryPort goodsPort, MerchantQueryPort merchantPort) {
        this.cartMapper = cartMapper;
        this.goodsPort = goodsPort;
        this.merchantPort = merchantPort;
    }

    /**
     * 仅活动商品的可买判定（TDD-商品仅活动可售 §4.3）。setter 注入、缺了按「没有活动」处理，
     * 与 OrderServiceImpl 同一口径 —— 购物车说能买、结账却被拒，比两边都拒更糟。
     */
    private ai.neargo.shop.spi.marketing.SaleGatePort saleGatePort;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setSaleGatePort(ai.neargo.shop.spi.marketing.SaleGatePort saleGatePort) {
        this.saleGatePort = saleGatePort;
    }

    /**
     * 这些货里此刻<b>有任何活动在跑</b>的（含拼团）。
     *
     * <p><b>购物车看 any，不看 direct</b>：C 端的「开团」「立即购买」都是先加购、再带着 skus 进确认页
     * （goods 页 openGroupBuy / buyNow）—— 购物车是所有下单的运输通道，不只是单买那一条。
     * 只有拼团在跑时在这里拒，仅活动商品的开团就在加购那一步断了。
     * 单买真正被拦在 OrderServiceImpl.split()：不带团号 / 开团又没有集单特价买赠开着 → 拒。
     */
    private java.util.Set<String> anyLive(java.util.Collection<GoodsQueryPort.SkuSnapshot> snaps) {
        List<String> only = snaps.stream().filter(GoodsQueryPort.SkuSnapshot::activityOnly)
                .map(GoodsQueryPort.SkuSnapshot::goodsNo).distinct().toList();
        if (only.isEmpty() || saleGatePort == null) {
            return java.util.Set.of();
        }
        return saleGatePort.live(only, System.currentTimeMillis()).any();
    }

    /** 每人限购（P1）。与下单共用一份口径 —— 购物车说能加、结账却被拒，比两边都拒更糟 */
    private PurchaseLimitGuard purchaseLimit;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPurchaseLimit(PurchaseLimitGuard purchaseLimit) {
        this.purchaseLimit = purchaseLimit;
    }

    /**
     * 车里这件货（所有规格合计）改成 {@code cartQtyOfSku} 之后会不会超限购。
     * 「立即购买」也是先加购再结账（goods 页 buyNow），所以这里拦住的是两条路。
     */
    private void requireWithinLimit(GoodsQueryPort.SkuSnapshot snap, String skuNo, int newQtyOfSku) {
        if (purchaseLimit == null || snap == null || !snap.limited()) {
            return;
        }
        int others = rows().stream()
                .filter(r -> snap.goodsNo().equals(r.getGoodsNo()) && !skuNo.equals(r.getSkuNo()))
                .mapToInt(r -> r.getQty() == null ? 0 : r.getQty()).sum();
        purchaseLimit.require(SecurityUtils.currentUserNo(),
                Map.of(snap.goodsNo(), others + newQtyOfSku),
                Map.of(snap.goodsNo(), snap.limitPerUser()));
    }

    @Override
    public List<CartItemVO> list() {
        List<TrdCartItem> rows = rows();
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<String, GoodsQueryPort.SkuSnapshot> snapshots =
                goodsPort.snapshot(rows.stream().map(TrdCartItem::getSkuNo).toList());
        // 仅活动的货：活动全结束后还躺在车里的那件，与下架同一处理 —— 失效行，不抹掉
        java.util.Set<String> open = anyLive(snapshots.values());

        return rows.stream().map(row -> {
            GoodsQueryPort.SkuSnapshot s = snapshots.get(row.getSkuNo());
            if (s == null) {
                // SKU 查不到（下架或删除）：仍然返回一行，标记失效。
                // 直接从购物车里抹掉更省事，但用户会以为「我明明加过」，投诉无从查起
                return new CartItemVO(row.getGoodsNo(), row.getSkuNo(), "该商品已下架", "", "",
                        0L, row.getQty(), "", "", "", "",
                        Boolean.TRUE.equals(row.getSelected()), true, 0);
            }
            String merchantName = merchantPort.find(s.merchantNo())
                    .map(MerchantQueryPort.MerchantBrief::merchantName).orElse("");
            return new CartItemVO(s.goodsNo(), s.skuNo(), s.title(), s.cover(), s.spec(),
                    s.price(), row.getQty(), s.categoryType(),
                    s.fulfillments().isEmpty() ? "" : s.fulfillments().get(0),
                    s.merchantNo(), merchantName,
                    Boolean.TRUE.equals(row.getSelected()),
                    !s.onSale() || (s.activityOnly() && !open.contains(s.goodsNo())), s.available());
        }).toList();
    }

    @Override
    @Transactional
    public List<CartItemVO> add(String goodsNo, String skuNo, int qty) {
        /*
         * 仅活动且此刻<b>什么活动都没在跑</b>的货，加购就拒，不等到结账 ——
         * 放进去再标失效也挡得住下单，但顾客会看到「加入成功」紧跟着一行灰掉的货。
         * 有拼团在跑时放行：开团也是先加购（见 anyLive 的注释）。
         */
        GoodsQueryPort.SkuSnapshot snap = goodsPort.snapshot(List.of(skuNo)).get(skuNo);
        if (snap != null && snap.activityOnly() && !anyLive(List.of(snap)).contains(snap.goodsNo())) {
            throw ai.neargo.shop.common.BizException.of(ai.neargo.shop.common.ErrorCode.GOODS_ACTIVITY_ONLY);
        }
        TrdCartItem existing = find(skuNo);
        requireWithinLimit(snap, skuNo, (existing == null ? 0 : existing.getQty()) + Math.max(qty, 1));
        if (existing == null) {
            TrdCartItem row = new TrdCartItem();
            row.setUserNo(SecurityUtils.currentUserNo());
            row.setGoodsNo(goodsNo);
            row.setSkuNo(skuNo);
            row.setQty(Math.max(qty, 1));
            row.setSelected(true);
            cartMapper.insert(row);
        } else {
            existing.setQty(existing.getQty() + Math.max(qty, 1));
            cartMapper.updateById(existing);
        }
        return list();
    }

    @Override
    @Transactional
    public List<CartItemVO> update(String skuNo, int qty) {
        TrdCartItem row = find(skuNo);
        if (row == null) {
            return list();
        }
        if (qty <= 0) {
            cartMapper.deleteById(row.getId());   // 逻辑删除（BaseEntity 的 @TableLogic）
        } else {
            if (qty > row.getQty()) {
                // 只在加量时判：减量永远放行，否则超限的车里连减都减不下来
                requireWithinLimit(goodsPort.snapshot(List.of(skuNo)).get(skuNo), skuNo, qty);
            }
            row.setQty(qty);
            cartMapper.updateById(row);
        }
        return list();
    }

    @Override
    @Transactional
    public List<CartItemVO> remove(List<String> skuNos) {
        if (skuNos != null && !skuNos.isEmpty()) {
            cartMapper.delete(Wrappers.<TrdCartItem>lambdaQuery()
                    .eq(TrdCartItem::getUserNo, SecurityUtils.currentUserNo())
                    .in(TrdCartItem::getSkuNo, skuNos));
        }
        return list();
    }

    private List<TrdCartItem> rows() {
        return cartMapper.selectList(Wrappers.<TrdCartItem>lambdaQuery()
                .eq(TrdCartItem::getUserNo, SecurityUtils.currentUserNo())
                .orderByDesc(TrdCartItem::getId));
    }

    private TrdCartItem find(String skuNo) {
        return cartMapper.selectOne(Wrappers.<TrdCartItem>lambdaQuery()
                .eq(TrdCartItem::getUserNo, SecurityUtils.currentUserNo())
                .eq(TrdCartItem::getSkuNo, skuNo)
                .last("limit 1"));
    }
}
