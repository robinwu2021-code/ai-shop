package ai.neargo.shop.trade.service.impl;

import ai.neargo.shop.trade.service.PlatformOrderService;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.trade.dto.OrderVO;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.TradeMappers.SubOrderMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class PlatformOrderServiceImpl implements PlatformOrderService {

    private final SubOrderMapper subOrderMapper;

    public PlatformOrderServiceImpl(SubOrderMapper subOrderMapper) {
        this.subOrderMapper = subOrderMapper;
    }

    @Override
    public PageData<OrderVO> search(String status, long page, long size) {
        var w = Wrappers.<OrdSubOrder>lambdaQuery();
        if (status != null && !status.isBlank()) {
            w.eq(OrdSubOrder::getStatus, status);
        }
        w.orderByDesc(OrdSubOrder::getId);

        // 平台视角看全量：数据域授权在登录时已给 ALL，这里显式豁免是为了不依赖那个假设
        Page<OrdSubOrder> p = DataScopeContext.executeWithoutScope(
                () -> subOrderMapper.selectPage(Page.of(page, size), w));

        List<OrderVO> records = p.getRecords().stream()
                .map(s -> new OrderVO(s.getSubOrderNo(), s.getOrderNo(), s.getStatus(),
                        s.getFulfillment(), s.getEntityNo(), s.getEntityName(),
                        List.of(),
                        OrderVO.Amount.of(nz(s.getGoodsAmount()), nz(s.getFreightAmount()),
                                nz(s.getDiscountAmount()), nz(s.getPayAmount()), "CNY"),
                        s.getVerifyCode(), s.getPickupNo(), s.getPickupName(),
                        // 平台侧也要看得到快递单号：客服处理「货到哪了」全靠它
                        null, 0L, null, s.getExpressNo(), s.getTrafficSource(), List.of(), null))
                .toList();
        return PageData.of(records, p.getTotal(), p.getCurrent(), p.getSize());
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }
}
