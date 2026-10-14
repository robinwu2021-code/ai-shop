package ai.neargo.shop.pay.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.pay.entity.StlPayment;
import ai.neargo.shop.pay.mapper.SettleMappers;
import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.spi.settle.SettlePort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.util.Objects;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;

@Service
public class PaymentLedgerServiceImpl implements PaymentLedgerService {

    private static final Logger log = LoggerFactory.getLogger(PaymentLedgerServiceImpl.class);

    private final SettleMappers.PaymentMapper paymentMapper;

    public PaymentLedgerServiceImpl(SettleMappers.PaymentMapper paymentMapper) {
        this.paymentMapper = paymentMapper;
    }

    @Override
    @Transactional("payTxManager")
    public String open(SettlePort.PaymentOpen cmd) {
        /*
         * **幂等的粒度是「这个订单有没有未终态的收款」**，不是「这个单号落过没有」。
         *
         * 用户在收银台点两次「去支付」应当复用同一笔 ——
         * 否则通道那边多出一个未支付单，而对账会把它当成掉单。
         * 而前一笔失败或关闭之后重试，走的是**新的 out_trade_no**：
         * 通道要求商户订单号唯一，关掉的号不能复用。
         */
        StlPayment open = openPaymentOf(cmd.orderNo());
        if (open != null) {
            return open.getOutTradeNo();
        }
        /*
         * **商户单号 = 订单号 + 尝试序号**（第一次就是订单号本身）。
         *
         * 为什么要能变：通道要求商户订单号唯一，而一笔单**关掉之后重试必须换新号** ——
         * 用订单号当商户单号且永不变的话，那笔订单再也下不了第二次单，
         * 症状是「点了没反应」，而查订单状态一切正常。
         *
         * 为什么第一次仍用订单号本身，而不是完全独立生成：
         * 独立生成会让「回调里拿到的号」与订单号彻底脱钩，
         * 而整条链路上有很多处默认两者相等。加后缀既拿到了「可以换号」，
         * 又让最常见的那条路径一个字都不用改 —— 这是**先量了影响面才改的**：
         * 完全独立那版让 20 多个测试类同时变红，而它们测的是业务链路，
         * 不是支付细节。
         *
         * 后缀从 -2 起：`ORD123`、`ORD123-2`、`ORD123-3`……
         * 用户报障时报的仍是自己看得到的订单号，客服按前缀就能找全这几次尝试。
         */
        long attempts = countPayAttempts(cmd.orderNo());
        String outTradeNo = attempts == 0 ? cmd.orderNo() : cmd.orderNo() + "-" + (attempts + 1);
        StlPayment p = new StlPayment();
        p.setPaymentNo(BizKey.next(BizKey.PAYMENT));
        p.setDirection(StlPayment.PAY);
        p.setStatus(StlPayment.PENDING);
        p.setOutTradeNo(outTradeNo);
        p.setOrderNo(cmd.orderNo());
        p.setUserNo(cmd.userNo());
        p.setEntityNo(cmd.entityNo());
        p.setPayChannel(cmd.payChannel());
        p.setAmountMinor(cmd.amountMinor());
        DataScopeContext.executeWithoutScope(() -> paymentMapper.insert(p));
        return outTradeNo;
    }

    /** 这个订单已经向通道下过几次单 —— 决定下一个商户单号的后缀 */
    private long countPayAttempts(String orderNo) {
        return DataScopeContext.executeWithoutScope(() -> paymentMapper.selectCount(
                Wrappers.<StlPayment>lambdaQuery()
                        .eq(StlPayment::getDirection, StlPayment.PAY)
                        .eq(StlPayment::getOrderNo, orderNo)));
    }

    @Override
    public java.util.Optional<PaidPayment> paidPayment(String orderNo) {
        StlPayment p = DataScopeContext.executeWithoutScope(() -> paymentMapper.selectOne(
                Wrappers.<StlPayment>lambdaQuery()
                        .eq(StlPayment::getDirection, StlPayment.PAY)
                        .eq(StlPayment::getOrderNo, orderNo)
                        .eq(StlPayment::getStatus, StlPayment.SUCCESS)
                        .orderByDesc(StlPayment::getId)
                        .last("LIMIT 1")));
        return p == null
                ? java.util.Optional.empty()
                : java.util.Optional.of(new PaidPayment(
                        p.getOutTradeNo(), p.getPayerOpenid(), p.getWxAppid()));
    }

