package ai.neargo.shop.pay.service.impl;

import ai.neargo.shop.pay.acquiring.dto.ChannelOutcome;
import ai.neargo.shop.pay.acquiring.service.RefundService;
import ai.neargo.shop.pay.acquiring.service.RefundOutcomeService;
import ai.neargo.shop.spi.settle.RefundRequest;
import ai.neargo.shop.pay.acquiring.service.PaymentOutcomeService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import ai.neargo.shop.pay.acquiring.service.PaymentService;
import ai.neargo.shop.pay.numbering.PayOperationsDocuments;
import ai.neargo.shop.numbering.DocumentType;
import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.entity.StlPayment;
import ai.neargo.shop.pay.mapper.SettleMappers;
import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.spi.settle.OrdinaryPaymentPort.Attempt;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import tools.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Service
public class PaymentLedgerServiceImpl implements PaymentLedgerService {
    @org.springframework.beans.factory.annotation.Autowired
    private PayOperationsDocuments documents;

    private static final Logger log = LoggerFactory.getLogger(PaymentLedgerServiceImpl.class);

    private final SettleMappers.PaymentMapper paymentMapper;
    private final ObjectMapper json = new ObjectMapper();

    private final RefundCapacityLedger capacity;
    public PaymentLedgerServiceImpl(SettleMappers.PaymentMapper paymentMapper, RefundCapacityLedger capacity) {
        this.capacity = capacity;
        this.paymentMapper = paymentMapper;
    }

    @Override
    @Transactional("payTxManager")
    public String open(SettlePort.PaymentOpen cmd) {
        if (successPayment(cmd.orderNo()) != null) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
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
            return reuse(open, cmd);
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
        // 实际收款账户落到行上，退款按它找原交易（F08 · R08）。与下单时同一条规则
        DirectAccounts.of(cmd).ifPresent(route -> {
            p.setSubMerchantId(route.subMerchantId());
            p.setPayMerchantNo(route.payMerchantNo());
        });
        try {
            DataScopeContext.executeWithoutScope(() -> paymentMapper.insert(p));
        } catch (org.springframework.dao.DuplicateKeyException race) {
            /*
             * 并发的另一次「去支付」先落了同号的行（F07 · Review R07）。上面的检查不加锁，两边都会过；
             * 兜底的是 out_trade_no 唯一键。撞键的这一方读回那一行、按同一套快照比对，返回同一个单号 ——
             * 对用户而言两次点击就是同一笔。
             * 必须用加锁读：REPEATABLE READ 下普通读用的是本事务开头的快照，看不到对方刚提交的行。
             */
            StlPayment winner = lockPayment(outTradeNo);
            if (winner == null) {
                throw race;
            }
            return reuse(winner, cmd);
        }
        documents.bind(DocumentType.PAYMENT_ATTEMPT, "MAIN", p.getPaymentNo());
        if (payOrders != null) {
            // 尝试挂到支付单上（I3 S3）；调用方没建支付单（开关关、存量单）时什么都不做
            payOrders.ifAvailable(s -> s.attachAttempt(cmd.orderNo(), p.getId()));
        }
        return outTradeNo;
    }

