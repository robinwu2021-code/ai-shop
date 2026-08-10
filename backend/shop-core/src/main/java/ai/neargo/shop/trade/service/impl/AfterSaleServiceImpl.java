package ai.neargo.shop.trade.service.impl;

import ai.neargo.shop.trade.service.AfterSaleService;
import ai.neargo.shop.trade.service.OrderStateMachine;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.spi.trade.OrderEvents;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.trade.dto.AfterSaleVO;
import ai.neargo.shop.trade.entity.OrdAfterSale;
import ai.neargo.shop.trade.entity.OrdStatusLog;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.TradeMappers.AfterSaleMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.StatusLogMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.SubOrderMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 售后。
 *
 * <p><b>本类只有一条真正重要的规则</b>：{@link #doRefund} 里，
 * 「回退分账」永远在「退款」之前，且回退失败就**停在 REFUNDING 等重试**，绝不继续退款。
 * 钱可以晚退给用户（他会催），但不能退了之后收不回分账（没人会还）。
 */
@Service
public class AfterSaleServiceImpl implements AfterSaleService {

    /** 极速退阈值：≤ 该金额自动通过。真实阈值由 P-6.1.2 运营配置，这里是缺省。 */
    @Value("${shop.after-sale.instant-threshold-minor:10000}")
    private long instantThresholdMinor;

    private static final List<String> REASONS = List.of(
            "不想要了", "商品破损", "少发/漏发", "与描述不符", "质量问题", "临期或过期", "其他");

    private final AfterSaleMapper afterSaleMapper;
    private final SubOrderMapper subOrderMapper;
    private final StatusLogMapper statusLogMapper;
    private final SettlePort settlePort;
    private final OutboxEventBus eventBus;
    private final ObjectMapper json;

    public AfterSaleServiceImpl(AfterSaleMapper afterSaleMapper, SubOrderMapper subOrderMapper,
                                StatusLogMapper statusLogMapper, SettlePort settlePort,
                                OutboxEventBus eventBus, ObjectMapper json) {
        this.afterSaleMapper = afterSaleMapper;
        this.subOrderMapper = subOrderMapper;
        this.statusLogMapper = statusLogMapper;
        this.settlePort = settlePort;
        this.eventBus = eventBus;
        this.json = json;
    }

    @Override
    public List<String> reasons() {
        return REASONS;
    }

    // ---------------------------------------------------------------- C 端

    @Override
    @Transactional
    public AfterSaleVO apply(String subOrderNo, ApplyCommand cmd) {
        OrdSubOrder sub = ownSubOrder(subOrderNo);

        // 未支付的单没有可退的钱 —— 直接取消订单即可，不该走售后
        if (OrdSubOrder.WAIT_PAY.equals(sub.getStatus()) || OrdSubOrder.CANCELLED.equals(sub.getStatus())) {
            throw BizException.of(ErrorCode.ORDER_STATE_ILLEGAL);
        }
        // 同一子单同时只能有一个进行中的售后。用应用层判定而不是唯一索引：
        // 唯一索引做不到「只对进行中的行唯一」，而终态之后必须允许再申请
        if (activeOf(subOrderNo) != null) {
            throw BizException.of(ErrorCode.CONFLICT);
        }

        long paid = sub.getPayAmount() == null ? 0L : sub.getPayAmount();
        long refund = cmd.refundMinor() == null || cmd.refundMinor() <= 0 ? paid : cmd.refundMinor();
        if (refund > paid) {
            // 退款金额超过实付：这是最直接的资损口子，必须在入口就挡住
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }

        OrdAfterSale as = new OrdAfterSale();
        as.setAfterSaleNo(BizKey.next(BizKey.AFTER_SALE));
        as.setSubOrderNo(subOrderNo);
        as.setOrderNo(sub.getOrderNo());
        as.setUserNo(sub.getUserNo());
        as.setEntityNo(sub.getEntityNo());
        as.setType(cmd.type());
        as.setReason(cmd.reason());
        as.setImages(writeJson(cmd.images()));
        as.setRefundMinor(refund);
        as.setStatus(OrdAfterSale.APPLIED);
        as.setSplitReversed(false);

        // 极速退：仅退款且金额在阈值内 → 自动通过。退货退款要等收到货，不能自动
        boolean instant = OrdAfterSale.REFUND_ONLY.equals(cmd.type()) && refund <= instantThresholdMinor;
        as.setInstant(instant);
        afterSaleMapper.insert(as);
        appendLog(subOrderNo, OrdAfterSale.APPLIED, "已申请售后", OrdStatusLog.BY_USER, sub.getUserNo());

        if (instant) {
            doRefund(as, "极速退");
        }
        return detailOf(as);
    }

    @Override
    public List<AfterSaleVO> myList() {
        return afterSaleMapper.selectList(Wrappers.<OrdAfterSale>lambdaQuery()
                        .eq(OrdAfterSale::getUserNo, SecurityUtils.currentUserNo())
                        .orderByDesc(OrdAfterSale::getId)).stream()
                .map(this::detailOf).toList();
    }

    @Override
    public AfterSaleVO detail(String afterSaleNo) {
        return detailOf(own(afterSaleNo));
    }

    @Override
    @Transactional
    public AfterSaleVO cancel(String afterSaleNo) {
        OrdAfterSale as = own(afterSaleNo);
        OrderStateMachine.assertAfterSaleTransit(as.getStatus(), OrdAfterSale.CLOSED);
        as.setStatus(OrdAfterSale.CLOSED);
        afterSaleMapper.updateById(as);
        appendLog(as.getSubOrderNo(), OrdAfterSale.CLOSED, "用户撤销申请",
                OrdStatusLog.BY_USER, as.getUserNo());
        return detailOf(as);
    }

    @Override
    @Transactional
    public AfterSaleVO shipBack(String afterSaleNo, String company, String expressNo) {
        OrdAfterSale as = own(afterSaleNo);
        as.setExpressCompany(company);
        as.setExpressNo(expressNo);
        afterSaleMapper.updateById(as);
        appendLog(as.getSubOrderNo(), as.getStatus(), "买家已寄回：" + expressNo,
                OrdStatusLog.BY_USER, as.getUserNo());
        return detailOf(as);
    }

    @Override
    @Transactional
    public AfterSaleVO escalate(String afterSaleNo, String appeal) {
        OrdAfterSale as = own(afterSaleNo);
        OrderStateMachine.assertAfterSaleTransit(as.getStatus(), OrdAfterSale.ARBITRATING);
        as.setStatus(OrdAfterSale.ARBITRATING);
        afterSaleMapper.updateById(as);
        appendLog(as.getSubOrderNo(), OrdAfterSale.ARBITRATING, "已申请平台介入：" + appeal,
                OrdStatusLog.BY_USER, as.getUserNo());
        return detailOf(as);
    }

    // ---------------------------------------------------------------- B 端

    @Override
    public List<AfterSaleVO> merchantList(String merchantNo, String status) {
        var w = Wrappers.<OrdAfterSale>lambdaQuery().eq(OrdAfterSale::getEntityNo, merchantNo);
        if (status != null && !status.isBlank()) {
            w.eq(OrdAfterSale::getStatus, status);
        }
        w.orderByDesc(OrdAfterSale::getId);
        return DataScopeContext.executeWithoutScope(() -> afterSaleMapper.selectList(w))
                .stream().map(this::detailOf).toList();
    }

    @Override
    @Transactional
    public AfterSaleVO approve(String merchantNo, String afterSaleNo) {
        OrdAfterSale as = ofMerchant(merchantNo, afterSaleNo);

        if (OrdAfterSale.RETURN_REFUND.equals(as.getType())) {
            // 退货退款：同意 ≠ 立刻退钱，要等收到货。这里只推进到「等待寄回」
            OrderStateMachine.assertAfterSaleTransit(as.getStatus(), OrdAfterSale.REFUNDING);
            as.setStatus(OrdAfterSale.REFUNDING);
            update(as);
            appendLog(as.getSubOrderNo(), OrdAfterSale.REFUNDING, "商家已同意，待买家寄回",
                    OrdStatusLog.BY_MERCHANT, merchantNo);
            return detailOf(as);
        }
        doRefund(as, "商家同意退款");
        return detailOf(as);
    }

    @Override
    @Transactional
    public AfterSaleVO reject(String merchantNo, String afterSaleNo, String remark) {
        OrdAfterSale as = ofMerchant(merchantNo, afterSaleNo);
        // 极速退已经是终态，商家只可见不可拒 —— 状态机在这里挡住
        OrderStateMachine.assertAfterSaleTransit(as.getStatus(), OrdAfterSale.REJECTED);
        as.setStatus(OrdAfterSale.REJECTED);
        as.setMerchantRemark(remark);
        update(as);
        appendLog(as.getSubOrderNo(), OrdAfterSale.REJECTED, "商家驳回：" + remark,
                OrdStatusLog.BY_MERCHANT, merchantNo);
        return detailOf(as);
    }

    @Override
    @Transactional
    public AfterSaleVO confirmReturn(String merchantNo, String afterSaleNo) {
        OrdAfterSale as = ofMerchant(merchantNo, afterSaleNo);
        doRefund(as, "商家已收到退货");
        return detailOf(as);
    }

    // ---------------------------------------------------------------- 退款（唯一入口）

    /**
     * <b>退款的唯一实现</b>。顺序是这里唯一重要的东西：
     * <ol>
     *   <li>① 回退分账 —— 失败就停在 REFUNDING 等重试，<b>绝不往下走</b></li>
     *   <li>② 调支付退款</li>
     *   <li>③ 子单转 REFUNDED，发事件（下游据此回补库存、调整评分）</li>
     * </ol>
     * 反过来做的话，钱退了但分账收不回，商家已提现的部分只能人工追。
     */
    private void doRefund(OrdAfterSale as, String label) {
        if (OrdAfterSale.REFUNDED.equals(as.getStatus())) {
            return;   // 幂等：重复点「同意」不会退两次
        }
        if (!OrdAfterSale.REFUNDING.equals(as.getStatus())) {
            OrderStateMachine.assertAfterSaleTransit(as.getStatus(), OrdAfterSale.REFUNDING);
            as.setStatus(OrdAfterSale.REFUNDING);
            update(as);
        }

        // ① 先回退分账
        if (!settlePort.reverseSplit(as.getSubOrderNo())) {
            appendLog(as.getSubOrderNo(), OrdAfterSale.REFUNDING, "分账回退失败，待重试",
                    OrdStatusLog.BY_SYSTEM, null);
            throw BizException.of(ErrorCode.SPLIT_EXPIRED);
        }
        as.setSplitReversed(true);

        // ② 再退款
        settlePort.refund(as.getSubOrderNo(), as.getRefundMinor(), as.getReason());

        // ③ 落终态
        as.setStatus(OrdAfterSale.REFUNDED);
        as.setRefundedAt(System.currentTimeMillis());
        update(as);

        OrdSubOrder sub = subOrderOf(as.getSubOrderNo());
        if (sub != null && OrderStateMachine.canTransit(OrderStateMachine.subOrderGraph(),
                sub.getStatus(), OrdSubOrder.REFUNDED)) {
            sub.setStatus(OrdSubOrder.REFUNDED);
            DataScopeContext.executeWithoutScope(() -> subOrderMapper.updateById(sub));
        }
        appendLog(as.getSubOrderNo(), OrdAfterSale.REFUNDED, label, OrdStatusLog.BY_SYSTEM, null);
        eventBus.publish(new OrderEvents.AfterSaleRefunded(as.getAfterSaleNo(), as.getSubOrderNo(),
                as.getUserNo(), as.getRefundMinor()));
    }

    // ---------------------------------------------------------------- 装配

    private OrdAfterSale own(String afterSaleNo) {
        OrdAfterSale as = afterSaleMapper.selectOne(Wrappers.<OrdAfterSale>lambdaQuery()
                .eq(OrdAfterSale::getAfterSaleNo, afterSaleNo)
                .eq(OrdAfterSale::getUserNo, SecurityUtils.currentUserNo())
                .last("limit 1"));
        if (as == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return as;
    }

    /** 商家侧：属主是 merchantNo。查不到即 404 —— 不区分「不存在」与「不是你的」。 */
    private OrdAfterSale ofMerchant(String merchantNo, String afterSaleNo) {
        OrdAfterSale as = DataScopeContext.executeWithoutScope(() ->
                afterSaleMapper.selectOne(Wrappers.<OrdAfterSale>lambdaQuery()
                        .eq(OrdAfterSale::getAfterSaleNo, afterSaleNo)
                        .eq(OrdAfterSale::getEntityNo, merchantNo)
                        .last("limit 1")));
        if (as == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return as;
    }

    private OrdAfterSale activeOf(String subOrderNo) {
        return DataScopeContext.executeWithoutScope(() ->
                afterSaleMapper.selectOne(Wrappers.<OrdAfterSale>lambdaQuery()
                        .eq(OrdAfterSale::getSubOrderNo, subOrderNo)
                        .notIn(OrdAfterSale::getStatus,
                                List.of(OrdAfterSale.CLOSED, OrdAfterSale.REJECTED, OrdAfterSale.REFUNDED))
                        .last("limit 1")));
    }

    private OrdSubOrder ownSubOrder(String subOrderNo) {
        OrdSubOrder sub = subOrderMapper.selectOne(Wrappers.<OrdSubOrder>lambdaQuery()
                .eq(OrdSubOrder::getSubOrderNo, subOrderNo)
                .eq(OrdSubOrder::getUserNo, SecurityUtils.currentUserNo())
                .last("limit 1"));
        if (sub == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return sub;
    }

    private OrdSubOrder subOrderOf(String subOrderNo) {
        return DataScopeContext.executeWithoutScope(() ->
                subOrderMapper.selectOne(Wrappers.<OrdSubOrder>lambdaQuery()
                        .eq(OrdSubOrder::getSubOrderNo, subOrderNo).last("limit 1")));
    }

    /** 商家侧写入同样要豁免：售后单的属主是买家，商家改它天然跨属主（R11 的同类）。 */
    private void update(OrdAfterSale as) {
        DataScopeContext.executeWithoutScope(() -> afterSaleMapper.updateById(as));
    }

    /** 售后自己的状态集合：时间线按**属于哪个聚合**过滤，不按时间窗口切。 */
    private static final List<String> AFTER_SALE_STATUSES = List.of(
            OrdAfterSale.APPLIED, OrdAfterSale.REFUNDING, OrdAfterSale.REFUNDED,
            OrdAfterSale.REJECTED, OrdAfterSale.ARBITRATING, OrdAfterSale.CLOSED);

    private AfterSaleVO detailOf(OrdAfterSale as) {
        // 订单与售后共用 ord_status_log。早先按「at >= 售后创建时间」切分，
        // 但两者的时间戳精度与写入顺序都不保证 —— 时间窗口是猜，状态集合是判定。
        List<AfterSaleVO.TimelineNode> timeline = statusLogMapper.selectList(
                        Wrappers.<OrdStatusLog>lambdaQuery()
                                .eq(OrdStatusLog::getSubOrderNo, as.getSubOrderNo())
                                .in(OrdStatusLog::getStatus, AFTER_SALE_STATUSES)
                                .orderByAsc(OrdStatusLog::getId)).stream()
                .map(l -> new AfterSaleVO.TimelineNode(l.getStatus(), l.getLabel(),
                        l.getAt() == null ? 0L : l.getAt()))
                .toList();

        return new AfterSaleVO(as.getAfterSaleNo(), as.getSubOrderNo(), as.getOrderNo(),
                as.getType(), as.getStatus(), as.getReason(), readList(as.getImages()),
                as.getRefundMinor() == null ? 0L : as.getRefundMinor(),
                Boolean.TRUE.equals(as.getInstant()), as.getMerchantRemark(),
                as.getExpressNo(), as.getLiability(), millis(as.getCreatedAt()), timeline);
    }

    private void appendLog(String subOrderNo, String status, String label,
                           String operatorType, String operatorNo) {
        OrdStatusLog log = new OrdStatusLog();
        log.setSubOrderNo(subOrderNo);
        log.setStatus(status);
        log.setLabel(label);
        log.setOperatorType(operatorType);
        log.setOperatorNo(operatorNo);
        log.setAt(System.currentTimeMillis());
        log.setTenantNo("MAIN");
        log.setCreatedAt(LocalDateTime.now());
        statusLogMapper.insert(log);
    }

    private String writeJson(List<String> images) {
        try {
            return json.writeValueAsString(images == null ? List.of() : images);
        } catch (Exception e) {
            return "[]";
        }
    }

    private List<String> readList(String jsonArray) {
        if (jsonArray == null || jsonArray.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(jsonArray, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    private static long millis(LocalDateTime t) {
        return t == null ? 0L : t.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    @Override
    public int merchantPendingCount(String merchantNo) {
        Long n = DataScopeContext.executeWithoutScope(() ->
                afterSaleMapper.selectCount(Wrappers.<OrdAfterSale>lambdaQuery()
                        .eq(OrdAfterSale::getEntityNo, merchantNo)
                        .eq(OrdAfterSale::getStatus, OrdAfterSale.APPLIED)));
        return n == null ? 0 : n.intValue();
    }
}
