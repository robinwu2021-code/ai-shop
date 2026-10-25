package ai.neargo.shop.pay.acquiring.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.access.PayAction;
import ai.neargo.shop.pay.access.PayCaller;
import ai.neargo.shop.pay.access.policy.CallerPolicy;
import ai.neargo.shop.pay.acquiring.dto.CreatePaymentCmd;
import ai.neargo.shop.pay.acquiring.entity.PayOrder;
import ai.neargo.shop.pay.acquiring.service.PaymentService;
import ai.neargo.shop.pay.acquiring.statemachine.PayOrderEvent;
import ai.neargo.shop.pay.acquiring.statemachine.PayOrderStateMachine;
import ai.neargo.shop.pay.acquiring.statemachine.PayOrderStatus;
import ai.neargo.shop.pay.entity.StlPayment;
import ai.neargo.shop.pay.mapper.SettleMappers.PaymentMapper;
import ai.neargo.shop.pay.mapper.acquiring.AcquiringMappers.PayOrderMapper;
import ai.neargo.shop.spi.pay.PaymentPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.spi.pay.PayQueryPort;
import ai.neargo.shop.spi.settle.SettlePort;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** {@link PaymentService} 的实现（TDD-支付服务-I3支付单退款流水 S3） */
@Service
public class PaymentServiceImpl implements PaymentService {

    private static final List<String> INTERNAL_BIZ = List.of(PaymentPort.MALL_ORDER, PaymentPort.FOOD_CHECK);

    private final CallerPolicy policy;
    private final PayOrderMapper orders;
    private final PaymentMapper attempts;
    private final TransactionTemplate tx;
    private final PayQueryPort channels;
    private final PaymentLedgerService ledger;

    public PaymentServiceImpl(CallerPolicy policy, PayOrderMapper orders, PaymentMapper attempts,
                              @Qualifier("payTxManager") PlatformTransactionManager payTxManager,
                              @Lazy PayQueryPort channels, @Lazy PaymentLedgerService ledger) {
        this.policy = policy;
        this.orders = orders;
        this.attempts = attempts;
        this.tx = new TransactionTemplate(payTxManager);
        this.channels = channels;
        this.ledger = ledger;
    }

    @Override
    public PayOrder create(PayCaller caller, CreatePaymentCmd cmd) {
        policy.check(caller, "IN_STORE".equals(cmd.scene()) ? PayAction.CREATE_IN_STORE : PayAction.CREATE_ONLINE,
                cmd.storeId());
        String sha = digest(caller.callerId(), cmd);
        PayOrder existing = byOutOrderNo(caller.callerId(), cmd.outOrderNo());
        if (existing != null) {
            return sameRequest(existing, sha);
        }
        PayOrder o = new PayOrder();
        o.setPayOrderNo(BizKey.next(BizKey.PAY_ORDER));
        o.setPayAppId(caller.callerId());
        o.setOutOrderNo(cmd.outOrderNo());
        o.setBizType(cmd.bizType());
        o.setBizNo(cmd.bizNo());
        o.setStoreId(cmd.storeId());
        o.setEntityNameSnapshot(cut(cmd.entityName(), 128));
        o.setStoreNameSnapshot(cut(cmd.storeName(), 128));
        o.setMarket(cmd.market());
        o.setCurrency(cmd.currency());
        o.setAmountMinor(cmd.amountMinor());
        o.setPaidMinor(0L);
        o.setRefundedMinor(0L);
        o.setReservedRefundMinor(0L);
        o.setSubject(cut(cmd.subject(), 128));
        o.setScene(cmd.scene() == null ? "ONLINE" : cmd.scene());
        o.setStatus(PayOrderStatus.CREATED.name());
        o.setSourceType(caller.type().name());
        o.setSourceId(caller.callerId());
        o.setOperatorNo(caller.operatorNo());
        o.setUserId(caller.userId());
        o.setRequestSha256(sha);
        o.setExpireAt(LocalDateTime.ofInstant(cmd.expireAt(), ZoneOffset.UTC));
        // I4 之前没有「选方式」这一步：资金路径在建单时按主体冻结（偏差 4）
        o.setFundsPath(cmd.fundsPath());
        try {
            tx.executeWithoutResult(s -> DataScopeContext.executeWithoutScope(() -> orders.insert(o)));
            return o;
        } catch (DuplicateKeyException concurrent) {
            // 两次「去支付」同时到：唯一键 (pay_app_id, out_order_no) 兜底，输的那方回读赢家那行再比摘要
            return sameRequest(byOutOrderNo(caller.callerId(), cmd.outOrderNo()), sha);
        }
    }

    @Override
    public PayOrder find(String payOrderNo) {
        return DataScopeContext.executeWithoutScope(() -> orders.selectOne(Wrappers.<PayOrder>lambdaQuery()
                .eq(PayOrder::getPayOrderNo, payOrderNo)));
    }

