package ai.neargo.shop.trade.order.points.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.spi.points.*;
import ai.neargo.shop.spi.points.PointsPaymentSnapshotPort.VerifiedPayment;
import ai.neargo.shop.trade.entity.*;
import ai.neargo.shop.trade.mapper.TradeMappers.*;
import ai.neargo.shop.trade.order.mapper.OrderPointsRefundBindingMapper;
import ai.neargo.shop.trade.order.points.*;
import ai.neargo.shop.trade.order.refund.model.RefundPlan;
import ai.neargo.shop.trade.order.refund.service.RefundExecutionService;
import ai.neargo.shop.spi.settle.SettlePort;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.util.*;

@Service
public class OrderPointsRefundCoordinatorImpl implements OrderPointsRefundCoordinator {
    private final OrderPointsRefundBindingMapper mapper;
    private final OrderMapper orders;
    private final SubOrderMapper subs;
    private final OrderItemMapper items;
    private final AfterSaleMapper afterSales;
    private final OrderPointsBindingService bindings;
    private final RefundExecutionService executions;
    private final OrderPointsFactService facts;
    private final ObjectMapper json = new ObjectMapper();

    public OrderPointsRefundCoordinatorImpl(
            OrderPointsRefundBindingMapper mapper,
            OrderMapper orders,
            SubOrderMapper subs,
            OrderItemMapper items,
            AfterSaleMapper afterSales,
            OrderPointsBindingService bindings,
            RefundExecutionService executions,
            OrderPointsFactService facts) {
        this.mapper = mapper;
        this.orders = orders;
        this.subs = subs;
        this.items = items;
        this.afterSales = afterSales;
        this.bindings = bindings;
        this.executions = executions;
        this.facts = facts;
    }