    /** 这个订单未终态（INIT / PENDING）的收款 —— 有就复用，没有才开新的 */
    private StlPayment openPaymentOf(String orderNo) {
        return DataScopeContext.executeWithoutScope(() -> paymentMapper.selectOne(
                Wrappers.<StlPayment>lambdaQuery()
                        .eq(StlPayment::getDirection, StlPayment.PAY)
                        .eq(StlPayment::getOrderNo, orderNo)
                        .in(StlPayment::getStatus, StlPayment.INIT, StlPayment.PENDING)
                        .orderByDesc(StlPayment::getId)
                        .last("LIMIT 1")));
    }

    @Override
    @Transactional("payTxManager")
    public String refund(String orderNo, String subOrderNo, String afterSaleNo,
                         long amountMinor, String reason) {
        /*
         * **幂等按售后单号。**重试是常态 —— 分账回退失败会让整笔退款停在
         * REFUNDING 等续跑任务再来一次。不幂等的话每重试一次多一行退款流水，
         * 而对账会把它们当成「多退了几笔」。
         */
        StlPayment exist = DataScopeContext.executeWithoutScope(() -> paymentMapper.selectOne(
                Wrappers.<StlPayment>lambdaQuery()
                        .eq(StlPayment::getDirection, StlPayment.REFUND)
                        .eq(StlPayment::getAfterSaleNo, afterSaleNo)
                        .last("LIMIT 1")));
        if (exist != null) {
            return exist.getPaymentNo();
        }

        /*
         * **挂在原收款上。**没有原收款就退不了 —— 那说明这笔单的钱从没收到过，
         * 而给一笔没收到钱的单退款是真通道一定会拒的。
         * 返回 null 让调用方知道，而不是落一行无主的退款流水。
         */
        StlPayment origin = DataScopeContext.executeWithoutScope(() -> paymentMapper.selectOne(
                Wrappers.<StlPayment>lambdaQuery()
                        .eq(StlPayment::getDirection, StlPayment.PAY)
                        .eq(StlPayment::getOrderNo, orderNo)
                        .eq(StlPayment::getStatus, StlPayment.SUCCESS)
                        .orderByDesc(StlPayment::getId).last("LIMIT 1")));
        if (origin == null) {
            log.warn("[payment-ledger] 订单 {} 没有成功的收款流水，退款无从挂靠（售后 {}）",
                    orderNo, afterSaleNo);
            return null;
        }

        // 商户单号 = 原单号-R序号。客服按前缀能把一笔单的收与退一次找全
        long times = DataScopeContext.executeWithoutScope(() -> paymentMapper.selectCount(
                Wrappers.<StlPayment>lambdaQuery()
                        .eq(StlPayment::getDirection, StlPayment.REFUND)
                        .eq(StlPayment::getOrderNo, orderNo)));

        StlPayment r = new StlPayment();
        r.setPaymentNo(BizKey.next(BizKey.PAYMENT));
        r.setDirection(StlPayment.REFUND);
        /*
         * **落成 PENDING，不是 SUCCESS。**通道退款是异步的，
         * 回执到了才算成功。直接写成功的话，通道拒单时账上显示退了而钱没退，
         * 而这种差异只有用户来投诉才会被发现。
         */
        r.setStatus(StlPayment.PENDING);
        r.setOutTradeNo(origin.getOutTradeNo() + "-R" + (times + 1));
        r.setOrderNo(orderNo);
        r.setSubOrderNo(subOrderNo);
        r.setAfterSaleNo(afterSaleNo);
        r.setUserNo(origin.getUserNo());
        r.setEntityNo(origin.getEntityNo());
        r.setPayChannel(origin.getPayChannel());
        r.setCurrency(origin.getCurrency());
        r.setAmountMinor(amountMinor);
        r.setErrMsg(reason);
        DataScopeContext.executeWithoutScope(() -> paymentMapper.insert(r));
        log.info("[payment-ledger] 退款流水 {} 已落（原收款 {}，{} 分）",
                r.getPaymentNo(), origin.getOutTradeNo(), amountMinor);
        return r.getPaymentNo();
    }

