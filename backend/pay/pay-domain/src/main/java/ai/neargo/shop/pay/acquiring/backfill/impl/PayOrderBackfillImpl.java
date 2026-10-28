package ai.neargo.shop.pay.acquiring.backfill.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.pay.acquiring.backfill.PayOrderBackfill;
import ai.neargo.shop.pay.acquiring.entity.PayOrder;
import ai.neargo.shop.pay.acquiring.entity.PayRefund;
import ai.neargo.shop.pay.acquiring.port.InternalCallers;
import ai.neargo.shop.pay.acquiring.statemachine.PayOrderStatus;
import ai.neargo.shop.pay.entity.StlPayment;
import ai.neargo.shop.pay.funds.FundsPaths;
import ai.neargo.shop.pay.ledger.LedgerVoucher;
import ai.neargo.shop.pay.ledger.service.LedgerService;
import ai.neargo.shop.pay.mapper.SettleMappers.PaymentMapper;
import ai.neargo.shop.pay.mapper.acquiring.AcquiringMappers.PayOrderMapper;
import ai.neargo.shop.pay.mapper.acquiring.AcquiringMappers.PayRefundMapper;
import ai.neargo.shop.pay.service.PaymentLedgerService.RefundTicket;
import ai.neargo.shop.spi.pay.PaymentPort;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** One order per short transaction. Uncertain historical facts cannot become spendable refund balances. */
@Service
public class PayOrderBackfillImpl implements PayOrderBackfill {
    private final PaymentMapper attempts;
    private final PayOrderMapper orders;
    private final PayRefundMapper refunds;
    private final InternalCallers callers;
    private final FundsPaths funds;
    private final LedgerService ledger;
    private final TransactionTemplate tx;
    private final TransactionTemplate read;
    private final ObjectMapper json = new ObjectMapper();

