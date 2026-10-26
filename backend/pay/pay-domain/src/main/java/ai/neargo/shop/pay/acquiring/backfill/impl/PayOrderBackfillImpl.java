package ai.neargo.shop.pay.acquiring.backfill.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.pay.acquiring.backfill.PayOrderBackfill;
import ai.neargo.shop.pay.acquiring.entity.PayOrder;
import ai.neargo.shop.pay.acquiring.port.InternalCallers;
import ai.neargo.shop.pay.acquiring.statemachine.PayOrderStatus;
import ai.neargo.shop.pay.entity.StlPayment;
import ai.neargo.shop.pay.funds.FundsPaths;
import ai.neargo.shop.pay.ledger.LedgerVoucher;
import ai.neargo.shop.pay.ledger.service.LedgerService;
import ai.neargo.shop.pay.mapper.SettleMappers.PaymentMapper;
import ai.neargo.shop.pay.mapper.acquiring.AcquiringMappers.PayOrderMapper;
import ai.neargo.shop.spi.pay.PaymentPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** {@link PayOrderBackfill} 的实现。一个订单号一个事务 */
@Service
public class PayOrderBackfillImpl implements PayOrderBackfill {

    private final PaymentMapper attempts;
    private final PayOrderMapper orders;
    private final InternalCallers callers;
    private final FundsPaths funds;
    private final LedgerService ledger;
    private final TransactionTemplate tx;

    public PayOrderBackfillImpl(PaymentMapper attempts, PayOrderMapper orders, InternalCallers callers,
                                FundsPaths funds, LedgerService ledger,
                                @Qualifier("payTxManager") PlatformTransactionManager payTxManager) {
        this.attempts = attempts;
        this.orders = orders;
        this.callers = callers;
        this.funds = funds;
        this.ledger = ledger;
        this.tx = new TransactionTemplate(payTxManager);
    }

    @Override
    public List<String> pendingOrderNos(int limit) {
        return DataScopeContext.executeWithoutScope(() -> attempts.selectList(Wrappers.<StlPayment>lambdaQuery()
                        .select(StlPayment::getOrderNo).eq(StlPayment::getDirection, StlPayment.PAY)
                        .isNull(StlPayment::getPayOrderId).groupBy(StlPayment::getOrderNo)
                        .orderByAsc(StlPayment::getOrderNo).last("LIMIT " + Math.max(1, limit))))
                .stream().map(StlPayment::getOrderNo).filter(Objects::nonNull).toList();
    }

    @Override
    public Long backfill(String orderNo, String bizType, Long storeId, String storeName, String entityName,
                         String market, String fundsPath) {
        return tx.execute(s -> {
            List<StlPayment> rows = DataScopeContext.executeWithoutScope(() -> attempts.selectList(
                    Wrappers.<StlPayment>lambdaQuery().eq(StlPayment::getOrderNo, orderNo).orderByAsc(StlPayment::getId)));
            List<StlPayment> pays = rows.stream().filter(r -> StlPayment.PAY.equals(r.getDirection())).toList();
            if (pays.stream().noneMatch(r -> r.getPayOrderId() == null)) {
                return pays.isEmpty() ? null : pays.get(0).getPayOrderId();
            }
            Long appId = callers.appId(PaymentPort.FOOD_CHECK.equals(bizType) ? InternalCallers.FOOD : InternalCallers.MALL);
            PayOrder o = DataScopeContext.executeWithoutScope(() -> orders.selectOne(Wrappers.<PayOrder>lambdaQuery()
                    .eq(PayOrder::getPayAppId, appId).eq(PayOrder::getOutOrderNo, orderNo)));
            if (o == null) {
                o = create(orderNo, bizType, appId, storeId, storeName, entityName, market, fundsPath, rows, pays);
            }
            Long id = o.getId();
            for (StlPayment r : rows) {
                if (r.getPayOrderId() == null) {
                    StlPayment link = new StlPayment();
                    link.setId(r.getId());
                    link.setPayOrderId(id);
                    link.setMarket(market);
                    DataScopeContext.executeWithoutScope(() -> attempts.updateById(link));
                }
            }
            replayOpening(o);
            return id;
        });
    }