    @Override
    @Transactional(value = "transactionManager", propagation = Propagation.MANDATORY)
    public Binding stage(String afterSaleNo, RefundPlan plan, VerifiedPayment payment) {
        return DataScopeContext.executeWithoutScope(
                () -> {
                    require(
                            plan != null && payment != null && afterSaleNo != null,
                            "POINTS_REFUND_SOURCE_INVALID");
                    var sub =
                            subs.selectOne(
                                    Wrappers.<OrdSubOrder>lambdaQuery()
                                            .eq(OrdSubOrder::getSubOrderNo, plan.subOrderNo())
                                            .last("FOR UPDATE"));
                    require(sub != null, "POINTS_REFUND_SOURCE_UNKNOWN");
                    var prior = decode(mapper.lock(afterSaleNo));
                    if (prior != null) {
                        require(
                                prior.subOrderNo().equals(plan.subOrderNo()),
                                "POINTS_REFUND_SOURCE_CONFLICT");
                        require(prior.command().payment().equals(payment.forSubOrder(plan.subOrderNo())),
                                "POINTS_REFUND_SOURCE_CONFLICT");
                        var execution = executions.inspect(afterSaleNo);
                        require(
                                execution != null && plan.equals(execution.plan()),
                                "POINTS_REFUND_SOURCE_CONFLICT");
                        return prior;
                    }
                    var as =
                            afterSales.selectOne(
                                    Wrappers.<OrdAfterSale>lambdaQuery()
                                            .eq(OrdAfterSale::getAfterSaleNo, afterSaleNo)
                                            .last("FOR UPDATE"));
                    var root =
                            orders.selectOne(
                                    Wrappers.<OrdOrder>lambdaQuery()
                                            .eq(OrdOrder::getOrderNo, sub.getOrderNo())
                                            .last("FOR UPDATE"));
                    require(
                            as != null
                                    && root != null
                                    && Boolean.TRUE.equals(root.getPointsIntegrated())
                                    && OrdOrder.PAID.equals(root.getStatus())
                                    && OrdAfterSale.REFUNDING.equals(as.getStatus())
                                    && afterSaleNo.equals(as.getAfterSaleNo())
                                    && plan.subOrderNo().equals(as.getSubOrderNo())
                                    && sub.getOrderNo().equals(payment.orderNo())
                                    && root.getUserNo().equals(payment.userNo())
                                    && root.getCurrency().equals(payment.currency())
                                    && Objects.equals(root.getPayAmount(), payment.amountMinor()),
                            "POINTS_REFUND_SOURCE_CONFLICT");
                    var execution = executions.inspect(afterSaleNo);
                    require(
                            execution != null && plan.equals(execution.plan()),
                            "POINTS_REFUND_SOURCE_CONFLICT");
                    var binding = bindings.find(plan.subOrderNo());
                    require(
                            binding != null
                                    && binding.phase() == OrderPointsBindingService.Phase.BOUND
                                    && binding.admission().orderNo().equals(root.getOrderNo())
                                    && binding.admission().storeNo().equals(sub.getStoreNo())
                                    && binding.admission().merchantNo().equals(sub.getEntityNo()),
                            "POINTS_REFUND_BINDING_MISSING");
                    var frozen = binding.snapshot().payment();
                    var paid = payment.forSubOrder(plan.subOrderNo());
                    require(
                            frozen.fundsPath().equals(paid.fundsPath())
                                    && frozen.channel().equals(paid.channel())
                                    && frozen.merchantNo().equals(paid.merchantNo())
                                    && frozen.storeNo().equals(paid.storeNo())
                                    && frozen.accountVersion().equals(paid.accountVersion())
                                    && Objects.equals(frozen.subMerchantId(), paid.subMerchantId())
                                    && frozen.orderTotalMinor() == paid.orderTotalMinor(),
                            "POINTS_REFUND_PAYMENT_MISMATCH");
                    var actual =
                            items.selectList(
                                    Wrappers.<OrdItem>lambdaQuery()
                                            .eq(OrdItem::getSubOrderNo, plan.subOrderNo()));
                    Map<String, OrdItem> byItem = new HashMap<>();
                    for (var item : actual)
                        if (!Boolean.TRUE.equals(item.getIsGift()))
                            require(
                                    byItem.put(String.valueOf(item.getId()), item) == null,
                                    "POINTS_REFUND_LINES_INVALID");
                    Map<String, Long> original = new HashMap<>();
                    for (var item : binding.snapshot().items())
                        require(
                                original.put(item.itemId(), item.goodsMinor()) == null,
                                "POINTS_REFUND_LINES_INVALID");
                    require(
                            byItem.keySet().equals(original.keySet()),
                            "POINTS_REFUND_LINES_INVALID");
                    Map<String, Long> newCash = new HashMap<>();
                    for (var line : plan.lines())
                        require(
                                line.amountMinor() >= 0
                                        && newCash.put(
                                                        String.valueOf(line.itemId()),
                                                        line.amountMinor())
                                                == null
                                        && byItem.containsKey(String.valueOf(line.itemId())),
                                "POINTS_REFUND_LINES_INVALID");
                    Map<String, Long> reservedCash = new HashMap<>();
                    Map<String, Long> reservedQty = new HashMap<>();
                    for (var row : mapper.forSubOrder(plan.subOrderNo())) {
                        String phase = (String) row.get("phase");
                        if ("COMMITTED".equals(phase) || "ABORTED".equals(phase)) continue;
                        String priorNo = (String) row.get("after_sale_no");
                        var reserved = executions.inspect(priorNo);
                        require(
                                reserved != null
                                        && reserved.preparation() != null
                                        && reserved.plan() != null
                                        && plan.subOrderNo().equals(reserved.plan().subOrderNo())
                                        && !"ABORTED".equals(reserved.preparation().state()),
                                "POINTS_REFUND_SOURCE_CONFLICT");
                        for (var line : reserved.plan().lines()) {
                            String id = String.valueOf(line.itemId());
                            require(byItem.containsKey(id), "POINTS_REFUND_LINES_INVALID");
                            reservedCash.merge(id, line.amountMinor(), Math::addExact);
                            reservedQty.merge(id, (long) line.qty(), Math::addExact);
                        }
                    }
                    List<PointsRefundPort.RefundLine> lines = new ArrayList<>();
                    long cumulative = 0;
                    for (var id : original.keySet().stream().sorted().toList()) {
                        var item = byItem.get(id);
                        require(item.getQty() != null && item.getQty() > 0,
                                "POINTS_REFUND_LINES_INVALID");
                        long cashOriginal = value(item.getPaidShareMinor());
                        long refunded =
                                Math.addExact(
                                        value(item.getRefundedMinor()),
                                        Math.addExact(
                                                reservedCash.getOrDefault(id, 0L),
                                                newCash.getOrDefault(id, 0L)));
                        require(refunded <= cashOriginal, "POINTS_REFUND_CAPACITY_EXCEEDED");
                        long gross = original.get(id);
                        long share =
                                cashOriginal > 0
                                        ? Math.multiplyExact(gross, refunded) / cashOriginal
                                        : Math.multiplyExact(
                                                        gross,
                                                        value(item.getRefundedQty())
                                                                + reservedQty.getOrDefault(id, 0L)
                                                                + plan.lines().stream()
                                                                        .filter(
                                                                                l ->
                                                                                        id.equals(
                                                                                                String
                                                                                                        .valueOf(
                                                                                                                l
                                                                                                                        .itemId())))
                                                                        .mapToLong(l -> l.qty())
                                                                        .sum())
                                                / item.getQty();
                        require(share <= gross, "POINTS_REFUND_CAPACITY_EXCEEDED");
                        lines.add(new PointsRefundPort.RefundLine(id, share));
                        cumulative = Math.addExact(cumulative, share);
                    }
                    long sourceVersion = as.getVersion() == null ? 0 : as.getVersion();
                    var command =
                            new PointsRefundPort.RefundCommand(
                                    afterSaleNo,
                                    plan.subOrderNo(),
                                    sub.getUserNo(),
                                    binding.admission().typeCode(),
                                    root.getCurrency(),
                                    sourceVersion,
                                    plan.totalMinor(),
                                    cumulative,
                                    lines,
                                    paid);
                    mapper.create(
                            afterSaleNo,
                            plan.subOrderNo(),
                            sourceVersion,
                            json.writeValueAsString(command));
                    return new Binding(
                            afterSaleNo, plan.subOrderNo(), Phase.STAGED, 0, command, null, null, null);
                });
    }

