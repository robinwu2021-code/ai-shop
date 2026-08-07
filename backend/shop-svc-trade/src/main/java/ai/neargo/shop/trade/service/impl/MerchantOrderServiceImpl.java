package ai.neargo.shop.trade.service.impl;

import ai.neargo.shop.trade.service.MerchantOrderService;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.trade.dto.OrderVO;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.TradeMappers.OrderItemMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.SubOrderMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class MerchantOrderServiceImpl implements MerchantOrderService {

    private final SubOrderMapper subOrderMapper;
    private final OrderItemMapper itemMapper;

    public MerchantOrderServiceImpl(SubOrderMapper subOrderMapper, OrderItemMapper itemMapper) {
        this.subOrderMapper = subOrderMapper;
        this.itemMapper = itemMapper;
    }

    @Override
    public PageData<OrderVO> list(String merchantNo, String status, long page, long size) {
        var w = Wrappers.<OrdSubOrder>lambdaQuery().eq(OrdSubOrder::getMerchantNo, merchantNo);
        if (status != null && !status.isBlank()) {
            w.eq(OrdSubOrder::getStatus, status);
        }
        w.orderByDesc(OrdSubOrder::getId);

        Page<OrdSubOrder> p = subOrderMapper.selectPage(Page.of(page, size), w);
        List<OrderVO> records = p.getRecords().stream().map(this::toVO).toList();
        return PageData.of(records, p.getTotal(), p.getCurrent(), p.getSize());
    }

    /**
     * 商家视角：**有金额**（这是他自己的钱），但**没有买家完整手机号** ——
     * 需要联系买家走平台客服通道，而不是把号码散出去（M11/B12）。
     */
    private OrderVO toVO(OrdSubOrder s) {
        List<OrderVO.ItemVO> items = itemMapper.selectList(Wrappers.<OrdItem>lambdaQuery()
                        .eq(OrdItem::getSubOrderNo, s.getSubOrderNo())).stream()
                .map(i -> new OrderVO.ItemVO(i.getGoodsNo(), s.getMerchantNo(), i.getSkuNo(),
                        i.getTitle(), i.getCover(), i.getSpec(),
                        i.getPrice() == null ? 0L : i.getPrice(),
                        i.getQty() == null ? 0 : i.getQty(),
                        i.getAmount() == null ? 0L : i.getAmount(), i.getCategoryType()))
                .toList();

        return new OrderVO(s.getSubOrderNo(), s.getOrderNo(), s.getStatus(), s.getFulfillment(),
                s.getMerchantNo(), s.getMerchantName(), items,
                OrderVO.Amount.of(nz(s.getGoodsAmount()), nz(s.getFreightAmount()),
                        nz(s.getDiscountAmount()), nz(s.getPayAmount()), "CNY"),
                s.getVerifyCode(), s.getPickupNo(), s.getPickupName(),
                null,
                s.getCreatedAt() == null ? 0L
                        : s.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
                null, s.getTrafficSource(), List.of(), null);
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }
}