    private PayOrder create(String orderNo, String bizType, Long appId, Long storeId, String storeName,
                            String entityName, String market, String fundsPath, List<StlPayment> rows,
                            List<StlPayment> pays) {
        StlPayment paid = pays.stream().filter(r -> StlPayment.SUCCESS.equals(r.getStatus())).findFirst().orElse(null);
        StlPayment first = pays.get(0);
        long refunded = rows.stream().filter(r -> StlPayment.REFUND.equals(r.getDirection())
                && StlPayment.SUCCESS.equals(r.getStatus())).mapToLong(r -> nz(r.getAmountMinor())).sum();
        boolean open = pays.stream().anyMatch(r -> StlPayment.PENDING.equals(r.getStatus())
                || StlPayment.INIT.equals(r.getStatus()));
        PayOrderStatus status = paid != null
                ? (refunded <= 0 ? PayOrderStatus.SUCCEEDED
                : refunded >= nz(paid.getAmountMinor()) ? PayOrderStatus.REFUNDED : PayOrderStatus.PARTIALLY_REFUNDED)
                : open ? PayOrderStatus.AWAITING_PAYMENT : PayOrderStatus.CLOSED;
        PayOrder o = new PayOrder();
        o.setPayOrderNo(BizKey.next(BizKey.PAY_ORDER));
        o.setPayAppId(appId);
        o.setOutOrderNo(orderNo);
        o.setBizType(bizType);
        o.setBizNo(orderNo);
        o.setStoreId(storeId);
        o.setStoreNameSnapshot(cut(storeName));
        o.setEntityNameSnapshot(cut(entityName));
        o.setMarket(market);
        o.setCurrency(first.getCurrency());
        o.setAmountMinor(nz(first.getAmountMinor()));
        o.setPaidMinor(paid == null ? 0 : nz(paid.getAmountMinor()));
        o.setRefundedMinor(refunded);
        o.setReservedRefundMinor(0L);
        o.setSubject("-");
        o.setScene("ONLINE");
        o.setPayChannel(first.getPayChannel());
        o.setFundsPath(fundsPath);
        o.setStatus(status.name());
        o.setSourceType("INTERNAL");
        o.setSourceId(appId);
        o.setRequestSha256(sha256("BACKFILL|" + appId + "|" + orderNo));
        o.setExpireAt(first.getCreatedAt() == null ? LocalDateTime.now(ZoneOffset.UTC) : first.getCreatedAt());
        if (paid != null && paid.getSucceededAt() != null) {
            o.setSucceededAt(LocalDateTime.ofInstant(Instant.ofEpochMilli(paid.getSucceededAt()), ZoneOffset.UTC));
        }
        if (status == PayOrderStatus.CLOSED) {
            o.setClosedAt(LocalDateTime.now(ZoneOffset.UTC));
        }
        DataScopeContext.executeWithoutScope(() -> orders.insert(o));
        return o;
    }

    /** B7 流水期初：按支付单的实收与已退回放两张凭证（幂等键带 BACKFILL，不与之后的实时记账撞） */
    private void replayOpening(PayOrder o) {
        if (nz(o.getPaidMinor()) <= 0) {
            return;
        }
        funds.of(o.getFundsPath()).ifPresent(strategy -> {
            LedgerVoucher pay = strategy.onPaymentSucceeded(o, o.getPaidMinor(), o.getPayChannel());
            ledger.post(opening(pay, "PAY_SUCCEEDED|BACKFILL|" + o.getId()));
            if (nz(o.getRefundedMinor()) > 0) {
                LedgerVoucher refund = strategy.onRefundSucceeded(o, o.getId(), o.getRefundedMinor(), o.getPayChannel());
                ledger.post(opening(refund, "REFUND_SUCCEEDED|BACKFILL|" + o.getId()));
            }
        });
    }

    private static LedgerVoucher opening(LedgerVoucher v, String idem) {
        return new LedgerVoucher(v.voucherType(), v.refType(), v.refId(), idem, v.market(), v.currency(), v.bizDate(),
                v.fundsPath(), v.sourceType(), v.sourceId(), "B7 流水期初回放", v.lines());
    }

    private static String cut(String s) {
        return s == null || s.isBlank() ? "-" : s.length() <= 128 ? s : s.substring(0, 128);
    }

    private static long nz(Long v) {
        return v == null ? 0 : v;
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