    @Override
    @Transactional(value = "transactionManager")
    public Binding acceptPreparation(String afterSaleNo, PointsRefundPort.RefundPreparation preparation) {
        return DataScopeContext.executeWithoutScope(() -> {
            var row=locked(afterSaleNo);
            var current=decode(row);
            require(preparation!=null && preparation.preparationNo()!=null
                    && preparation.typeCode().equals(current.command().typeCode())
                    && preparation.inputHash()!=null && !preparation.inputHash().isBlank()
                    && preparation.revision()>0 && preparation.cashOffsetMinor()>=0
                    && preparation.netCashMinor()>=0
                    && Math.addExact(preparation.cashOffsetMinor(),preparation.netCashMinor())
                    ==current.command().businessCashMinor(),"POINTS_REFUND_PREPARATION_MISMATCH");
            if(current.preparation()!=null) {
                require(current.preparation().equals(preparation),"POINTS_REFUND_PREPARATION_CONFLICT");
                return current;
            }
            require(current.phase()==Phase.STAGED,"POINTS_REFUND_PHASE_CONFLICT");
            require(mapper.change(afterSaleNo,current.version(),current.phase().name(),
                    Phase.PREPARED.name(),json.writeValueAsString(preparation),null)==1,
                    "POINTS_REFUND_PHASE_CONFLICT");
            return inspect(afterSaleNo);
        });
    }

    @Override
    @Transactional(value = "transactionManager")
    public Binding authorizeCash(String afterSaleNo, PointsRefundPort.CashDecision decision) {
        return DataScopeContext.executeWithoutScope(() -> {
            var row=locked(afterSaleNo);
            var current=decode(row);
            var prep=current.preparation();
            require(prep!=null && decision!=null && prep.netCashMinor()>0
                    && prep.preparationNo().equals(decision.preparationNo())
                    && prep.inputHash().equals(decision.inputHash())
                    && prep.revision()==decision.revision()
                    && prep.netCashMinor()==decision.netCashMinor()
                    && prep.cashOffsetMinor()==decision.cashOffsetMinor()
                    && Objects.equals(prep.collectionTicket(),decision.collectionTicket())
                    && current.command().afterSaleNo().equals(decision.afterSaleNo())
                    && current.command().subOrderNo().equals(decision.subOrderNo())
                    && current.command().userNo().equals(decision.userNo())
                    && current.command().currency().equals(decision.currency())
                    && current.command().payment().channel().equals(decision.channel())
                    && current.command().payment().equals(decision.payment()),
                    "POINTS_REFUND_CASH_DECISION_MISMATCH");
            if(Set.of(Phase.CASH_AUTHORIZED,Phase.CASH_CONFIRMED,
                    Phase.POINTS_CONFIRMED,Phase.COMMITTED).contains(current.phase())) return current;
            require(current.phase()==Phase.PREPARED,"POINTS_REFUND_PHASE_CONFLICT");
            require(mapper.change(afterSaleNo,current.version(),current.phase().name(),
                    Phase.CASH_AUTHORIZED.name(),json.writeValueAsString(prep),null)==1,
                    "POINTS_REFUND_PHASE_CONFLICT");
            return inspect(afterSaleNo);
        });
    }

