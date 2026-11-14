package ai.neargo.shop.pay.acquiring.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.pay.acquiring.entity.PayException;
import ai.neargo.shop.pay.acquiring.entity.PayOrder;
import ai.neargo.shop.pay.acquiring.entity.PayRefund;
import ai.neargo.shop.pay.acquiring.service.PayOpsQueryService;
import ai.neargo.shop.pay.entity.StlPayment;
import ai.neargo.shop.pay.ledger.entity.PayLedgerEntry;
import ai.neargo.shop.pay.ledger.entity.PayLedgerVoucher;
import ai.neargo.shop.pay.mapper.SettleMappers.PaymentMapper;
import ai.neargo.shop.pay.mapper.acquiring.AcquiringMappers.PayExceptionMapper;
import ai.neargo.shop.pay.mapper.acquiring.AcquiringMappers.PayOrderMapper;
import ai.neargo.shop.pay.mapper.acquiring.AcquiringMappers.PayRefundMapper;
import ai.neargo.shop.pay.mapper.ledger.LedgerMappers.LedgerEntryMapper;
import ai.neargo.shop.pay.mapper.ledger.LedgerMappers.LedgerVoucherMapper;
import ai.neargo.shop.pay.mapper.notify.NotifyMappers.PayBizNotifyMapper;
import ai.neargo.shop.pay.notify.entity.PayBizNotify;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** {@link PayOpsQueryService} 的实现（TDD-支付服务-I5接入层 S9）。全是只读，按 id 倒序、每次有上限 */
@Service
public class PayOpsQueryServiceImpl implements PayOpsQueryService {

    private static final int MAX = 200;

    private final PayOrderMapper orders;
    private final PayRefundMapper refunds;
    private final PaymentMapper attempts;
    private final LedgerEntryMapper entries;
    private final LedgerVoucherMapper vouchers;
    private final PayBizNotifyMapper notifies;
    private final PayExceptionMapper exceptions;

    public PayOpsQueryServiceImpl(PayOrderMapper orders, PayRefundMapper refunds, PaymentMapper attempts,
                                  LedgerEntryMapper entries, LedgerVoucherMapper vouchers, PayBizNotifyMapper notifies,
                                  PayExceptionMapper exceptions) {
        this.orders = orders;
        this.refunds = refunds;
        this.attempts = attempts;
        this.entries = entries;
        this.vouchers = vouchers;
        this.notifies = notifies;
        this.exceptions = exceptions;
    }

    @Override
    public List<OrderRow> orders(OrderFilter f, int page, int size) {
        int n = Math.max(1, Math.min(size, MAX));
        int offset = Math.max(0, page - 1) * n;
        return DataScopeContext.executeWithoutScope(() -> orders.selectList(Wrappers.<PayOrder>lambdaQuery()
                .eq(present(f.status()), PayOrder::getStatus, f.status())
                .eq(present(f.payOrderNo()), PayOrder::getPayOrderNo, f.payOrderNo())
                .eq(present(f.outOrderNo()), PayOrder::getOutOrderNo, f.outOrderNo())
                .eq(f.storeId() != null, PayOrder::getStoreId, f.storeId())
                .eq(present(f.bizType()), PayOrder::getBizType, f.bizType())
                .orderByDesc(PayOrder::getId).last("LIMIT " + n + " OFFSET " + offset)))
                .stream().map(PayOpsQueryServiceImpl::row).toList();
    }

    @Override
    public OrderDetail detail(String payOrderNo) {
        PayOrder o = payOrderNo == null ? null : DataScopeContext.executeWithoutScope(() -> orders.selectOne(
                Wrappers.<PayOrder>lambdaQuery().eq(PayOrder::getPayOrderNo, payOrderNo)));
        if (o == null) {
            return null;
        }
        List<AttemptRow> tries = DataScopeContext.executeWithoutScope(() -> attempts.selectList(
                Wrappers.<StlPayment>lambdaQuery().eq(StlPayment::getPayOrderId, o.getId()).orderByAsc(StlPayment::getId)))
                .stream().map(a -> new AttemptRow(a.getPaymentNo(), a.getDirection(), a.getOutTradeNo(), a.getTradeNo(),
                        a.getPayChannel(), nz(a.getAmountMinor()), a.getCurrency(), a.getStatus(), a.getPayMerchantId(),
                        a.getFundsPath(), a.getSucceededAt())).toList();
        List<RefundRow> rs = DataScopeContext.executeWithoutScope(() -> refunds.selectList(
                Wrappers.<PayRefund>lambdaQuery().eq(PayRefund::getPayOrderId, o.getId()).orderByAsc(PayRefund::getId)))
                .stream().map(r -> refundRow(r, o.getPayOrderNo())).toList();
        Set<Long> voucherIds = DataScopeContext.executeWithoutScope(() -> entries.selectList(
                Wrappers.<PayLedgerEntry>lambdaQuery().eq(PayLedgerEntry::getPayOrderId, o.getId())))
                .stream().map(PayLedgerEntry::getPayLedgerVoucherId).collect(Collectors.toSet());
        List<VoucherRow> vs = voucherIds.isEmpty() ? List.of() : DataScopeContext.executeWithoutScope(
                () -> vouchers.selectBatchIds(voucherIds)).stream()
                .sorted((a, b) -> Long.compare(a.getId(), b.getId()))
                .map(v -> new VoucherRow(v.getVoucherNo(), v.getVoucherType(), nz(v.getTotalMinor()), v.getCurrency(),
                        v.getFundsPath(), v.getBizDate() == null ? null : v.getBizDate().toString(), v.getMemo())).toList();
        List<NotifyRow> ns = DataScopeContext.executeWithoutScope(() -> notifies.selectList(
                Wrappers.<PayBizNotify>lambdaQuery().eq(PayBizNotify::getPayOrderId, o.getId()).orderByAsc(PayBizNotify::getId)))
                .stream().map(PayOpsQueryServiceImpl::notifyRow).toList();
        List<ExceptionRow> es = DataScopeContext.executeWithoutScope(() -> exceptions.selectList(
                Wrappers.<PayException>lambdaQuery().eq(PayException::getPayOrderId, o.getId()).orderByAsc(PayException::getId)))
                .stream().map(e -> exceptionRow(e, o.getPayOrderNo())).toList();
        return new OrderDetail(row(o), tries, rs, vs, ns, es);
    }