    public PayOrderBackfillImpl(PaymentMapper attempts, PayOrderMapper orders, PayRefundMapper refunds,
                                InternalCallers callers,
                                FundsPaths funds, LedgerService ledger,
                                @Qualifier("payTxManager") PlatformTransactionManager payTxManager) {
        this.attempts = attempts;
        this.orders = orders;
        this.refunds = refunds;
        this.callers = callers;
        this.funds = funds;
        this.ledger = ledger;
        this.tx = new TransactionTemplate(payTxManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.read = new TransactionTemplate(payTxManager);
        read.setReadOnly(true);
    }

    @Override
    public List<String> pendingOrderNos(String afterOrderNo, int limit) {
        return DataScopeContext.executeWithoutScope(() -> attempts.selectList(Wrappers.<StlPayment>lambdaQuery()
                .select(StlPayment::getOrderNo).in(StlPayment::getDirection, StlPayment.PAY, StlPayment.REFUND)
                .isNull(StlPayment::getPayOrderId).isNotNull(StlPayment::getOrderNo).ne(StlPayment::getOrderNo, "")
                .gt(text(afterOrderNo), StlPayment::getOrderNo, afterOrderNo)
                .groupBy(StlPayment::getOrderNo).orderByAsc(StlPayment::getOrderNo)
                .last("LIMIT " + Math.max(1, Math.min(limit, 1000)))))
                .stream().map(StlPayment::getOrderNo).toList();
    }

    @Override
    public Assessment inspect(String orderNo, String bizType, Long storeId, String storeName, String entityName,
                              String market, String fundsPath) {
        return read.execute(s -> plan(orderNo, bizType, storeId, market, fundsPath, false).assessment());
    }

    @Override
    public Long backfill(String orderNo, String bizType, Long storeId, String storeName, String entityName,
                         String market, String fundsPath) {
        return tx.execute(s -> {
            Plan plan = plan(orderNo, bizType, storeId, market, fundsPath, true);
            Assessment a = plan.assessment();
            if (a.status() == Status.BLOCKED) throw new IllegalStateException("PAY_BACKFILL_" + a.reason());
            if (a.status() == Status.ALREADY_LINKED) return a.payOrderId();
            if (a.status() == Status.LINKS_ONLY) {
                link(plan.rows(), a.payOrderId());
                return a.payOrderId();
            }
            PayOrder order = create(orderNo, bizType, storeId, storeName, entityName, market, plan);
            link(plan.rows(), order.getId());
            replayOpening(order);
            return order.getId();
        });
    }

    private void link(List<StlPayment> rows, Long orderId) {
        for (StlPayment row : rows) {
            if (row.getPayOrderId() != null) continue;
            StlPayment patch = new StlPayment();
            patch.setId(row.getId());
            patch.setPayOrderId(orderId);
            // Association only: historical market/routing/receipt fields remain exactly as recorded.
            DataScopeContext.executeWithoutScope(() -> attempts.updateById(patch));
        }
    }

    private Plan plan(String no, String bizType, Long storeId, String market, String expectedPath, boolean lock) {
        try {
            require(text(no) && storeId != null && storeId > 0 && text(market), "SOURCE_IDENTITY_REQUIRED");
            require(PaymentPort.MALL_ORDER.equals(bizType) || PaymentPort.FOOD_CHECK.equals(bizType),
                    "BUSINESS_TYPE_UNKNOWN");
            List<StlPayment> rows = DataScopeContext.executeWithoutScope(() -> attempts.selectList(
                    Wrappers.<StlPayment>lambdaQuery().eq(StlPayment::getOrderNo, no)
                            .in(StlPayment::getDirection, StlPayment.PAY, StlPayment.REFUND)
                            .orderByAsc(StlPayment::getId).last(lock, "FOR UPDATE")));
            List<StlPayment> pays = rows.stream().filter(r -> StlPayment.PAY.equals(r.getDirection())).toList();
            require(!pays.isEmpty(), "ORIGINAL_PAYMENT_REQUIRED");
            require(rows.stream().allMatch(r -> "MAIN".equals(r.getTenantNo())), "TENANT_UNSUPPORTED");
            Long appId = callers.appId(
                    PaymentPort.FOOD_CHECK.equals(bizType) ? InternalCallers.FOOD : InternalCallers.MALL);
            long otherOwners = DataScopeContext.executeWithoutScope(() -> orders.selectCount(
                    Wrappers.<PayOrder>lambdaQuery().eq(PayOrder::getOutOrderNo, no)
                            .eq(PayOrder::getSourceType, "INTERNAL")
                            .in(PayOrder::getBizType, PaymentPort.MALL_ORDER, PaymentPort.FOOD_CHECK)
                            .ne(PayOrder::getPayAppId, appId)));
            require(otherOwners == 0, "EXISTING_ORDER_OWNER_CONFLICT");
            // Never change or replay existing orders. A plain current read avoids reversing attachAttempt's locks.
            PayOrder existing = DataScopeContext.executeWithoutScope(() -> orders.selectOne(
                    Wrappers.<PayOrder>lambdaQuery().eq(PayOrder::getPayAppId, appId).eq(PayOrder::getOutOrderNo, no)));
            if (existing != null) {
                boolean complete = rows.stream().allMatch(r -> Objects.equals(r.getPayOrderId(), existing.getId()));
                if (!complete) {
                    require(pays.stream().allMatch(r -> Objects.equals(r.getPayOrderId(), existing.getId())),
                            "EXISTING_ORDER_REQUIRES_REVIEW");
                    require(funds.of(existing.getFundsPath()).isPresent(), "EXISTING_ORDER_FUNDS_UNVERIFIED");
                    for (StlPayment row : rows) {
                        if (row.getPayOrderId() == null) proveRefundLink(row, pays, existing);
                        else require(Objects.equals(row.getPayOrderId(), existing.getId()), "EXISTING_LINK_CONFLICT");
                    }
                }
                require(Objects.equals(existing.getStoreId(), storeId) && Objects.equals(existing.getMarket(), market),
                        "SOURCE_IDENTITY_CONFLICT");
                StlPayment anchor = pays.stream().filter(r -> StlPayment.SUCCESS.equals(r.getStatus()))
                        .findFirst().orElse(pays.get(pays.size() - 1));
                return new Plan(new Assessment(complete ? Status.ALREADY_LINKED : Status.LINKS_ONLY,
                        null, existing.getId(), existing.getFundsPath(),
                        existing.getCurrency(), nz(existing.getAmountMinor()), nz(existing.getPaidMinor()),
                        nz(existing.getRefundedMinor()), existing.getPayChannel(), List.of(), List.of(),
                        anchor.getUserNo(), anchor.getOutTradeNo()),
                        null, rows, appId, null);
            }
            require(rows.stream().allMatch(r -> r.getPayOrderId() == null && r.getPayRefundId() == null),
                    "EXISTING_LINK_CONFLICT");
            List<StlPayment> paid = pays.stream().filter(r -> StlPayment.SUCCESS.equals(r.getStatus())).toList();
            List<StlPayment> open = pays.stream().filter(r -> StlPayment.PENDING.equals(r.getStatus())
                    || StlPayment.INIT.equals(r.getStatus())).toList();
            require(paid.size() <= 1, "MULTIPLE_SUCCESSFUL_PAYMENTS");
            require(open.size() <= 1 && (paid.isEmpty() || open.isEmpty()), "PAYMENT_IN_FLIGHT_CONFLICT");
            StlPayment selected = !paid.isEmpty() ? paid.get(0)
                    : !open.isEmpty() ? open.get(0) : pays.get(pays.size() - 1);
            require("MAIN".equals(selected.getTenantNo()), "TENANT_UNSUPPORTED");
            require(selected.getAmountMinor() != null && selected.getAmountMinor() >= 0
                    && text(selected.getCurrency()) && text(selected.getUserNo()) && text(selected.getPayChannel()),
                    "PAYMENT_IDENTITY_REQUIRED");
            for (StlPayment pay : pays) validatePayment(pay, selected, market);
            require(!"FROZEN".equals(selected.getRefundCapacityState()), "ORIGINAL_PAYMENT_FROZEN");
            if (!paid.isEmpty() && selected.getAmountMinor() > 0) {
                require(text(selected.getTradeNo()) && selected.getSucceededAt() != null
                        && selected.getSucceededAt() > 0,
                        "PAYMENT_RECEIPT_REQUIRED");
            }
            PaymentSnapshot request = request(selected);
            var routes = request == null ? List.<StoreSnapshot>of() : request.stores();
            require(routes.stream().allMatch(r -> r != null && r.route() != null
                    && text(r.route().storeNo()) && text(r.route().merchantNo())
                    && Objects.equals(r.route().channel(), selected.getPayChannel())), "ROUTE_SNAPSHOT_INVALID");
            List<String> stores = routes.stream().map(r -> r.route().storeNo()).distinct().sorted().toList();
            List<String> merchants = routes.stream().map(r -> r.route().merchantNo()).distinct().sorted().toList();
            require(stores.size() <= 1 && merchants.size() <= 1, "SOURCE_STORE_AMBIGUOUS");
            List<String> paths = routes.stream().map(r -> r.route().fundsPath()).filter(PayOrderBackfillImpl::text)
                    .distinct().toList();
            require(paths.size() <= 1, "FUNDS_PATH_CONFLICT");
            String path = text(selected.getFundsPath()) ? selected.getFundsPath()
                    : paths.isEmpty() ? null : paths.get(0);
            require(text(path), "FROZEN_FUNDS_PATH_REQUIRED");
            require(paths.isEmpty() || path.equals(paths.get(0)), "FUNDS_PATH_CONFLICT");
            require(!text(expectedPath) || path.equals(expectedPath), "FUNDS_PATH_CONFLICT");
            require(funds.of(path).isPresent(), "FUNDS_PATH_UNSUPPORTED");
            long refunded = 0;
            for (StlPayment row : rows) {
                if (!StlPayment.REFUND.equals(row.getDirection())) continue;
                require(!paid.isEmpty(), "REFUND_WITHOUT_PAYMENT");
                validateRefund(row, selected);
                if (StlPayment.SUCCESS.equals(row.getStatus())) {
                    refunded = Math.addExact(refunded, row.getAmountMinor());
                }
            }
            long received = paid.isEmpty() ? 0 : selected.getAmountMinor();
            require(refunded <= received, "REFUND_EXCEEDS_PAYMENT");
            PayOrderStatus status;
            if (!paid.isEmpty()) status = refunded == 0 ? PayOrderStatus.SUCCEEDED
                    : refunded == received ? PayOrderStatus.REFUNDED : PayOrderStatus.PARTIALLY_REFUNDED;
            else if (!open.isEmpty()) status = PayOrderStatus.AWAITING_PAYMENT;
            else if (pays.stream().allMatch(r -> StlPayment.CLOSED.equals(r.getStatus()))) {
                status = PayOrderStatus.CLOSED;
            }
            else if (pays.stream().allMatch(r -> StlPayment.FAILED.equals(r.getStatus()))) {
                status = PayOrderStatus.FAILED;
            }
            else throw new Blocked("PAYMENT_STATE_AMBIGUOUS");
            return new Plan(new Assessment(Status.READY, null, null, path, selected.getCurrency(),
                    selected.getAmountMinor(), received, refunded, selected.getPayChannel(), stores, merchants,
                    selected.getUserNo(), selected.getOutTradeNo()),
                    selected, rows, appId, status);
        } catch (Blocked blocked) {
            return new Plan(new Assessment(Status.BLOCKED, blocked.getMessage(), null, null, null, 0, 0, 0, null,
                    List.of(), List.of(), null, null), null, List.of(), null, null);
        } catch (ArithmeticException overflow) {
            return new Plan(new Assessment(Status.BLOCKED, "AMOUNT_OVERFLOW", null, null, null, 0, 0, 0, null,
                    List.of(), List.of(), null, null), null, List.of(), null, null);
        }
    }

    private void proveRefundLink(StlPayment row, List<StlPayment> pays, PayOrder order) {
        require(StlPayment.REFUND.equals(row.getDirection()) && row.getPayRefundId() != null,
                "EXISTING_REFUND_LINK_UNPROVEN");
        PayRefund intent = DataScopeContext.executeWithoutScope(() -> refunds.selectById(row.getPayRefundId()));
        StlPayment origin = pays.stream().filter(p -> Objects.equals(p.getPaymentNo(), row.getOriginPaymentNo()))
                .findFirst().orElse(null);
        require(intent != null && origin != null && Objects.equals(intent.getPayOrderId(), order.getId())
                && Objects.equals(intent.getPayAppId(), order.getPayAppId())
                && Objects.equals(intent.getStoreId(), order.getStoreId())
                && Objects.equals(intent.getStlPaymentId(), row.getId())
                && Objects.equals(intent.getOutRefundNo(), row.getOutTradeNo())
                && Objects.equals(intent.getOutRefundNo(), row.getRefundExecutionNo())
                && Objects.equals(intent.getBizRefundNo(), row.getAfterSaleNo())
                && Objects.equals(intent.getAmountMinor(), row.getAmountMinor())
                && Objects.equals(intent.getCurrency(), row.getCurrency())
                && Objects.equals(origin.getUserNo(), row.getUserNo())
                && Objects.equals(origin.getPayChannel(), row.getPayChannel())
                && Objects.equals(origin.getCurrency(), row.getCurrency()), "EXISTING_REFUND_LINK_UNPROVEN");
    }

    private void validatePayment(StlPayment pay, StlPayment selected, String market) {
        require(Objects.equals(pay.getTenantNo(), selected.getTenantNo()), "TENANT_CONFLICT");
        require(text(pay.getPaymentNo()) && text(pay.getOutTradeNo()) && pay.getCreatedAt() != null,
                "PAYMENT_IDENTITY_REQUIRED");
        require(List.of(StlPayment.INIT, StlPayment.PENDING, StlPayment.SUCCESS, StlPayment.FAILED, StlPayment.CLOSED)
                .contains(pay.getStatus() == null ? "" : pay.getStatus()), "PAYMENT_STATE_UNKNOWN");
        require(Objects.equals(pay.getAmountMinor(), selected.getAmountMinor())
                && Objects.equals(pay.getCurrency(), selected.getCurrency())
                && Objects.equals(pay.getUserNo(), selected.getUserNo()), "PAYMENT_FACTS_CONFLICT");
        require(!text(pay.getMarket()) || market.equals(pay.getMarket()), "MARKET_CONFLICT");
        require(pay.getChannelCurrency() == null || pay.getCurrency().equals(pay.getChannelCurrency()),
                "PAYMENT_RECEIPT_CONFLICT");
        require(!StlPayment.SUCCESS.equals(pay.getStatus()) || pay.getChannelAmountMinor() == null
                || pay.getAmountMinor().equals(pay.getChannelAmountMinor()), "PAYMENT_RECEIPT_CONFLICT");
        request(pay);
    }

    /** Read only the frozen financial facts; the backfill does not depend on a business-side command. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PaymentSnapshot(String orderNo, String userNo, String currency, String payChannel,
                                   Long amountMinor, String subject, List<StoreSnapshot> stores) {
        PaymentSnapshot {
            stores = stores == null ? List.of() : stores;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record StoreSnapshot(RouteSnapshot route) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RouteSnapshot(String storeNo, String merchantNo, String channel, String fundsPath) { }

    private PaymentSnapshot request(StlPayment row) {
        if (row.getPaymentRequestJson() == null) return null;
        PaymentSnapshot snapshot;
        try {
            snapshot = json.readValue(row.getPaymentRequestJson(), PaymentSnapshot.class);
        } catch (RuntimeException invalid) {
            throw new Blocked("PAYMENT_SNAPSHOT_INVALID");
        }
        require(snapshot != null && Objects.equals(snapshot.orderNo(), row.getOrderNo())
                && Objects.equals(snapshot.userNo(), row.getUserNo())
                && Objects.equals(snapshot.currency(), row.getCurrency())
                && Objects.equals(snapshot.payChannel(), row.getPayChannel())
                && row.getAmountMinor() != null && Objects.equals(snapshot.amountMinor(), row.getAmountMinor()),
                "PAYMENT_SNAPSHOT_CONFLICT");
        return snapshot;
    }

    private void validateRefund(StlPayment refund, StlPayment paid) {
        require(Objects.equals(refund.getTenantNo(), paid.getTenantNo()), "TENANT_CONFLICT");
        require(refund.getAmountMinor() != null && refund.getAmountMinor() > 0
                && Objects.equals(refund.getCurrency(), paid.getCurrency())
                && Objects.equals(refund.getPayChannel(), paid.getPayChannel())
                && Objects.equals(refund.getUserNo(), paid.getUserNo()), "REFUND_FACTS_CONFLICT");
        require(Objects.equals(refund.getOriginPaymentNo(), paid.getPaymentNo()), "REFUND_ORIGIN_REQUIRED");
        if (refund.getRefundRequestJson() != null) {
            RefundTicket ticket;
            try {
                ticket = json.readValue(refund.getRefundRequestJson(), RefundTicket.class);
            } catch (RuntimeException invalid) {
                throw new Blocked("REFUND_SNAPSHOT_INVALID");
            }
            require(ticket != null && Objects.equals(ticket.originOutTradeNo(), paid.getOutTradeNo())
                    && Objects.equals(ticket.originTradeNo(), paid.getTradeNo())
                    && Objects.equals(ticket.outRefundNo(), refund.getOutTradeNo())
                    && Objects.equals(ticket.payChannel(), paid.getPayChannel())
                    && ticket.originTotalMinor() == paid.getAmountMinor()
                    && ticket.amountMinor() == refund.getAmountMinor(), "REFUND_SNAPSHOT_CONFLICT");
        }
        require(!"LATE_SUCCESS".equals(refund.getRefundQueryState())
                && !"FROZEN".equals(refund.getRefundCapacityState()), "REFUND_PROOF_CONFLICT");
        if (StlPayment.FAILED.equals(refund.getStatus()) && "RELEASED".equals(refund.getRefundCapacityState())
                && text(refund.getRefundTerminalProof()) && text(refund.getRefundRecoveryProof())) return;
        require(StlPayment.SUCCESS.equals(refund.getStatus()), "REFUND_UNRESOLVED");
        require(!"RELEASED".equals(refund.getRefundCapacityState()) && !text(refund.getRefundTerminalProof()),
                "REFUND_PROOF_CONFLICT");
        if (refund.getRefundVerifiedAt() != null) {
            require(refund.getRefundVerifiedAt() > 0 && text(refund.getRefundVerifiedProviderNo())
                    && Objects.equals(refund.getRefundVerifiedMinor(), refund.getAmountMinor()),
                    "REFUND_RECEIPT_REQUIRED");
        } else require(text(refund.getTradeNo()) && refund.getSucceededAt() != null && refund.getSucceededAt() > 0,
                "REFUND_RECEIPT_REQUIRED");
    }

    private PayOrder create(String no, String bizType, Long storeId, String storeName, String entityName,
                            String market, Plan plan) {
        Assessment a = plan.assessment();
        StlPayment source = plan.selected();
        PayOrder o = new PayOrder();
        o.setPayOrderNo(BizKey.next(BizKey.PAY_ORDER));
        o.setPayAppId(plan.appId());
        o.setOutOrderNo(no);
        o.setBizType(bizType);
        o.setBizNo(no);
        o.setStoreId(storeId);
        o.setStoreNameSnapshot(cut(storeName));
        o.setEntityNameSnapshot(cut(entityName));
        o.setMarket(market);
        o.setCurrency(a.currency());
        o.setAmountMinor(a.amountMinor());
        o.setPaidMinor(a.paidMinor());
        o.setRefundedMinor(a.refundedMinor());
        o.setReservedRefundMinor(0L);
        PaymentSnapshot request = request(source);
        o.setSubject(request == null || !text(request.subject()) ? "-" : cut(request.subject()));
        o.setScene("ONLINE");
        o.setPayChannel(a.payChannel());
        o.setFundsPath(a.fundsPath());
        o.setPayMerchantId(source.getPayMerchantId());
        o.setSubMerchantId(source.getSubMerchantId());
        o.setSecKeyVersion(source.getSecKeyVersion());
        o.setPayMethod(source.getPayMethod());
        o.setStatus(plan.status().name());
        o.setSourceType("INTERNAL");
        o.setSourceId(plan.appId());
        o.setRequestSha256(sha256("BACKFILL|" + plan.appId() + "|" + no));
        o.setExpireAt(source.getCreatedAt());
        if (a.paidMinor() > 0) o.setSucceededAt(at(source.getSucceededAt()));
        if (plan.status() == PayOrderStatus.CLOSED && source.getClosedAt() != null) {
            o.setClosedAt(at(source.getClosedAt()));
        }
        DataScopeContext.executeWithoutScope(() -> orders.insert(o));
        return o;
    }

    private void replayOpening(PayOrder o) {
        if (nz(o.getPaidMinor()) <= 0) return;
        var strategy = funds.of(o.getFundsPath()).orElseThrow();
        ledger.post(opening(strategy.onPaymentSucceeded(o, o.getPaidMinor(), o.getPayChannel()),
                "PAY_SUCCEEDED|BACKFILL|" + o.getId(), o.getId()));
        if (nz(o.getRefundedMinor()) > 0) ledger.post(opening(
                strategy.onRefundSucceeded(o, o.getId(), o.getRefundedMinor(), o.getPayChannel()),
                "REFUND_SUCCEEDED|BACKFILL|" + o.getId(), o.getId()));
    }

    private static LedgerVoucher opening(LedgerVoucher v, String idem, Long orderId) {
        // Historical aggregate refunds have no real pay_refund; their opening reference is the payment order.
        return new LedgerVoucher(v.voucherType(), "PAY_ORDER", orderId, idem, v.market(), v.currency(), v.bizDate(),
                v.fundsPath(), v.sourceType(), v.sourceId(), "B7 流水期初回放", v.lines());
    }

    private record Plan(Assessment assessment, StlPayment selected, List<StlPayment> rows, Long appId,
                        PayOrderStatus status) { }
    private static class Blocked extends RuntimeException {
        Blocked(String reason) { super(reason); }
    }
    private static void require(boolean condition, String reason) { if (!condition) throw new Blocked(reason); }
    private static boolean text(String value) { return value != null && !value.isBlank(); }
    private static long nz(Long value) { return value == null ? 0 : value; }
    private static LocalDateTime at(long millis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneOffset.UTC);
    }
    private static String cut(String value) {
        return !text(value) ? "-" : value.length() <= 128 ? value : value.substring(0, 128);
    }
    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