    @Override
    @Transactional(value = "transactionManager", propagation = Propagation.MANDATORY)
    public Binding recordCashSuccess(String afterSaleNo, SettlePort.RefundEvidence proof) {
        return DataScopeContext.executeWithoutScope(() -> {
            var row=locked(afterSaleNo);
            var current=decode(row);
            var prep=current.preparation();
            var command=current.command();
            require(prep!=null && prep.netCashMinor()>0 && proof!=null
                    && "REFUND".equals(proof.direction()) && "SUCCESS".equals(proof.status())
                    && proof.verifiedAt()!=null && proof.verifiedAt()>0
                    && proof.providerNo()!=null && !proof.providerNo().isBlank()
                    && proof.verifiedMinor()!=null && proof.verifiedMinor()==prep.netCashMinor()
                    && proof.amountMinor()==prep.netCashMinor()
                    && afterSaleNo.equals(proof.afterSaleNo())
                    && command.subOrderNo().equals(proof.subOrderNo())
                    && command.userNo().equals(proof.userNo())
                    && command.currency().equals(proof.currency()),
                    "POINTS_REFUND_CASH_EVIDENCE_MISMATCH");
            if(current.phase()==Phase.CASH_CONFIRMED || current.phase()==Phase.POINTS_CONFIRMED
                    || current.phase()==Phase.COMMITTED) {
                require(proof.paymentNo().equals(current.cashPaymentNo()),
                        "POINTS_REFUND_CASH_EVIDENCE_MISMATCH");
                return current;
            }
            require(current.phase()==Phase.CASH_AUTHORIZED,
                    "POINTS_REFUND_PHASE_CONFLICT");
            var execution=executions.inspect(afterSaleNo);
            require(execution!=null && "CASH_CONFIRMED".equals(execution.preparation().state())
                    && proof.paymentNo().equals(execution.cashPaymentNo()),
                    "POINTS_REFUND_CASH_EVIDENCE_MISMATCH");
            var original=bindings.find(command.subOrderNo());
            require(original!=null && original.phase()==OrderPointsBindingService.Phase.BOUND,
                    "POINTS_REFUND_BINDING_MISSING");
            var at=java.time.Instant.ofEpochMilli(proof.verifiedAt());
            String eventNo="ORDER:REFUND_CONFIRM:"+afterSaleNo;
            var confirmation=new PointsRefundPort.RefundConfirmation(prep.preparationNo(),
                    prep.inputHash(),proof.paymentNo(),prep.collectionTicket(),null);
            var fact=new PointsFactPort.RefundConfirmationFact(eventNo,command.typeCode(),
                    command.subOrderNo(),command.userNo(),command.sourceVersion(),at,confirmation);
            facts.append(new PointsOrderEvent(original.admission().orderNo(),command.subOrderNo(),
                    original.admission().merchantNo(),original.admission().storeNo(),command.userNo(),
                    command.typeCode(),command.currency(),command.sourceVersion(),at,
                    new SourceFactsPort.Fact(0,null,null,null,null,fact)));
            require(mapper.change(afterSaleNo,current.version(),current.phase().name(),
                    Phase.CASH_CONFIRMED.name(),json.writeValueAsString(prep),proof.paymentNo())==1,
                    "POINTS_REFUND_PHASE_CONFLICT");
            return inspect(afterSaleNo);
        });
    }