    @Override
    public List<RefundRow> refunds(String status, int limit) {
        List<PayRefund> rs = DataScopeContext.executeWithoutScope(() -> refunds.selectList(Wrappers.<PayRefund>lambdaQuery()
                .eq(present(status), PayRefund::getStatus, status).orderByDesc(PayRefund::getId)
                .last("LIMIT " + Math.max(1, Math.min(limit, MAX)))));
        Map<Long, String> nos = orderNos(rs.stream().map(PayRefund::getPayOrderId).collect(Collectors.toSet()));
        return rs.stream().map(r -> refundRow(r, nos.get(r.getPayOrderId()))).toList();
    }

    @Override
    public List<NotifyRow> notifies(String status, int limit) {
        return DataScopeContext.executeWithoutScope(() -> notifies.selectList(Wrappers.<PayBizNotify>lambdaQuery()
                .eq(present(status), PayBizNotify::getStatus, status).orderByDesc(PayBizNotify::getId)
                .last("LIMIT " + Math.max(1, Math.min(limit, MAX))))).stream().map(PayOpsQueryServiceImpl::notifyRow).toList();
    }

    @Override
    public List<ExceptionRow> exceptions(String status, int limit) {
        List<PayException> es = DataScopeContext.executeWithoutScope(() -> exceptions.selectList(
                Wrappers.<PayException>lambdaQuery().eq(present(status), PayException::getStatus, status)
                        .orderByDesc(PayException::getId).last("LIMIT " + Math.max(1, Math.min(limit, MAX)))));
        Map<Long, String> nos = orderNos(es.stream().map(PayException::getPayOrderId).collect(Collectors.toSet()));
        return es.stream().map(e -> exceptionRow(e, nos.get(e.getPayOrderId()))).toList();
    }

    private Map<Long, String> orderNos(Set<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return DataScopeContext.executeWithoutScope(() -> orders.selectBatchIds(ids)).stream()
                .collect(Collectors.toMap(PayOrder::getId, PayOrder::getPayOrderNo, (a, b) -> a));
    }

    private static OrderRow row(PayOrder o) {
        return new OrderRow(o.getPayOrderNo(), o.getOutOrderNo(), o.getBizType(), o.getStoreId(), o.getStoreNameSnapshot(),
                o.getMarket(), o.getCurrency(), nz(o.getAmountMinor()), nz(o.getPaidMinor()), nz(o.getRefundedMinor()),
                o.getStatus(), o.getPayChannel(), o.getPayMethod(), o.getFundsPath(), o.getSourceType(),
                epoch(o.getCreatedAt()), epoch(o.getSucceededAt()));
    }

    private static RefundRow refundRow(PayRefund r, String payOrderNo) {
        return new RefundRow(r.getPayRefundNo(), payOrderNo, nz(r.getAmountMinor()), r.getCurrency(), r.getStatus(),
                r.getReason(), r.getRequestedBy(), r.getSourceType(), r.getHitReasons(), epoch(r.getCreatedAt()));
    }

    private static NotifyRow notifyRow(PayBizNotify n) {
        return new NotifyRow(n.getId(), n.getEventType(), n.getEventKey(), n.getTargetType(), n.getTargetUrl(), n.getStatus(),
                n.getAttempts() == null ? 0 : n.getAttempts(), epoch(n.getNextAt()), epoch(n.getDeliveredAt()),
                epoch(n.getCreatedAt()));
    }

    private static ExceptionRow exceptionRow(PayException e, String payOrderNo) {
        return new ExceptionRow(e.getId(), e.getKind(), payOrderNo, nz(e.getAmountMinor()), e.getCurrency(), e.getStatus(),
                e.getResolution(), e.getNote(), e.getHandledBy(), epoch(e.getCreatedAt()));
    }

    /** 「有值才作条件」 */
    private static boolean present(String s) {
        return s != null && !s.isBlank();
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }

    private static Long epoch(LocalDateTime t) {
        return t == null ? null : t.toInstant(ZoneOffset.UTC).toEpochMilli();
    }
}
