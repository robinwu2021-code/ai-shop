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

    @Override
    public List<CartItemVO> list() {
        List<TrdCartItem> rows = rows();
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<String, GoodsQueryPort.SkuSnapshot> snapshots =
                goodsPort.snapshot(rows.stream().map(TrdCartItem::getSkuNo).toList());

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
                    !s.onSale(), s.available());
        }).toList();
    }

    @Override
    @Transactional
    public List<CartItemVO> add(String goodsNo, String skuNo, int qty) {
        TrdCartItem existing = find(skuNo);
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