    @Override
    public java.util.Optional<RefundTicket> refundTicket(String refundPaymentNo) {
        StlPayment r = byPaymentNo(refundPaymentNo);
        if (r == null || !StlPayment.REFUND.equals(r.getDirection())) {
            return java.util.Optional.empty();
        }
        /*
         * 原收款按**订单号 + 成功**去找，与 refund() 落账时用的是同一个判据 ——
         * 两处不一致的话，退款会挂在 A 而发给通道的坐标来自 B。
         */
        StlPayment origin = DataScopeContext.executeWithoutScope(() -> paymentMapper.selectOne(
                Wrappers.<StlPayment>lambdaQuery()
                        .eq(StlPayment::getDirection, StlPayment.PAY)
                        .eq(StlPayment::getOrderNo, r.getOrderNo())
                        .eq(StlPayment::getStatus, StlPayment.SUCCESS)
                        .orderByDesc(StlPayment::getId).last("LIMIT 1")));
        if (origin == null) {
            // 落账那一步已经查过一次，走到这里说明中间被改动过
            log.warn("[payment-ledger] 退款 {} 的原收款不见了，不发通道", refundPaymentNo);
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new RefundTicket(
                origin.getPayChannel(), origin.getTradeNo(), origin.getOutTradeNo(),
                r.getOutTradeNo(), nz(origin.getAmountMinor()), nz(r.getAmountMinor()),
                r.getErrMsg()));
    }

    @Override
    @Transactional("payTxManager")
    public void markRefundSent(String refundPaymentNo, boolean accepted, boolean retryable,
                               String providerNo, String reason) {
        StlPayment r = byPaymentNo(refundPaymentNo);
        if (r == null) {
            log.warn("[payment-ledger] 回填退款结果时找不到流水 {}", refundPaymentNo);
            return;
        }
        StlPayment patch = new StlPayment();
        patch.setId(r.getId());
        if (accepted) {
            /*
             * **仍是 PENDING。**受理不等于钱退出去了 —— 微信返回 PROCESSING 是常态。
             * 改成 SUCCESS 的话账上写着退了而钱还在路上，
             * 通道最终拒单时这个差异只有用户投诉才会被发现。
             * 确认交给对账轴（它按 outTradeNo 调 queryRefund）。
             */
            patch.setTradeNo(providerNo);
            /*
             * **不动 err_msg。**这一列在退款行上存的是<b>退款原因</b>
             * （落账时写进去的，refundTicket 还要读它发给通道），不是错误。
             * 而且 updateById 本来就跳过 null 字段 —— 写 setErrMsg(null)
             * 既清不掉，又会让读代码的人以为清掉了。
             */
            log.info("[payment-ledger] 退款 {} 已被通道受理，通道单号 {}（仍待确认）",
                    refundPaymentNo, providerNo);
        } else if (retryable) {
            /*
             * **留 PENDING。**超时的时候「到底发出去没有」是真的不知道，
             * 转 FAILED 等于替通道回答了一个我方答不了的问题。
             */
            patch.setErrMsg(reason);
            log.warn("[payment-ledger] 退款 {} 发送失败但可重试，留待回查：{}", refundPaymentNo, reason);
        } else {
            /*
             * **转 FAILED。**留 PENDING 更糟：对账轴回查会得到「通道没有这笔」，
             * 而那正是它用来安全关单的判据 —— 一笔该退的钱会被静默关掉。
             * FAILED 是「没退出去，且重试没用」，要人工介入。
             */
            patch.setStatus(StlPayment.FAILED);
            patch.setErrMsg(reason);
            patch.setClosedAt(System.currentTimeMillis());
            log.error("[payment-ledger] 退款 {} 被通道拒绝，钱没有退给用户，需人工处理：{}",
                    refundPaymentNo, reason);
        }
        DataScopeContext.executeWithoutScope(() -> paymentMapper.updateById(patch));
    }

