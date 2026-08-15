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

        /*
         * **走数据域**（2026-08-14，运营端数据域接入 批①）。
         *
         * 这里原先是 `executeWithoutScope`，理由写的是「平台视角看全量」——
         * 而那句话只对没配数据域的账号成立。配了「只看某商家 / 某片区」的运营，
         * 他的 `DataScopeSpec` 一路带到这里就被丢掉了：
         * 配置页显示「已限定」，他照样看到全平台的单。
         *
         * 没配数据域的账号仍然是 `DataScopeSpec.ALL`（空 = 不限定），
         * 超管恒 ALL —— 所以对存量账号零变化。
         */
        Page<OrdSubOrder> p = subOrderMapper.selectPage(Page.of(page, size), w);

        List<OrderVO> records = p.getRecords().stream()
                .map(s -> new OrderVO(s.getSubOrderNo(), s.getOrderNo(), s.getStatus(),
                        s.getFulfillment(), s.getEntityNo(), s.getEntityName(),
                        List.of(),
                        OrderVO.Amount.of(nz(s.getGoodsAmount()), nz(s.getFreightAmount()),
                                nz(s.getDiscountAmount()), nz(s.getPayAmount()), "CNY"),
                        s.getVerifyCode(), s.getPickupNo(), s.getPickupName(),
                        // 平台侧也要看得到快递单号：客服处理「货到哪了」全靠它
                        // 收件人先不下发：平台端列表是「查单」不是「送货」，
                        // 真要给也该是另一档口径，别顺着商家那套走
                        null, 0L, null, s.getExpressNo(), s.getTrafficSource(), null, List.of(), null,
                        // 买家昵称：平台端列表是「查单」，认人靠订单号与手机号尾号
                        null))
                .toList();
        return PageData.of(records, p.getTotal(), p.getCurrent(), p.getSize());
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }
}