    @Override
    @Transactional(value = "transactionManager", propagation = Propagation.MANDATORY)
    public Binding recordCashFailure(String afterSaleNo, SettlePort.RefundEvidence proof) {
        return DataScopeContext.executeWithoutScope(() -> {
            var current=decode(locked(afterSaleNo));
            var prep=current.preparation();
            var command=current.command();
            require(prep!=null && prep.netCashMinor()>0 && prep.collectionTicket()!=null
                    && proof!=null && Set.of("FAILED","CLOSED").contains(proof.status())
                    && "REFUND".equals(proof.direction()) && proof.paymentNo()!=null
                    && afterSaleNo.equals(proof.afterSaleNo())
                    && command.subOrderNo().equals(proof.subOrderNo())
                    && command.userNo().equals(proof.userNo())
                    && command.currency().equals(proof.currency())
                    && proof.amountMinor()==prep.netCashMinor()
                    && proof.verifiedAt()==null && proof.verifiedMinor()==null,
                    "POINTS_REFUND_CASH_FAILURE_MISMATCH");
            if(current.phase()==Phase.COMPENSATING) {
                require(proof.paymentNo().equals(current.cashPaymentNo()),
                        "POINTS_REFUND_CASH_FAILURE_MISMATCH");
                return current;
            }
            require(current.phase()==Phase.CASH_AUTHORIZED,
                    "POINTS_REFUND_PHASE_CONFLICT");
            var execution=executions.inspect(afterSaleNo);
            require(execution!=null && proof.paymentNo().equals(execution.cashPaymentNo())
                    && "CASH_PENDING".equals(execution.preparation().state()),
                    "POINTS_REFUND_CASH_FAILURE_MISMATCH");
            require(mapper.change(afterSaleNo,current.version(),current.phase().name(),
                    Phase.COMPENSATING.name(),json.writeValueAsString(prep),proof.paymentNo())==1,
                    "POINTS_REFUND_PHASE_CONFLICT");
            return inspect(afterSaleNo);
        });
    }

    @Override
    @Transactional(value = "transactionManager")
    public Binding recordNoCollectionCashFailure(String afterSaleNo,
            SettlePort.RefundEvidence proof, PointsRefundPort.RefundRecoveryStatus status) {
        return DataScopeContext.executeWithoutScope(() -> {
            var current = decode(locked(afterSaleNo));
            var prep = current.preparation();
            var command = current.command();
            require(prep != null && prep.netCashMinor() > 0 && prep.collectionTicket() == null
                    && proof != null && Set.of("FAILED", "CLOSED").contains(proof.status())
                    && "REFUND".equals(proof.direction()) && proof.paymentNo() != null
                    && afterSaleNo.equals(proof.afterSaleNo())
                    && command.subOrderNo().equals(proof.subOrderNo())
                    && command.userNo().equals(proof.userNo())
                    && command.currency().equals(proof.currency())
                    && proof.amountMinor() == prep.netCashMinor()
                    && proof.verifiedAt() == null && proof.verifiedMinor() == null
                    && status != null && "ABORTED".equals(status.state())
                    && !status.collectionRequired() && !status.compensationSucceeded()
                    && prep.preparationNo().equals(status.preparationNo())
                    && afterSaleNo.equals(status.afterSaleNo())
                    && command.subOrderNo().equals(status.subOrderNo())
                    && status.netCashMinor() == prep.netCashMinor()
                    && proof.paymentNo().equals(status.cashRefundPaymentNo()),
                    "POINTS_REFUND_CASH_FAILURE_MISMATCH");
            if (current.phase() == Phase.CASH_FAILED_PENDING_OPERATOR) {
                require(proof.paymentNo().equals(current.cashPaymentNo()),
                        "POINTS_REFUND_CASH_FAILURE_MISMATCH");
                return current;
            }
            require(current.phase() == Phase.CASH_AUTHORIZED, "POINTS_REFUND_PHASE_CONFLICT");
            var execution = executions.inspect(afterSaleNo);
            require(execution != null && "CASH_PENDING".equals(execution.preparation().state())
                    && proof.paymentNo().equals(execution.cashPaymentNo()),
                    "POINTS_REFUND_CASH_FAILURE_MISMATCH");
            require(mapper.change(afterSaleNo, current.version(), current.phase().name(),
                    Phase.CASH_FAILED_PENDING_OPERATOR.name(), json.writeValueAsString(prep),
                    proof.paymentNo()) == 1, "POINTS_REFUND_PHASE_CONFLICT");
            return inspect(afterSaleNo);
        });
    }