    @Override
    @Transactional("payTxManager")
    public void markRefundSettled(String refundPaymentNo, String providerNo) {
        StlPayment r = byPaymentNo(refundPaymentNo);
        if (r == null || !StlPayment.REFUND.equals(r.getDirection())) {
            log.warn("[payment-ledger] 确认退款到账时找不到退款流水 {}", refundPaymentNo);
            return;
        }
        if (StlPayment.SUCCESS.equals(r.getStatus())) {
            // **幂等**：不覆盖已有的成功时刻，否则对账查到的时刻会一轮一轮往后跳
            return;
        }
        StlPayment patch = new StlPayment();
        patch.setId(r.getId());
        patch.setStatus(StlPayment.SUCCESS);
        patch.setSucceededAt(System.currentTimeMillis());
        /*
         * **不写 trade_no。**它在受理那一刻就由 markRefundSent 记下了
         * （通道自己回的 refund_id），那个才是权威；回查只负责确认「退成功了没」。
         *
         * 覆盖它有实打实的代价：`stl_payment` 上有
         * <b>UNIQUE (pay_channel, trade_no)</b>，而这个索引是<b>跨方向</b>的 ——
         * 回查一旦给回一个别的行已经占着的号，插不进去的不是这一行，
         * 是<b>整轮自查当场抛异常中断</b>，后面几百笔一笔都不查了。
         * 用一个不带新信息的写，去换一条能炸掉整轮扫描的路径，不划算。
         */
        DataScopeContext.executeWithoutScope(() -> paymentMapper.updateById(patch));
        log.info("[payment-ledger] 退款 {} 已确认到账（通道单号 {}）", refundPaymentNo, providerNo);
    }

    @Override
    @Transactional("payTxManager")
    public void recordPayer(String outTradeNo, String payerId, String appId) {
        if (payerId == null || payerId.isBlank()) {
            // 没有就不写。写一行空的等于把「没取到」和「没这个概念」混成一件事
            return;
        }
        StlPayment p = byOutTradeNo(outTradeNo);
        if (p == null) {
            log.warn("[payment-ledger] 记付款人时找不到流水 {}", outTradeNo);
            return;
        }
        StlPayment patch = new StlPayment();
        patch.setId(p.getId());
        patch.setPayerOpenid(payerId);
        if (appId != null && !appId.isBlank()) {
            patch.setWxAppid(appId);
        }
        DataScopeContext.executeWithoutScope(() -> paymentMapper.updateById(patch));
    }

    @Override
    @Transactional("payTxManager")
    public String settleRefundByOutTradeNo(String outRefundNo, String providerNo) {
        StlPayment r = byOutTradeNo(outRefundNo, StlPayment.REFUND);
        if (r == null || !StlPayment.REFUND.equals(r.getDirection())) {
            /*
             * **认领不了就说认领不了。** 通道回传了一个我方没发出去过的退款号，
             * 当成功的话账上会多出一笔从没发生过的退款；
             * 而真出现这种情况，要么是串了商户号，要么是有人在伪造回调。
             */
            log.error("[payment-ledger] 收到无法认领的退款回调 out_refund_no={}", outRefundNo);
            return null;
        }
        markRefundSettled(r.getPaymentNo(), providerNo);
        return r.getOrderNo();
    }

    private StlPayment byPaymentNo(String paymentNo) {
        if (paymentNo == null) {
            return null;
        }
        return DataScopeContext.executeWithoutScope(() -> paymentMapper.selectOne(
                Wrappers.<StlPayment>lambdaQuery()
                        .eq(StlPayment::getPaymentNo, paymentNo).last("LIMIT 1")));
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }

