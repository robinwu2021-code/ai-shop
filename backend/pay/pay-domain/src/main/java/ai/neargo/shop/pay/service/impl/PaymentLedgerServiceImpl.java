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
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Service
public class PaymentLedgerServiceImpl implements PaymentLedgerService {

    private static final Logger log = LoggerFactory.getLogger(PaymentLedgerServiceImpl.class);

    private final SettleMappers.PaymentMapper paymentMapper;
    private final ObjectMapper json = new ObjectMapper();

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
            var original = paymentRequest(open.getOutTradeNo()).orElseThrow(
                    () -> new IllegalArgumentException("PAY_REQUEST_SNAPSHOT_REQUIRED"));
            if (!Objects.equals(original.userNo(), cmd.userNo())
                    || !Objects.equals(original.entityNo(), cmd.entityNo())
                    || !Objects.equals(original.payChannel(), cmd.payChannel())
                    || !Objects.equals(original.currency(), cmd.currency())
                    || original.amountMinor() != cmd.amountMinor()) {
                throw new IllegalArgumentException("PAY_REQUEST_SNAPSHOT_CONFLICT");
            }
            return open.getOutTradeNo();
        }
        validateRequest(cmd);
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
        p.setCurrency(cmd.currency());
        p.setPaymentRequestJson(json.writeValueAsString(cmd));
        DataScopeContext.executeWithoutScope(() -> paymentMapper.insert(p));
        return outTradeNo;
    }

    @Override
    public Optional<SettlePort.PaymentOpen> paymentRequest(String outTradeNo) {
        StlPayment row = byOutTradeNo(outTradeNo);
        if (row == null || row.getPaymentRequestJson() == null) {
            return Optional.empty();
        }
        var request = json.readValue(row.getPaymentRequestJson(), SettlePort.PaymentOpen.class);
        validateRequest(request);
        if (!Objects.equals(row.getOrderNo(), request.orderNo())
                || !Objects.equals(row.getUserNo(), request.userNo())
                || !Objects.equals(row.getEntityNo(), request.entityNo())
                || !Objects.equals(row.getPayChannel(), request.payChannel())
                || !Objects.equals(row.getCurrency(), request.currency())
                || row.getAmountMinor() == null || row.getAmountMinor() != request.amountMinor()) {
            throw new IllegalArgumentException("PAY_REQUEST_SNAPSHOT_CONFLICT");
        }
        return Optional.of(request);
    }

    private static void validateRequest(SettlePort.PaymentOpen request) {
        if (!nonblank(request.orderNo()) || !nonblank(request.userNo()) || !nonblank(request.payChannel())
                || request.currency() == null || !request.currency().matches("[A-Z]{3}")
                || request.amountMinor() < 0) {
            throw new IllegalArgumentException("PAY_REQUEST_INVALID");
        }
        Set<String> subs = new HashSet<>();
        String directAccount = null;
        String path = null;
        for (var store : request.stores()) {
            var route = store.route();
            if (!nonblank(store.subOrderNo()) || !subs.add(store.subOrderNo()) || route == null
                    || !nonblank(route.merchantNo()) || !nonblank(route.storeNo())
                    || !nonblank(route.payMerchantNo()) || !nonblank(route.subMerchantId())
                    || !nonblank(route.accountVersion())
                    || !(Objects.equals(route.channel(), request.payChannel())
                            || ("FREE".equals(request.payChannel()) && request.amountMinor() == 0
                                    && nonblank(route.channel())))
                    || !("DIRECT".equals(route.fundsPath()) || "AGGREGATED".equals(route.fundsPath()))) {
                throw new IllegalArgumentException("PAY_STORE_ROUTE_INVALID");
            }
            if (path != null && !path.equals(route.fundsPath())) {
                throw new IllegalArgumentException("PAY_MIXED_FUNDS_PATH_UNSUPPORTED");
            }
            path = route.fundsPath();
            if ("DIRECT".equals(path)) {
                String account = route.payMerchantNo() + ":" + route.subMerchantId() + ":" + route.accountVersion();
                if (directAccount != null && !directAccount.equals(account)) {
                    throw new IllegalArgumentException("PAY_MULTIPLE_DIRECT_ACCOUNTS_UNSUPPORTED");
                }
                directAccount = account;
            }
        }
    }

    private static boolean nonblank(String value) {
        return value != null && !value.isBlank();
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
        if (amountMinor <= 0 || afterSaleNo == null || afterSaleNo.isBlank()) {
            throw new IllegalArgumentException("PAY_REFUND_INPUT_INVALID");
        }
        StlPayment origin = DataScopeContext.executeWithoutScope(() -> paymentMapper.lockPaidForRefund(orderNo));
        if (origin == null) {
            return null;
        }
        StlPayment exist = DataScopeContext.executeWithoutScope(
                () -> paymentMapper.lockRefundByAfterSale(afterSaleNo));
        if (exist != null) {
            if (!orderNo.equals(exist.getOrderNo()) || !subOrderNo.equals(exist.getSubOrderNo())
                    || exist.getAmountMinor() == null || exist.getAmountMinor() != amountMinor) {
                throw new IllegalArgumentException("PAY_REFUND_INPUT_CONFLICT");
            }
            return exist.getPaymentNo();
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
        r.setRefundRequestJson(json.writeValueAsString(new RefundTicket(origin.getPayChannel(), origin.getTradeNo(),
                origin.getOutTradeNo(), r.getOutTradeNo(), nz(origin.getAmountMinor()), amountMinor, reason)));
        DataScopeContext.executeWithoutScope(() -> paymentMapper.insert(r));
        log.info("[payment-ledger] 退款流水 {} 已落（原收款 {}，{} 分）",
                r.getPaymentNo(), origin.getOutTradeNo(), amountMinor);
        return r.getPaymentNo();
    }

    @Override
    @Transactional("payTxManager")
    public boolean claimRefundDispatch(String refundPaymentNo) {
        StlPayment refund = DataScopeContext.executeWithoutScope(
                () -> paymentMapper.lockRefundEvidence(refundPaymentNo));
        if (refund == null) {
            throw new IllegalArgumentException("PAY_REFUND_UNKNOWN");
        }
        if (!StlPayment.PENDING.equals(refund.getStatus()) || refund.getRefundDispatchAt() != null) {
            return false;
        }
        StlPayment patch = new StlPayment();
        patch.setId(refund.getId());
        patch.setRefundDispatchAt(System.currentTimeMillis());
        DataScopeContext.executeWithoutScope(() -> paymentMapper.updateById(patch));
        return true;
    }

    @Override
    public java.util.Optional<RefundTicket> refundTicket(String refundPaymentNo) {
        StlPayment r = byPaymentNo(refundPaymentNo);
        if (r == null || !StlPayment.REFUND.equals(r.getDirection())) {
            return java.util.Optional.empty();
        }
        if (r.getRefundRequestJson() == null) {
            return java.util.Optional.empty();
        }
        RefundTicket ticket = json.readValue(r.getRefundRequestJson(), RefundTicket.class);
        if (!r.getPayChannel().equals(ticket.payChannel()) || !r.getOutTradeNo().equals(ticket.outRefundNo())
                || nz(r.getAmountMinor()) != ticket.amountMinor()) {
            throw new IllegalArgumentException("PAY_REFUND_REQUEST_SNAPSHOT_CONFLICT");
        }
        return java.util.Optional.of(ticket);
    }

    @Override
    @Transactional("payTxManager")
    public void markRefundSent(String refundPaymentNo, boolean accepted, boolean retryable,
                               String providerNo, String reason) {
        StlPayment r = DataScopeContext.executeWithoutScope(
                () -> paymentMapper.lockRefundEvidence(refundPaymentNo));
        if (r == null || !StlPayment.PENDING.equals(r.getStatus())) {
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
    public void markRefundSettled(String refundPaymentNo,String providerNo,long verifiedMinor) {
        StlPayment refund=DataScopeContext.executeWithoutScope(() -> paymentMapper.lockRefundEvidence(refundPaymentNo));
        if (refund==null) { throw new IllegalArgumentException("PAY_REFUND_UNKNOWN"); }
        if (verifiedMinor<=0 || refund.getAmountMinor()==null || refund.getAmountMinor()!=verifiedMinor) {
            throw new IllegalArgumentException("PAY_REFUND_VERIFIED_AMOUNT_MISMATCH");
        }
        if (providerNo==null || providerNo.isBlank() || providerNo.length()>128) {
            throw new IllegalArgumentException("PAY_REFUND_VERIFIED_RECEIPT_REQUIRED");
        }
        if (refund.getRefundVerifiedAt()!=null) {
            if (!StlPayment.SUCCESS.equals(refund.getStatus())
                    || !providerNo.equals(refund.getRefundVerifiedProviderNo())
                    || refund.getRefundVerifiedMinor()==null || refund.getRefundVerifiedMinor()!=verifiedMinor) {
                throw new IllegalArgumentException("PAY_REFUND_VERIFIED_RECEIPT_CONFLICT");
            }
            return;
        }
        if (!StlPayment.PENDING.equals(refund.getStatus()) && !StlPayment.SUCCESS.equals(refund.getStatus())) {
            throw new IllegalStateException("PAY_REFUND_STATE_CONFLICT");
        }
        long now=System.currentTimeMillis();
        StlPayment patch=new StlPayment();
        patch.setId(refund.getId());
        patch.setStatus(StlPayment.SUCCESS);
        patch.setSucceededAt(refund.getSucceededAt()==null?now:refund.getSucceededAt());
        patch.setRefundVerifiedMinor(verifiedMinor);
        patch.setRefundVerifiedProviderNo(providerNo);
        patch.setRefundVerifiedAt(now);
        // Do not overwrite trade_no: its original acceptance value participates in a cross-direction unique key.
        DataScopeContext.executeWithoutScope(() -> paymentMapper.updateById(patch));
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
    public String settleRefundByOutTradeNo(String outRefundNo,String providerNo,long verifiedMinor) {
        StlPayment r = DataScopeContext.executeWithoutScope(() -> paymentMapper.selectOne(
                Wrappers.<StlPayment>lambdaQuery().eq(StlPayment::getDirection, StlPayment.REFUND)
                        .eq(StlPayment::getOutTradeNo, outRefundNo).last("LIMIT 1")));
        if (r == null || !StlPayment.REFUND.equals(r.getDirection())) {
            /*
             * **认领不了就说认领不了。** 通道回传了一个我方没发出去过的退款号，
             * 当成功的话账上会多出一笔从没发生过的退款；
             * 而真出现这种情况，要么是串了商户号，要么是有人在伪造回调。
             */
            log.error("[payment-ledger] 收到无法认领的退款回调 out_refund_no={}", outRefundNo);
            return null;
        }
        markRefundSettled(r.getPaymentNo(),providerNo,verifiedMinor);
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
    @Transactional("payTxManager")
    public void close(String outTradeNo, String reason) {
        StlPayment p = byOutTradeNo(outTradeNo);
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
        StlPayment existing = byOutTradeNo(cmd.outTradeNo());
        if (existing == null) {
            /*
             * **按订单号回退认领一次。**
             *
             * 2026-09-01 之前 out_trade_no 就是订单号，所以调用方传订单号是对的；
             * 独立之后真通道回传的一定是我方给它的 out_trade_no，走上面那条就找到了。
             *
             * 回退这条留给两种情况：**存量在途的单**（发起于改动之前），
             * 以及 stub 通道 —— 它是开发期的假通道，调用方手上常常只有订单号。
             *
             * <b>生产上真通道走到这里即异常</b>，所以记 WARN 而不是静默 ——
             * 它意味着通道回传了一个我方没发出去过的单号。
             */
            StlPayment byOrder = openPaymentOf(cmd.outTradeNo());
            if (byOrder != null) {
                log.warn("[payment-ledger] 按 out_trade_no 认领不到，按订单号找到了 —— "
                        + "传入 {}（存量在途单或 stub 通道；真通道走到这里即异常）",
                        cmd.outTradeNo());
                existing = byOrder;
            }
        }
        if (existing == null) {
            /*
             * 没有起点行。**正常链路不会走到这里** —— 端上必须先调
             * /mp/order/{no}/pay 拿到支付参数才付得成，而那一步会落 PENDING 行。
             * 走到这里的是存量单：本功能（2026-09-01）上线之前发起、之后才回调的。
             *
             * <b>不硬补一行</b>：stl_payment.user_no 是 NOT NULL，而这里拿不到付款人 ——
             * 编一个值进去，这行流水就永远指向一个不存在的用户，
             * 而它会被对账、退款追溯当成真数据用。**宁可缺一行，不要一行假的。**
             *
             * 也不抛错：抛了通道会一直重推，而订单状态那边照样要推进 ——
             * 这笔钱确实收到了。缺的这一行是存量的历史问题，随窗口过去自己消失。
             */
            log.warn("[payment-ledger] {} 没有发起行，跳过记账 —— "
                    + "存量单（本功能上线前发起），或调用方跳过了发起。订单状态不受影响",
                    cmd.outTradeNo());
            /*
             * **把传入值当订单号返回**，让订单状态照常推进。
             *
             * 这笔钱确实收到了 —— 返回 null 让回调 ackFail 的话，通道会一直重推，
             * 而重推多少次都不会有发起行。用户付了钱而订单一直不动，
             * 比「缺一行流水」严重得多。
             *
             * 这条能成立是因为**第一次发起的商户单号就是订单号本身**
             * （见 open 里的后缀规则）。重试单（带 -2 后缀）走不到这里 ——
             * 它一定有发起行，否则那个号根本不会存在。
             */
            return cmd.outTradeNo();
        }
        if (StlPayment.SUCCESS.equals(existing.getStatus())) {
            /*
             * 幂等：通道会重推。**不覆盖已有的成功时刻** ——
             * 覆盖的话对账查到的成功时刻会随每次重推往后跳，
             * 而那个时刻是「钱什么时候到的」的唯一依据。
             */
            return existing.getOrderNo();
        }
        StlPayment patch = new StlPayment();
        patch.setId(existing.getId());
        patch.setStatus(StlPayment.SUCCESS);
        patch.setTradeNo(cmd.tradeNo());
        patch.setSucceededAt(cmd.succeededAt());
        /*
         * 金额以**通道回执**为准，不是以发起时的应付为准。
         * 两者不一致时留下的是通道说的那个数 —— 对账要对的就是它，
         * 而「我方以为该收多少」在发起行里已经记过了。
         */
        patch.setAmountMinor(cmd.amountMinor());
        DataScopeContext.executeWithoutScope(() -> paymentMapper.updateById(patch));
        return existing.getOrderNo();
    }

    private StlPayment byOutTradeNo(String outTradeNo) {
        return DataScopeContext.executeWithoutScope(() -> paymentMapper.selectOne(
                Wrappers.<StlPayment>lambdaQuery()
                        .eq(StlPayment::getDirection, StlPayment.PAY)
                        .eq(StlPayment::getOutTradeNo, outTradeNo)
                        .last("LIMIT 1")));
    }
    @Override public SettlePort.RefundEvidence refundEvidence(String no) {
        var row=byPaymentNo(no);
        if (row==null) { return null; }
        return new SettlePort.RefundEvidence(row.getPaymentNo(),row.getDirection(),row.getAfterSaleNo(),
                row.getSubOrderNo(),row.getUserNo(),row.getCurrency(),row.getAmountMinor(),row.getStatus(),
                row.getRefundVerifiedMinor(),row.getRefundVerifiedAt(),row.getRefundVerifiedProviderNo());
    }
    @Override public boolean hasRefundForAfterSale(String no) {
        return DataScopeContext.executeWithoutScope(() -> paymentMapper.selectCount(Wrappers.<StlPayment>lambdaQuery()
                .eq(StlPayment::getDirection,StlPayment.REFUND).eq(StlPayment::getAfterSaleNo,no)))>0;
    }
    @Override public String paymentCurrency(String orderNo) {
        var row=DataScopeContext.executeWithoutScope(() -> paymentMapper.selectOne(Wrappers.<StlPayment>lambdaQuery()
                .eq(StlPayment::getOrderNo,orderNo).eq(StlPayment::getDirection,StlPayment.PAY)
                .eq(StlPayment::getStatus,StlPayment.SUCCESS).orderByDesc(StlPayment::getId).last("LIMIT 1")));
        return row==null?null:row.getCurrency();
    }
}