    @Override
    @Transactional(value = "transactionManager")
    public Binding recordCompensated(String afterSaleNo,
                                     PointsRefundPort.RefundRecoveryStatus status) {
        return DataScopeContext.executeWithoutScope(() -> {
            var current = decode(locked(afterSaleNo));
            var prep = current.preparation();
            require(prep != null && status != null
                    && prep.preparationNo().equals(status.preparationNo())
                    && afterSaleNo.equals(status.afterSaleNo())
                    && current.subOrderNo().equals(status.subOrderNo())
                    && prep.netCashMinor() == status.netCashMinor()
                    && Objects.equals(current.cashPaymentNo(), status.cashRefundPaymentNo())
                    && "ABORTED".equals(status.state()) && status.compensationSucceeded()
                    && status.collectionRequired(),
                    "POINTS_REFUND_COMPENSATION_PROOF_INVALID");
            if (current.phase() == Phase.COMPENSATED_PENDING_OPERATOR) return current;
            require(current.phase() == Phase.COMPENSATING,
                    "POINTS_REFUND_PHASE_CONFLICT");
            var execution = executions.inspect(afterSaleNo);
            require(execution != null && "CASH_PENDING".equals(execution.preparation().state())
                    && current.cashPaymentNo().equals(execution.cashPaymentNo()),
                    "POINTS_REFUND_COMPENSATION_SOURCE_CONFLICT");
            require(mapper.change(afterSaleNo, current.version(), current.phase().name(),
                    Phase.COMPENSATED_PENDING_OPERATOR.name(),
                    json.writeValueAsString(prep), current.cashPaymentNo()) == 1,
                    "POINTS_REFUND_PHASE_CONFLICT");
            return inspect(afterSaleNo);
        });
    }

    @Override
    @Transactional(value = "transactionManager", propagation = Propagation.MANDATORY)
    public Binding closeCompensated(String afterSaleNo,
                                   PointsRefundPort.RefundRecoveryStatus status) {
        return DataScopeContext.executeWithoutScope(() -> {
            var current = decode(locked(afterSaleNo));
            var prep = current.preparation();
            require(prep != null && status != null
                    && prep.preparationNo().equals(status.preparationNo())
                    && afterSaleNo.equals(status.afterSaleNo())
                    && current.subOrderNo().equals(status.subOrderNo())
                    && prep.netCashMinor() == status.netCashMinor()
                    && Objects.equals(current.cashPaymentNo(), status.cashRefundPaymentNo())
                    && "ABORTED".equals(status.state())
                    && status.collectionRequired() == (prep.collectionTicket() != null)
                    && status.compensationSucceeded() == status.collectionRequired(),
                    "POINTS_REFUND_COMPENSATION_PROOF_INVALID");
            if (current.phase() == Phase.ABORTED) return current;
            require(current.phase() == Phase.COMPENSATED_PENDING_OPERATOR
                    || current.phase() == Phase.CASH_FAILED_PENDING_OPERATOR,
                    "POINTS_REFUND_PHASE_CONFLICT");
            executions.abortCompensated(afterSaleNo, status);
            require(mapper.change(afterSaleNo, current.version(), current.phase().name(),
                    Phase.ABORTED.name(), json.writeValueAsString(prep),
                    current.cashPaymentNo()) == 1, "POINTS_REFUND_PHASE_CONFLICT");
            return inspect(afterSaleNo);
        });
    }