    @Override
    @Transactional(value = "payTxManager", propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED)
    public void close(String outTradeNo, String reason) {
        StlPayment p = lockPayment(outTradeNo);
        if (p == null || StlPayment.SUCCESS.equals(p.getStatus())
                || StlPayment.CLOSED.equals(p.getStatus())) {
            /*
             * **已成功的绝不关。**下单失败与「钱已经收到」在时间上可以交错：
             * 通道返回超时而实际下单成功、用户付了钱、回调先到 —— 这时再关单
             * 就是把一笔收到的钱标成关闭，而对账会把它算成掉单。
             */
            return;
        }
        StlPayment patch = new StlPayment();
        patch.setId(p.getId());
        patch.setStatus(StlPayment.CLOSED);
        patch.setClosedAt(System.currentTimeMillis());
        patch.setErrMsg(reason == null ? "下单失败" : reason);
        DataScopeContext.executeWithoutScope(() -> paymentMapper.updateById(patch));
        log.info("[payment-ledger] 关掉未终态收款 {}：{}", outTradeNo, reason);
    }

    @Override
    @Transactional("payTxManager")
    public String settle(SettlePort.PaymentSettled cmd) {
        StlPayment existing = lockPayment(cmd.outTradeNo());
        if (existing == null || !Objects.equals(existing.getPayChannel(), cmd.payChannel())
                || cmd.tradeNo() == null || cmd.tradeNo().isBlank() || cmd.amountMinor() < 0
                || cmd.currency() == null || cmd.currency().isBlank()) {
            return null;
        }
        if (StlPayment.SUCCESS.equals(existing.getStatus())) {
            return Objects.equals(existing.getTradeNo(), cmd.tradeNo())
                    && nz(existing.getAmountMinor()) == cmd.amountMinor()
                    && Objects.equals(existing.getCurrency(), cmd.currency()) ? existing.getOrderNo() : null;
        }
        StlPayment patch = new StlPayment();
        patch.setId(existing.getId());
        patch.setStatus(StlPayment.SUCCESS);
        patch.setTradeNo(cmd.tradeNo());
        patch.setSucceededAt(cmd.succeededAt());
        // Preserve the channel's actual money. Order projection independently compares its immutable amount.
        patch.setAmountMinor(cmd.amountMinor());
        patch.setCurrency(cmd.currency());
        DataScopeContext.executeWithoutScope(() -> paymentMapper.updateById(patch));
        return existing.getOrderNo();
    }

    @Override
    public SettlePort.PaymentReceipt successPayment(String orderNo) {
        StlPayment p = DataScopeContext.executeWithoutScope(() -> paymentMapper.selectOne(
                Wrappers.<StlPayment>lambdaQuery().eq(StlPayment::getDirection, StlPayment.PAY)
                        .eq(StlPayment::getOrderNo, orderNo).eq(StlPayment::getStatus, StlPayment.SUCCESS)
                        .orderByDesc(StlPayment::getId).last("LIMIT 1")));
        return receipt(p);
    }

    @Override
    public SettlePort.PaymentReceipt paymentByOutTradeNo(String outTradeNo) {
        return receipt(byOutTradeNo(outTradeNo));
    }

    private SettlePort.PaymentReceipt receipt(StlPayment p) {
        return p == null ? null : new SettlePort.PaymentReceipt(p.getPaymentNo(), p.getOrderNo(),
                p.getUserNo(), p.getPayChannel(), p.getTradeNo(), nz(p.getAmountMinor()),
                p.getCurrency(), p.getStatus());
    }

    private StlPayment lockPayment(String outTradeNo) {
        return DataScopeContext.executeWithoutScope(() -> paymentMapper.selectOne(
                Wrappers.<StlPayment>lambdaQuery().eq(StlPayment::getDirection, StlPayment.PAY)
                        .eq(StlPayment::getOutTradeNo, outTradeNo).last("LIMIT 1 FOR UPDATE")));
    }

    private StlPayment byOutTradeNo(String outTradeNo) {
        return byOutTradeNo(outTradeNo, StlPayment.PAY);
    }

    private StlPayment byOutTradeNo(String outTradeNo, String direction) {
        return DataScopeContext.executeWithoutScope(() -> paymentMapper.selectOne(
                Wrappers.<StlPayment>lambdaQuery()
                        .eq(StlPayment::getDirection, direction)
                        .eq(StlPayment::getOutTradeNo, outTradeNo)
                        .last("LIMIT 1")));
    }
}
