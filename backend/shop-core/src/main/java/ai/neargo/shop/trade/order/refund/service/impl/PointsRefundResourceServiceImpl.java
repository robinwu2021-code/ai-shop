package ai.neargo.shop.trade.order.refund.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.product.StockPort;
import ai.neargo.shop.trade.entity.OrdAfterSale;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.order.entity.OrdCreateIntent;
import ai.neargo.shop.trade.order.mapper.CreateIntentMapper;
import ai.neargo.shop.trade.order.mapper.RefundExecutionMapper;
import ai.neargo.shop.trade.order.mapper.RefundObligationMapper;
import ai.neargo.shop.trade.order.refund.model.RefundPlan;
import ai.neargo.shop.trade.order.refund.service.PointsRefundResourceService;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class PointsRefundResourceServiceImpl implements PointsRefundResourceService {
    private final RefundObligationMapper tasks;
    private final RefundExecutionMapper sources;
    private final CreateIntentMapper intents;
    private final StockPort stock;
    private final String authority;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    public PointsRefundResourceServiceImpl(RefundObligationMapper tasks,RefundExecutionMapper sources,
            CreateIntentMapper intents,StockPort stock,ObjectMapper json,
            @Value("${shop.inventory.stock-authority:PLATFORM}") String authority,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        this.tasks=tasks; this.sources=sources; this.intents=intents; this.stock=stock; this.json=json;
        this.authority=authority; this.tx=new TransactionTemplate(manager);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public void prepare(OrdAfterSale as,RefundPlan plan) {
        DataScopeContext.executeWithoutScope(
                () -> {
                    var child = sources.lockSub(plan.subOrderNo());
                    if (child == null
                            || !"LEGACY_FUNDED".equals(child.getAdmissionKind())
                            || sources.fundedSource(
                                            child.getOrderNo(),
                                            child.getSubOrderNo(),
                                            as.getAfterSaleNo(),
                                            child.getTenantNo(),
                                            child.getEntityNo())
                                    != 1) {
                        throw sourceConflict();
                    }
                    String no = "PRF-" + as.getAfterSaleNo();
                    if (tasks.lockRows(no).stream()
                            .anyMatch(row -> "STOCK".equals(row.get("kind")))) return null;
                    var execution = sources.lockExecution(as.getAfterSaleNo());
                    require(
                            execution != null && "COMMITTED".equals(execution.get("state")),
                            "POINTS_RESOURCE_SOURCE_NOT_READY");
                    var items = sources.items(plan.subOrderNo());
                    boolean lifecycle2 =
                            Integer.valueOf(2).equals(child.getInventoryLifecycleVersion());
                    boolean unshipped =
                            OrdSubOrder.WAIT_FULFILL.equals(child.getStatus())
                                    && items.stream()
                                            .noneMatch(
                                                    it ->
                                                            it.getShippedQty() != null
                                                                    && it.getShippedQty() > 0);
                    boolean returned =
                            OrdAfterSale.RETURN_REFUND.equals(as.getType())
                                    && as.getReturnReceivedAt() != null
                                    && Boolean.TRUE.equals(as.getRestockApproved());
                    boolean needed =
                            !Integer.valueOf(1).equals(as.getStockRestored())
                                    && (unshipped || returned && !lifecycle2);
                    var original =
                            intents.selectOne(
                                    Wrappers.<OrdCreateIntent>lambdaQuery()
                                            .eq(OrdCreateIntent::getPayNo, child.getOrderNo()));
                    var lines =
                            plan.lines().stream()
                                    .filter(line -> line.qty() > 0)
                                    .map(
                                            line -> {
                                                var item =
                                                        items.stream()
                                                                .filter(
                                                                        it ->
                                                                                it.getId()
                                                                                        == line
                                                                                                .itemId())
                                                                .findFirst()
                                                                .orElseThrow();
                                                return new StockPort.SkuQty(
                                                        item.getSkuNo(),
                                                        line.qty(),
                                                        item.getSupplyStoreNo() == null
                                                                ? child.getStoreNo()
                                                                : item.getSupplyStoreNo());
                                            })
                                    .toList();
                    boolean full =
                            plan.lines().stream().mapToLong(line -> line.qty()).sum()
                                    == items.stream().mapToLong(it -> it.getQty()).sum();
                    var command =
                            new Command(
                                    as.getAfterSaleNo(),
                                    child.getOrderNo(),
                                    child.getSubOrderNo(),
                                    original == null ? null : original.getStockAuthority(),
                                    lifecycle2,
                                    unshipped,
                                    full,
                                    lines);
                    String payload = json.writeValueAsString(command);
                    tasks.createStock(
                            no,
                            as.getAfterSaleNo(),
                            RefundCouponScopeServiceImpl.hash(payload),
                            payload,
                            needed && !lines.isEmpty() ? "PENDING" : "NOT_REQUIRED",
                            needed ? null : "STOCK_POLICY:" + as.getAfterSaleNo());
                    return null;
                });
    }
    @Override @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public void recover(String as) {
        var row =
                tx.execute(
                        status ->
                                DataScopeContext.executeWithoutScope(
                                        () -> {
                                            var current = guard(as);
                                            if (current == null || conflict(current)) {
                                                return current;
                                            }
                                            if (terminal(current)) {
                                                return null;
                                            }
                                            if ("PROCESSING".equals(current.get("state"))
                                                    && number(current, "next_attempt_at")
                                                            > System.currentTimeMillis())
                                                return null;
                                            if (tasks.update(
                                                            number(current, "id"),
                                                            (String) current.get("input_hash"),
                                                            "PROCESSING",
                                                            null,
                                                            null,
                                                            System.currentTimeMillis() + 120000)
                                                    != 1) return null;
                                            return current;
                                        }));
        if(row==null) return;
        if (conflict(row)) {
            throw sourceConflict();
        }
        String error=null;
        boolean done=false;
        try {
            String payload=(String)row.get("payload_json");
            require(RefundCouponScopeServiceImpl.hash(payload).equals(row.get("input_hash")),"POINTS_RESOURCE_INPUT_CONFLICT");
            var command=json.readValue(payload,Command.class);
            require(Objects.equals(authority,command.authority()),"STOCK_SOURCE_AUTHORITY_MISMATCH");
            if(command.lifecycle2() && command.unshipped()) {
                require(command.full(),"STOCK_PARTIAL_RELEASE_REQUIRES_REVIEW");
                stock.releaseAllocated(command.subOrderNo(),command.orderNo());
                done=true;
            } else {
                done=stock.restoreOriginal("PRF-"+as,command.orderNo(),command.lines())==StockPort.RestoreResult.COMPLETED;
            }
            if(!done) error="STOCK_RESTORE_PENDING";
        } catch(RuntimeException failure) {
            error=failure instanceof IllegalStateException?failure.getMessage():"STOCK_RESTORE_UNCONFIRMED";
            if(error==null) error="STOCK_RESTORE_UNCONFIRMED";
            error=error.substring(0,Math.min(error.length(),128));
        }
        boolean complete=done;
        String lastError=error;
        Boolean retained =
                tx.execute(
                        status ->
                                DataScopeContext.executeWithoutScope(
                                        () -> {
                                            var current = guard(as);
                                            if (current == null || conflict(current)) {
                                                return false;
                                            }
                                            if (!Objects.equals(
                                                            current.get("input_hash"),
                                                            row.get("input_hash"))
                                                    || !Objects.equals(
                                                            current.get("payload_json"),
                                                            row.get("payload_json"))) {
                                                reject(current);
                                                return false;
                                            }
                                            tasks.update(
                                                    number(row, "id"),
                                                    (String) row.get("input_hash"),
                                                    complete ? "SUCCEEDED" : "UNKNOWN",
                                                    complete ? "STOCK_RESTORE:PRF-" + as : null,
                                                    lastError,
                                                    System.currentTimeMillis() + 120000);
                                            if (complete) tasks.stockRestored(as);
                                            return true;
                                        }));
        if (!Boolean.TRUE.equals(retained)) {
            throw sourceConflict();
        }
    }

    /** Caller holds a short Core transaction; child precedes task/execution source inspection. */
    private Map<String, Object> guard(String afterSaleNo) {
        var hint = sources.execution(afterSaleNo);
        var child = hint == null ? null : sources.lockSub((String) hint.get("sub_order_no"));
        var row =
                tasks.lockRows("PRF-" + afterSaleNo).stream()
                        .filter(task -> "STOCK".equals(task.get("kind")))
                        .findFirst()
                        .orElse(null);
        if (row == null) {
            return null;
        }
        var source = sources.execution(afterSaleNo);
        boolean valid =
                child != null
                        && source != null
                        && "COMMITTED".equals(source.get("state"))
                        && Objects.equals(source.get("sub_order_no"), child.getSubOrderNo())
                        && Objects.equals(source.get("tenant_no"), child.getTenantNo())
                        && "LEGACY_FUNDED".equals(child.getAdmissionKind())
                        && sources.fundedSource(
                                        child.getOrderNo(),
                                        child.getSubOrderNo(),
                                        afterSaleNo,
                                        child.getTenantNo(),
                                        child.getEntityNo())
                                == 1
                        && Objects.equals(afterSaleNo, row.get("source_no"))
                        && !conflict(row);
        if (valid) {
            String payload = (String) row.get("payload_json");
            Command command;
            try {
                command = json.readValue(payload, Command.class);
                valid =
                        Objects.equals(afterSaleNo, command.afterSaleNo())
                                && Objects.equals(command.subOrderNo(), child.getSubOrderNo())
                                && Objects.equals(command.orderNo(), child.getOrderNo())
                                && RefundCouponScopeServiceImpl.hash(payload)
                                        .equals(row.get("input_hash"))
                                && command.lines() != null;
            } catch (RuntimeException malformed) {
                reject(row);
                return row;
            }
            if (valid) {
                var items = sources.items(child.getSubOrderNo());
                boolean lifecycle2 =
                        Integer.valueOf(2).equals(child.getInventoryLifecycleVersion());
                boolean noShippedQuantity =
                        items.stream()
                                .noneMatch(
                                        item ->
                                                item.getShippedQty() != null
                                                        && item.getShippedQty() > 0);
                var original =
                        intents.selectOne(
                                Wrappers.<OrdCreateIntent>lambdaQuery()
                                        .eq(OrdCreateIntent::getPayNo, child.getOrderNo()));
                valid =
                        original != null
                                && Objects.equals(original.getStockAuthority(), command.authority())
                                && command.lifecycle2() == lifecycle2
                                && (!command.unshipped() || noShippedQuantity)
                                && command.lines().stream()
                                        .allMatch(line -> line != null && line.qty() > 0)
                                && command.full()
                                        == (command.lines().stream()
                                                        .mapToLong(StockPort.SkuQty::qty)
                                                        .sum()
                                                == items.stream()
                                                        .mapToLong(item -> item.getQty())
                                                        .sum());
            }
        }
        if (!valid) {
            reject(row);
        }
        return row;
    }

    private void reject(Map<String, Object> row) {
        tasks.update(
                number(row, "id"),
                (String) row.get("input_hash"),
                "UNKNOWN",
                null,
                "REFUND_EXECUTION_CONFLICT",
                System.currentTimeMillis() + 120000);
        row.put("error_code", "REFUND_EXECUTION_CONFLICT");
    }

    private static boolean conflict(Map<String, Object> row) {
        return "REFUND_EXECUTION_CONFLICT".equals(row.get("error_code"));
    }

    private static BizException sourceConflict() {
        return BizException.of(ErrorCode.CHECKOUT_PAYMENT_CONFLICT);
    }

    @Override public List<String> pendingNos(int limit) {
        return DataScopeContext.executeWithoutScope(() -> tasks.pendingStock(System.currentTimeMillis(),Math.max(1,Math.min(limit,1000))));
    }
    public record Command(String afterSaleNo,String orderNo,String subOrderNo,String authority,
                          boolean lifecycle2,boolean unshipped,boolean full,List<StockPort.SkuQty> lines) { }
    private static boolean terminal(Map<String,Object> row) {return "SUCCEEDED".equals(row.get("state"))||"NOT_REQUIRED".equals(row.get("state"));}
    private static long number(Map<String,Object> row,String key) {return ((Number)row.get(key)).longValue();}
    private static void require(boolean valid,String error) {if(!valid) throw new IllegalStateException(error);}
}