    @Override
    @Transactional(value = "transactionManager", propagation = Propagation.MANDATORY)
    public Binding recordZeroCash(String afterSaleNo) {
        return DataScopeContext.executeWithoutScope(() -> {
            var current=decode(locked(afterSaleNo));
            var prep=current.preparation();
            var command=current.command();
            require(prep!=null && prep.netCashMinor()==0,
                    "POINTS_ZERO_CASH_NOT_ZERO");
            if(Set.of(Phase.ZERO_AUTHORIZED,Phase.POINTS_CONFIRMED,Phase.COMMITTED)
                    .contains(current.phase())) return current;
            require(current.phase()==Phase.PREPARED,"POINTS_REFUND_PHASE_CONFLICT");
            var original=bindings.find(command.subOrderNo());
            require(original!=null && original.phase()==OrderPointsBindingService.Phase.BOUND,
                    "POINTS_REFUND_BINDING_MISSING");
            var reason=command.businessCashMinor()==0 && prep.collectionTicket()==null
                    ? PointsRefundPort.ZeroCashReason.NO_CASH_DUE
                    : PointsRefundPort.ZeroCashReason.COLLECTED_OFFSET;
            require(reason==PointsRefundPort.ZeroCashReason.NO_CASH_DUE
                    || (prep.collectionTicket()!=null
                    && prep.cashOffsetMinor()==command.businessCashMinor()),
                    "POINTS_ZERO_CASH_REASON_MISMATCH");
            String eventNo="ORDER:ZERO_CASH:"+afterSaleNo;
            var at=java.time.Instant.now();
            var fact=new PointsRefundPort.ZeroCashCompletionFact(eventNo,prep.preparationNo(),
                    prep.inputHash(),prep.revision(),command.typeCode(),afterSaleNo,
                    command.subOrderNo(),command.userNo(),original.admission().storeNo(),
                    command.currency(),command.sourceVersion(),command.businessCashMinor(),
                    prep.cashOffsetMinor(),0,reason,at);
            facts.append(new PointsOrderEvent(original.admission().orderNo(),command.subOrderNo(),
                    original.admission().merchantNo(),original.admission().storeNo(),command.userNo(),
                    command.typeCode(),command.currency(),command.sourceVersion(),at,
                    new SourceFactsPort.Fact(0,null,null,null,fact,null)));
            require(mapper.change(afterSaleNo,current.version(),current.phase().name(),
                    Phase.ZERO_AUTHORIZED.name(),json.writeValueAsString(prep),null)==1,
                    "POINTS_REFUND_PHASE_CONFLICT");
            return inspect(afterSaleNo);
        });
    }

    @Override
    @Transactional(value = "transactionManager")
    public Binding acceptPointsConfirmation(String afterSaleNo, PointsRefundPort.RefundOutcome outcome) {
        return DataScopeContext.executeWithoutScope(() -> {
            var current=decode(locked(afterSaleNo));
            require(outcome!=null && current.preparation()!=null
                    && current.preparation().preparationNo().equals(outcome.preparationNo())
                    && "CONFIRMED".equals(outcome.state()),"POINTS_REFUND_CONFIRMATION_MISMATCH");
            if(current.phase()==Phase.POINTS_CONFIRMED || current.phase()==Phase.COMMITTED) {
                require(current.outcome()!=null
                        && current.outcome().returnedPoints()==outcome.returnedPoints()
                        && current.outcome().reclaimedPoints()==outcome.reclaimedPoints()
                        && current.outcome().collectedMinor()==outcome.collectedMinor()
                        && current.outcome().waivedPoints()==outcome.waivedPoints()
                        && current.outcome().receivableMinor()==outcome.receivableMinor()
                        && Objects.equals(current.outcome().voucherNo(),outcome.voucherNo()),
                        "POINTS_REFUND_CONFIRMATION_MISMATCH");
                return current;
            }
            require(current.phase()==Phase.CASH_CONFIRMED
                    || current.phase()==Phase.ZERO_AUTHORIZED,"POINTS_REFUND_PHASE_CONFLICT");
            require(mapper.confirm(afterSaleNo,current.version(),current.phase().name(),
                    json.writeValueAsString(outcome))==1,"POINTS_REFUND_PHASE_CONFLICT");
            return inspect(afterSaleNo);
        });
    }

