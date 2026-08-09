package ai.neargo.shop.trade.service.impl;

import ai.neargo.shop.trade.service.MerchantOrderService;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.trade.dto.OrderVO;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.trade.entity.OrdStatusLog;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.TradeMappers.StatusLogMapper;
import ai.neargo.shop.trade.service.OrderStateMachine;
import org.springframework.transaction.annotation.Transactional;
import ai.neargo.shop.trade.mapper.TradeMappers.OrderItemMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.SubOrderMapper;
import ai.neargo.common.data.scope.DataScopeContext;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class MerchantOrderServiceImpl implements MerchantOrderService {

    private final SubOrderMapper subOrderMapper;
    private final OrderItemMapper itemMapper;
    private final StatusLogMapper statusLogMapper;

    public MerchantOrderServiceImpl(SubOrderMapper subOrderMapper, OrderItemMapper itemMapper,
                                    StatusLogMapper statusLogMapper) {
        this.subOrderMapper = subOrderMapper;
        this.itemMapper = itemMapper;
        this.statusLogMapper = statusLogMapper;
    }

    @Override
    public OrderVO detail(String merchantNo, String storeNo, String subOrderNo) {
        return toVO(require(merchantNo, storeNo, subOrderNo));
    }

    @Override
    @Transactional
    public OrderVO ship(String merchantNo, String storeNo, String subOrderNo, String expressNo) {
        if (expressNo == null || expressNo.isBlank()) {
            /*
             * 没有单号的「已发货」对买家没有任何用处 —— 他既查不到物流，
             * 也无法判断该不该继续等。所以这里拦住，而不是存一个空单号。
             */
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        OrdSubOrder sub = require(merchantNo, storeNo, subOrderNo);
        String no = expressNo.trim();
        /*
         * 状态机对 from==to 是**幂等友好**的（为回调重放设计），所以重复发货不会在这里被拒。
         * 于是要在这一层区分两件事：
         *   同一个单号再发一次 → 重复点击，空操作；
         *   换了一个单号     → 这是「改单号」，允许（填错单号必须能改，
         *                      拒了商家只能打客服），但**必须留痕** ——
         *                      买家那边的物流号变了却查不到是谁改的，是纠纷的开始。
         */
        boolean shipped = OrdSubOrder.FULFILLING.equals(sub.getStatus());
        if (shipped && no.equals(sub.getExpressNo())) {
            return toVO(sub);
        }
        OrderStateMachine.assertSubOrderTransit(sub.getStatus(), OrdSubOrder.FULFILLING);
        String old = sub.getExpressNo();
        sub.setStatus(OrdSubOrder.FULFILLING);
        sub.setExpressNo(no);
        save(sub);
        log(sub, OrdSubOrder.FULFILLING,
                shipped ? "商家改快递单号：" + old + " → " + no : "商家发货：" + no,
                merchantNo);
        return toVO(sub);
    }

    @Override
    @Transactional
    public OrderVO delivered(String merchantNo, String storeNo, String subOrderNo) {
        OrdSubOrder sub = require(merchantNo, storeNo, subOrderNo);
        OrderStateMachine.assertSubOrderTransit(sub.getStatus(), OrdSubOrder.COMPLETED);
        sub.setStatus(OrdSubOrder.COMPLETED);
        save(sub);
        /*
         * 留痕写「商家标记送达」而不是「已完成」——
         * 买家自己确认收货也会把单推到 COMPLETED，纠纷时要能分清是谁点的。
         */
        log(sub, OrdSubOrder.COMPLETED, "商家标记送达", merchantNo);
        return toVO(sub);
    }

    /**
     * 取一单并校验归属。
     *
     * <p><b>查不到就是 NOT_FOUND，不是 FORBIDDEN</b>：后者等于确认「这个单号是真的」，
     * 而单号可枚举 —— 那就成了一个订单探测器。
     */
    private OrdSubOrder require(String merchantNo, String storeNo, String subOrderNo) {
        OrdSubOrder sub = DataScopeContext.executeWithoutScope(() ->
                subOrderMapper.selectOne(Wrappers.<OrdSubOrder>lambdaQuery()
                        .eq(OrdSubOrder::getSubOrderNo, subOrderNo)
                        .eq(OrdSubOrder::getEntityNo, merchantNo)
                        // 门店维度再收窄：只按主体判的话，A 店店员能翻出 B 店的单
                        .eq(storeNo != null && !storeNo.isBlank(), OrdSubOrder::getStoreNo, storeNo)
                        .last("limit 1")));
        if (sub == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return sub;
    }

    private void save(OrdSubOrder sub) {
        DataScopeContext.executeWithoutScope(() -> subOrderMapper.updateById(sub));
    }

    private void log(OrdSubOrder sub, String status, String label, String operatorNo) {
        OrdStatusLog row = new OrdStatusLog();
        row.setSubOrderNo(sub.getSubOrderNo());
        row.setStatus(status);
        row.setLabel(label);
        row.setOperatorType("MERCHANT");
        row.setOperatorNo(operatorNo);
        row.setAt(System.currentTimeMillis());
        row.setTenantNo("MAIN");
        row.setCreatedAt(java.time.LocalDateTime.now());
        DataScopeContext.executeWithoutScope(() -> statusLogMapper.insert(row));
    }

    @Override
    public PageData<OrderVO> list(String merchantNo, java.util.Collection<String> storeNos,
                                  String status, long page, long size) {
        var w = Wrappers.<OrdSubOrder>lambdaQuery().eq(OrdSubOrder::getEntityNo, merchantNo);
        /*
         * 门店过滤。**结算键 entity_no 仍然保留** —— 两个键各管各的：
         * entity_no 是「这单的钱算谁的」（历史快照，门店换主体也不改），
         * store_no 是「这单在哪家店履约」。
         *
         * null = 不过滤（属主的「全部门店」，含早于多门店、store_no 为空的历史单）；
         * **空集合 = 一家都看不到**，不是「不过滤」——
         * 把空集合当成不过滤，是「没被授权的员工反而看到全部」这类越权最常见的写法。
         */
        if (storeNos != null) {
            if (storeNos.isEmpty()) {
                return PageData.of(List.of(), 0, page, size);
            }
            w.in(OrdSubOrder::getStoreNo, storeNos);
        }
        if (status != null && !status.isBlank()) {
            w.eq(OrdSubOrder::getStatus, status);
        }
        w.orderByDesc(OrdSubOrder::getId);

        /*
         * ★ **显式豁免数据域**（与 MerchantGoodsServiceImpl 同一套做法）。
         *
         * 商家用的是消费者令牌，会话的数据域维度是 SELF；而 `ord_sub_order` 上
         * SELF 锚定的是 `user_no`（买家）。不豁免的话，SQL 会追加
         * `user_no = <商家自己的 userNo>` —— 商家在订单页只看得到**他自己买过的单**，
         * 卖出去的一单都看不到，而且不报错，只是"今天没有订单"。
         *
         * 这里可以豁免，是因为归属判断已经由上面那句 `eq(entity_no, merchantNo)` 做掉了，
         * 而 merchantNo 来自 BizContext（授权边界），不是请求参数。
         */
        Page<OrdSubOrder> p = DataScopeContext.executeWithoutScope(() ->
                subOrderMapper.selectPage(Page.of(page, size), w));
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
                .map(i -> new OrderVO.ItemVO(i.getGoodsNo(), s.getEntityNo(), i.getSkuNo(),
                        i.getTitle(), i.getCover(), i.getSpec(),
                        i.getPrice() == null ? 0L : i.getPrice(),
                        i.getQty() == null ? 0 : i.getQty(),
                        i.getAmount() == null ? 0L : i.getAmount(), i.getCategoryType()))
                .toList();

        return new OrderVO(s.getSubOrderNo(), s.getOrderNo(), s.getStatus(), s.getFulfillment(),
                s.getEntityNo(), s.getEntityName(), items,
                OrderVO.Amount.of(nz(s.getGoodsAmount()), nz(s.getFreightAmount()),
                        nz(s.getDiscountAmount()), nz(s.getPayAmount()), "CNY"),
                s.getVerifyCode(), s.getPickupNo(), s.getPickupName(),
                null,
                s.getCreatedAt() == null ? 0L
                        : s.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
                null,
                // 商家也要看得到自己填了什么单号 —— 否则改单号之后无从核对
                s.getExpressNo(), s.getTrafficSource(), List.of(), null);
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }
}