    @Override
    public PayOrder query(PayCaller caller, String payOrderNo) {
        PayOrder o = find(payOrderNo);
        if (o == null) {
            throw BizException.of(ErrorCode.PAY_ORDER_NOT_FOUND);
        }
        policy.check(caller, PayAction.QUERY, o.getStoreId());
        return o;
    }

    @Override
    public PayOrder findByOutOrderNo(Long appId, String outOrderNo) {
        return byOutOrderNo(appId, outOrderNo);
    }

    @Override
    public PayOrder close(PayCaller caller, String payOrderNo, String reason) {
        PayOrder o = query(caller, payOrderNo);
        policy.check(caller, PayAction.CLOSE, o.getStoreId());
        if (PayOrderStatus.valueOf(o.getStatus()).terminal()) {
            return o;
        }
        List<StlPayment> open = DataScopeContext.executeWithoutScope(() -> attempts.selectList(
                Wrappers.<StlPayment>lambdaQuery().eq(StlPayment::getPayOrderId, o.getId())
                        .eq(StlPayment::getDirection, StlPayment.PAY)
                        .notIn(StlPayment::getStatus, StlPayment.SUCCESS, StlPayment.CLOSED, StlPayment.FAILED)));
        // 关前必查单（R18），渠道调用在事务外：查到已付就按结果入口入账，不关
        for (StlPayment a : open) {
            PayQueryPort.Result r = channels.query(a.getPayChannel(), a.getOutTradeNo());
            if (r != null && r.ok() && r.found() && r.paid() && r.tradeNo() != null) {
                ledger.settle(new SettlePort.PaymentSettled(a.getOutTradeNo(), a.getPayChannel(), r.tradeNo(),
                        r.amountMinor(), System.currentTimeMillis(), r.currency() == null ? a.getCurrency() : r.currency()));
                return find(payOrderNo);
            }
        }
        tx.executeWithoutResult(s -> {
            PayOrder locked = orders.lockById(o.getId());
            PayOrderStatus from = PayOrderStatus.valueOf(locked.getStatus());
            if (!PayOrderStateMachine.allows(from, PayOrderEvent.CLOSED)) {
                return;
            }
            for (StlPayment a : open) {
                ledger.close(a.getOutTradeNo(), reason);
            }
            PayOrder patch = new PayOrder();
            patch.setId(locked.getId());
            patch.setStatus(PayOrderStateMachine.next(from, PayOrderEvent.CLOSED).name());
            patch.setClosedAt(LocalDateTime.now(ZoneOffset.UTC));
            DataScopeContext.executeWithoutScope(() -> orders.updateById(patch));
        });
        return find(payOrderNo);
    }

    @Override
    public void attachAttempt(String outOrderNo, Long stlPaymentId) {
        if (outOrderNo == null || stlPaymentId == null) {
            return;
        }
        PayOrder found = DataScopeContext.executeWithoutScope(() -> orders.selectOne(Wrappers.<PayOrder>lambdaQuery()
                .eq(PayOrder::getOutOrderNo, outOrderNo).in(PayOrder::getBizType, INTERNAL_BIZ)
                .orderByDesc(PayOrder::getId).last("LIMIT 1")));
        if (found == null) {
            return;
        }
        PayOrder o = orders.lockById(found.getId());
        StlPayment link = new StlPayment();
        link.setId(stlPaymentId);
        link.setPayOrderId(o.getId());
        link.setMarket(o.getMarket());
        DataScopeContext.executeWithoutScope(() -> attempts.updateById(link));
        PayOrderStatus from = PayOrderStatus.valueOf(o.getStatus());
        if (PayOrderStateMachine.allows(from, PayOrderEvent.ATTEMPT_OPENED)) {
            PayOrder patch = new PayOrder();
            patch.setId(o.getId());
            patch.setStatus(PayOrderStateMachine.next(from, PayOrderEvent.ATTEMPT_OPENED).name());
            DataScopeContext.executeWithoutScope(() -> orders.updateById(patch));
        }
    }

    private PayOrder byOutOrderNo(Long appId, String outOrderNo) {
        return DataScopeContext.executeWithoutScope(() -> orders.selectOne(Wrappers.<PayOrder>lambdaQuery()
                .eq(PayOrder::getPayAppId, appId).eq(PayOrder::getOutOrderNo, outOrderNo)));
    }

    private static PayOrder sameRequest(PayOrder existing, String sha) {
        if (!Objects.equals(existing.getRequestSha256(), sha)) {
            throw BizException.of(ErrorCode.PAY_ORDER_CONFLICT);
        }
        return existing;
    }

    /**
     * 建单请求的规范化摘要：决定「是不是同一笔钱」的字段。商品标题、截止时间不算 ——
     * 调用方重试时它们可能被重新拼过，而那不是另一笔钱。
     */
    static String digest(Long appId, CreatePaymentCmd c) {
        String canonical = String.join("|", String.valueOf(appId), c.bizType(), c.bizNo(), c.outOrderNo(),
                String.valueOf(c.storeId()), c.market(), c.currency(), String.valueOf(c.amountMinor()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String cut(String s, int max) {
        if (s == null || s.isBlank()) {
            return "-";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