    /** 复用一笔未终态的收款：请求快照必须一致，否则是另一笔请求冒用了同一订单 */
    private String reuse(StlPayment open, SettlePort.PaymentOpen cmd) {
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

    /** 支付单（I3 S3）；手工构造的单测里没有 */
    private ObjectProvider<PaymentService> payOrders;
    /** 结果唯一入口（I3 S4）；切片测试里没有 —— 调用时再取 */
    private ObjectProvider<PaymentOutcomeService> outcomes;

    @Autowired(required = false)
    void setOutcomes(ObjectProvider<PaymentOutcomeService> outcomes) {
        this.outcomes = outcomes;
    }

    @Autowired(required = false)
    void setPayOrders(ObjectProvider<PaymentService> payOrders) {
        this.payOrders = payOrders;
    }

    private ObjectProvider<RefundService> refundOrders;
    private ObjectProvider<RefundOutcomeService> refundOutcomes;

    @Autowired
    void setRefundOrders(ObjectProvider<RefundService> refundOrders) {
        this.refundOrders = refundOrders;
    }

    @Autowired
    void setRefundOutcomes(ObjectProvider<RefundOutcomeService> refundOutcomes) {
        this.refundOutcomes = refundOutcomes;
    }

    @Override
    public Optional<String> paymentNoOf(String outTradeNo) {
        if (outTradeNo == null || outTradeNo.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(DataScopeContext.executeWithoutScope(() -> paymentMapper.selectOne(
                        Wrappers.<StlPayment>lambdaQuery().eq(StlPayment::getOutTradeNo, outTradeNo))))
                .map(StlPayment::getPaymentNo);
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

    /**
     * 这个订单**已经结束**的下单次数 —— 决定下一个商户单号的后缀。
     *
     * <p>只数终态（不含 INIT / PENDING）：能走到新开一笔，说明这单没有未终态的收款，
     * 此刻数到的在途行只可能是并发的另一次「去支付」。把它也数进去，两边就算出不同的单号
     * （「订单号」与「订单号-2」），绕开唯一键各插一行（F07 · Review R07，并发测试实测抓到）。
     * 只数终态时两边算出同一个号，唯一键兜住、后到的复用先到的。
     */
    private long countPayAttempts(String orderNo) {
        return DataScopeContext.executeWithoutScope(() -> paymentMapper.selectCount(
                Wrappers.<StlPayment>lambdaQuery()
                        .eq(StlPayment::getDirection, StlPayment.PAY)
                        .eq(StlPayment::getOrderNo, orderNo)
                        .notIn(StlPayment::getStatus, StlPayment.INIT, StlPayment.PENDING)));
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
    @Transactional(value="payTxManager", isolation=Isolation.READ_COMMITTED)
    public String refund(String orderNo, String subOrderNo, String afterSaleNo,
                         long amountMinor, String reason) {
        if (amountMinor <= 0 || afterSaleNo == null || afterSaleNo.isBlank()) {
            throw new IllegalArgumentException("PAY_REFUND_INPUT_INVALID");
        }
        var candidate = successPayment(orderNo);
        StlPayment origin = candidate == null ? null : DataScopeContext.executeWithoutScope(() ->
                paymentMapper.selectOne(Wrappers.<StlPayment>lambdaQuery()
                        .eq(StlPayment::getPaymentNo,candidate.paymentNo())
                        .eq(StlPayment::getDirection,StlPayment.PAY)
                        .eq(StlPayment::getStatus,StlPayment.SUCCESS).last("FOR UPDATE")));
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

        capacity.check(origin, amountMinor);

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
        // Keep the source's original channel instruction; governance must not allocate a second refund number.
        r.setRefundExecutionNo(r.getOutTradeNo());
        r.setOrderNo(orderNo);
        r.setSubOrderNo(subOrderNo);
        r.setAfterSaleNo(afterSaleNo);
        r.setUserNo(origin.getUserNo());
        r.setEntityNo(origin.getEntityNo());
        r.setPayChannel(origin.getPayChannel());
        r.setCurrency(origin.getCurrency());
        r.setAmountMinor(amountMinor);
        r.setErrMsg(reason);
        r.setOriginPaymentNo(origin.getPaymentNo());
        r.setRefundCapacityState("RESERVED");
        r.setRefundRequestJson(json.writeValueAsString(new RefundTicket(origin.getPayChannel(), origin.getTradeNo(),
                origin.getOutTradeNo(), r.getOutTradeNo(), nz(origin.getAmountMinor()), amountMinor, reason)));
        DataScopeContext.executeWithoutScope(() -> paymentMapper.insert(r));
        if (refundOrders != null) {
            refundOrders.ifAvailable(service -> service.attachExecution(origin, r,
                    new RefundRequest(r.getRefundExecutionNo(), afterSaleNo, subOrderNo, origin.getPaymentNo(),
                            amountMinor, origin.getCurrency(), reason)));
        }
        log.info("[payment-ledger] 退款流水 {} 已落（原收款 {}，{} 分）",
                r.getPaymentNo(), origin.getOutTradeNo(), amountMinor);
        return r.getPaymentNo();
    }

    @Override
    @Transactional("payTxManager")
    public boolean claimRefundDispatch(String refundPaymentNo) {
        var origin = capacity.lockOriginal(byPaymentNo(refundPaymentNo));
        if (origin != null && "FROZEN".equals(origin.getRefundCapacityState())) return false;
        StlPayment refund = DataScopeContext.executeWithoutScope(
                () -> paymentMapper.lockRefundEvidence(refundPaymentNo));
        if (refund == null) {
            throw new IllegalArgumentException("PAY_REFUND_UNKNOWN");
        }
        // Historical rows without an execution identity are query-only; never adopt them for a new send.
        if (refund.getRefundExecutionNo() == null) return false;
        RefundService governance = refundOrders == null ? null : refundOrders.getIfAvailable();
        if (governance != null && !governance.dispatchAllowed(refund.getRefundExecutionNo())) return false;
        if (java.util.Set.of("PROCESSING","MANUAL_REQUIRED","LATE_SUCCESS","TERMINAL_FAILED")
                .contains(refund.getRefundQueryState()==null?"":refund.getRefundQueryState())) return false;
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
    public void markRefundRejected(String paymentNo, String reason) {
        var row = DataScopeContext.executeWithoutScope(() -> paymentMapper.lockRefundEvidence(paymentNo));
        if (row == null) throw new IllegalArgumentException("PAY_REFUND_UNKNOWN");
        if (StlPayment.SUCCESS.equals(row.getStatus())) return;
        capacity.rejected(row, reason);
        markRefundSent(paymentNo, false, false, null, reason);
    }

    @Override
    @Transactional("payTxManager")
    public void markRefundSettled(String refundPaymentNo,String providerNo,long verifiedMinor) {
        var candidate = byPaymentNo(refundPaymentNo);
        if (candidate != null) capacity.lockOriginal(candidate);
        StlPayment refund=DataScopeContext.executeWithoutScope(() -> paymentMapper.lockRefundEvidence(refundPaymentNo));
        if (refund==null) { throw new IllegalArgumentException("PAY_REFUND_UNKNOWN"); }
        if (verifiedMinor<=0 || refund.getAmountMinor()==null || refund.getAmountMinor()!=verifiedMinor) {
            throw new IllegalArgumentException("PAY_REFUND_VERIFIED_AMOUNT_MISMATCH");
        }
        if (providerNo==null || providerNo.isBlank() || providerNo.length()>128) {
            throw new IllegalArgumentException("PAY_REFUND_VERIFIED_RECEIPT_REQUIRED");
        }
        if (!capacity.consume(refund, providerNo, verifiedMinor)) return;
        if (refund.getRefundVerifiedAt()!=null) {
            if (!StlPayment.SUCCESS.equals(refund.getStatus())
                    || refund.getRefundVerifiedMinor()==null || refund.getRefundVerifiedMinor()!=verifiedMinor) {
                throw new IllegalArgumentException("PAY_REFUND_VERIFIED_RECEIPT_CONFLICT");
            }
            // A later verified replay must not replace the first receipt or success time.
            return;
        }
        if (!StlPayment.PENDING.equals(refund.getStatus()) && !StlPayment.SUCCESS.equals(refund.getStatus())
                && !StlPayment.FAILED.equals(refund.getStatus())) {
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
        if (refundOutcomes != null && refund.getRefundExecutionNo() != null) {
            refundOutcomes.ifAvailable(service -> service.succeeded(refund.getRefundExecutionNo()));
        }
    }

    @Override
    @Transactional("payTxManager")
    public void freezePayer(String outTradeNo, String userNo, String payerId, String appId) {
        if (!nonblank(userNo) || !nonblank(payerId) || !nonblank(appId)) {
            throw new IllegalArgumentException("PAY_PAYER_IDENTITY_REQUIRED");
        }
        StlPayment payment = lockPayment(outTradeNo);
        if (payment == null || !Objects.equals(payment.getUserNo(), userNo)) {
            throw new IllegalArgumentException("PAY_PAYER_OWNER_CONFLICT");
        }
        if (nonblank(payment.getPayerOpenid()) || nonblank(payment.getWxAppid())) {
            if (!Objects.equals(payment.getPayerOpenid(), payerId)
                    || !Objects.equals(payment.getWxAppid(), appId)) {
                throw new IllegalArgumentException("PAY_PAYER_IDENTITY_CONFLICT");
            }
            return;
        }
        StlPayment patch = new StlPayment();
        patch.setId(payment.getId());
        patch.setPayerOpenid(payerId);
        patch.setWxAppid(appId);
        DataScopeContext.executeWithoutScope(() -> paymentMapper.updateById(patch));
    }

    @Override
    @Transactional("payTxManager")
    public void recordPayer(String outTradeNo, String payerId, String appId) {
        if (payerId == null || payerId.isBlank()) {
            // 没有就不写。写一行空的等于把「没取到」和「没这个概念」混成一件事
            return;
        }
        StlPayment p = lockPayment(outTradeNo);
        if (p == null) {
            log.warn("[payment-ledger] 记付款人时找不到流水 {}", outTradeNo);
            return;
        }
        if ((nonblank(p.getPayerOpenid()) && !Objects.equals(p.getPayerOpenid(), payerId))
                || (nonblank(p.getWxAppid()) && nonblank(appId) && !Objects.equals(p.getWxAppid(), appId))) {
            throw new IllegalArgumentException("PAY_PAYER_IDENTITY_CONFLICT");
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
        var settled = byPaymentNo(r.getPaymentNo());
        return settled != null && StlPayment.SUCCESS.equals(settled.getStatus())
                && settled.getRefundVerifiedAt() != null ? r.getOrderNo() : null;
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
        // 尝试行记的是渠道事实（实收），全仓读成功收款的地方都按这个口径；渠道事实另在 channel_* 显式留一份。
        // 应收以支付单 pay_order.amount_minor 为准（R20 的「原始值丢失」由支付单解决，见 TDD 偏差 5）
        patch.setAmountMinor(cmd.amountMinor());
        patch.setCurrency(cmd.currency());
        patch.setChannelAmountMinor(cmd.amountMinor());
        patch.setChannelCurrency(cmd.currency());
        DataScopeContext.executeWithoutScope(() -> paymentMapper.updateById(patch));
        if (outcomes != null && outcomes.getIfAvailable() != null) {
            // 支付单这一层由结果唯一入口决定（I3 S4）：关单后到账、实收不符、重复支付都转异常，不进成功
            outcomes.getObject().apply(new ChannelOutcome(existing.getId(), existing.getPayOrderId(), existing.getPayChannel(), cmd.tradeNo(),
                    cmd.amountMinor(), cmd.currency(), cmd.succeededAt()));
        }
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
    public SettlePort.PaymentReceipt successPayment(String orderNo, String channel, String tradeNo) {
        if (tradeNo == null || tradeNo.isBlank()) { return null; }
        StlPayment p = DataScopeContext.executeWithoutScope(() -> paymentMapper.selectOne(
                Wrappers.<StlPayment>lambdaQuery().eq(StlPayment::getDirection, StlPayment.PAY)
                        .eq(StlPayment::getOrderNo, orderNo).eq(StlPayment::getStatus, StlPayment.SUCCESS)
                        .eq(channel != null, StlPayment::getPayChannel, channel)
                        .eq(StlPayment::getTradeNo, tradeNo).last("LIMIT 1")));
        return receipt(p);
    }

    @Override
    public SettlePort.PaymentReceipt paymentByOutTradeNo(String outTradeNo) {
        return receipt(byOutTradeNo(outTradeNo));
    }

    @Override
    public Attempt ordinaryPayment(String outTradeNo) {
        return ordinaryReceipt(byOutTradeNo(outTradeNo));
    }

    @Override
    public Attempt successfulOrdinaryPayment(String orderNo) {
        StlPayment payment = DataScopeContext.executeWithoutScope(() -> paymentMapper.selectOne(
                Wrappers.<StlPayment>lambdaQuery().eq(StlPayment::getDirection, StlPayment.PAY)
                        .eq(StlPayment::getOrderNo, orderNo).eq(StlPayment::getStatus, StlPayment.SUCCESS)
                        .orderByDesc(StlPayment::getId).last("LIMIT 1")));
        return ordinaryReceipt(payment);
    }

    private Attempt ordinaryReceipt(StlPayment payment) {
        return payment == null ? null : new Attempt(payment.getPaymentNo(), payment.getOutTradeNo(),
                payment.getOrderNo(), payment.getUserNo(), payment.getEntityNo(), payment.getPayChannel(),
                payment.getTradeNo(), payment.getAmountMinor(), payment.getCurrency(), payment.getStatus(),
                documents.find(DocumentType.PAYMENT_ATTEMPT, "MAIN", payment.getPaymentNo()));
    }

    private SettlePort.PaymentReceipt receipt(StlPayment p) {
        return p == null ? null : new SettlePort.PaymentReceipt(p.getPaymentNo(), p.getOrderNo(),
                p.getUserNo(), p.getPayChannel(), p.getTradeNo(), nz(p.getAmountMinor()),
                p.getCurrency(), p.getStatus()).withDocumentNo(documents.find(DocumentType.PAYMENT_ATTEMPT, "MAIN", p.getPaymentNo()));
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
    @Override public java.util.Optional<RefundQueryCoordinates> refundQueryCoordinates(String no) {
        var row=byPaymentNo(no);
        if(row==null || !StlPayment.REFUND.equals(row.getDirection()) || row.getPayChannel()==null
                || row.getPayChannel().isBlank() || row.getOutTradeNo()==null || row.getOutTradeNo().isBlank())
            return java.util.Optional.empty();
        return java.util.Optional.of(new RefundQueryCoordinates(row.getPayChannel(),row.getOutTradeNo()));
    }
    @Override public SettlePort.RefundEvidence refundEvidence(String no) {
        var row=byPaymentNo(no);
        if (row==null) { return null; }
        return new SettlePort.RefundEvidence(row.getPaymentNo(),row.getDirection(),row.getAfterSaleNo(),
                row.getSubOrderNo(),row.getUserNo(),row.getCurrency(),row.getAmountMinor(),row.getStatus(),
                row.getRefundVerifiedMinor(),row.getRefundVerifiedAt(),row.getRefundVerifiedProviderNo(),capacity.safety(row));
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