    @Override
    @Transactional(value = "transactionManager", propagation = Propagation.MANDATORY)
    public Binding commit(String afterSaleNo) {
        return DataScopeContext.executeWithoutScope(() -> {
            var current=decode(locked(afterSaleNo));
            if(current.phase()==Phase.COMMITTED) return current;
            require(current.phase()==Phase.POINTS_CONFIRMED,"POINTS_REFUND_PHASE_CONFLICT");
            var execution=executions.inspect(afterSaleNo);
            require(execution!=null && "COMMITTED".equals(execution.preparation().state()),
                    "POINTS_REFUND_SOURCE_NOT_COMMITTED");
            require(mapper.change(afterSaleNo,current.version(),current.phase().name(),
                    Phase.COMMITTED.name(),json.writeValueAsString(current.preparation()),
                    current.cashPaymentNo())==1,"POINTS_REFUND_PHASE_CONFLICT");
            return inspect(afterSaleNo);
        });
    }

    @Override
    @Transactional(value = "transactionManager", readOnly = true)
    public Binding inspect(String afterSaleNo) {
        return DataScopeContext.executeWithoutScope(() -> decode(mapper.find(afterSaleNo)));
    }

    @Override
    @Transactional(value = "transactionManager", readOnly = true)
    public ConfirmedTotals confirmedTotals(String subOrderNo) {
        return DataScopeContext.executeWithoutScope(() -> {
            long returned=0, reclaimed=0;
            for (var row : mapper.forSubOrder(subOrderNo)) {
                if (!Set.of("POINTS_CONFIRMED","COMMITTED").contains(row.get("phase"))) continue;
                Object body=row.get("outcome_json");
                require(body!=null,"POINTS_REFUND_CONFIRMATION_MISSING");
                var outcome=json.readValue((String)body,PointsRefundPort.RefundOutcome.class);
                returned=Math.addExact(returned,outcome.returnedPoints());
                reclaimed=Math.addExact(reclaimed,outcome.reclaimedPoints());
            }
            return new ConfirmedTotals(returned,reclaimed);
        });
    }

    @Override
    @Transactional(value = "transactionManager", readOnly = true)
    public ManualPage manualForStore(String storeNo, String after, int limit) {
        require(storeNo != null && !storeNo.isBlank() && limit >= 1 && limit <= 100,
                "POINTS_REFUND_MANUAL_QUERY_INVALID");
        return DataScopeContext.executeWithoutScope(() -> {
            var found = mapper.manualForStore(storeNo, after == null ? "" : after, limit + 1);
            boolean more = found.size() > limit;
            var rows = found.stream().limit(limit).map(row -> new ManualRefund(
                    (String) row.get("after_sale_no"), (String) row.get("sub_order_no"),
                    ((java.util.Date) row.get("created_at")).getTime())).toList();
            return new ManualPage(rows, rows.isEmpty() ? after : rows.get(rows.size() - 1).afterSaleNo(), more);
        });
    }

    @Override
    @Transactional(value = "transactionManager", readOnly = true)
    public List<String> pending(int limit) {
        require(limit >= 1 && limit <= 200, "POINTS_REFUND_LIMIT_INVALID");
        return DataScopeContext.executeWithoutScope(() -> mapper.pending(limit));
    }

    private Binding decode(Map<String, Object> row) {
        if (row == null) return null;
        return new Binding(
                (String) row.get("after_sale_no"),
                (String) row.get("sub_order_no"),
                Phase.valueOf((String) row.get("phase")),
                ((Number) row.get("version")).longValue(),
                json.readValue(
                        (String) row.get("command_json"), PointsRefundPort.RefundCommand.class),
                row.get("preparation_json") == null
                        ? null
                        : json.readValue(
                                (String) row.get("preparation_json"),
                                PointsRefundPort.RefundPreparation.class),
                (String) row.get("cash_payment_no"),
                row.get("outcome_json") == null ? null : json.readValue(
                        (String) row.get("outcome_json"), PointsRefundPort.RefundOutcome.class));
    }

    private Map<String,Object> locked(String no) {
        var first=mapper.find(no);
        require(first!=null,"POINTS_REFUND_BINDING_UNKNOWN");
        subs.selectOne(Wrappers.<OrdSubOrder>lambdaQuery()
                .eq(OrdSubOrder::getSubOrderNo,(String)first.get("sub_order_no")).last("FOR UPDATE"));
        return mapper.lock(no);
    }

    private static long value(Long x) {
        return x == null ? 0 : x;
    }

    private static long value(Integer x) {
        return x == null ? 0 : x;
    }

    private static void require(boolean yes, String code) {
        if (!yes) throw new IllegalArgumentException(code);
    }
}
