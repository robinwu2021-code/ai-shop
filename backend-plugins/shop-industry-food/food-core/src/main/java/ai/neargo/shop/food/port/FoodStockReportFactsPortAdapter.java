package ai.neargo.shop.food.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.food.dto.FoodStockContracts.BalanceVO;
import ai.neargo.shop.food.entity.*;
import ai.neargo.shop.food.mapper.FoodMappers.*;
import ai.neargo.shop.spi.food.FoodStockReportFactsPort;
import ai.neargo.shop.spi.report.ReportContracts.*;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Physical FOOD_LOCAL quantities only; NO_STOCK audits never become usage or inventory costs. */
public final class FoodStockReportFactsPortAdapter implements FoodStockReportFactsPort {
    private final StockBalanceMapper balances;private final StockItemMapper items;private final StockMovementMapper movements;
    private final StockCommandMapper commands;private final ObjectMapper json;private final ZoneId sourceZone;private final TransactionTemplate read;
    public FoodStockReportFactsPortAdapter(StockBalanceMapper balances,StockItemMapper items,StockMovementMapper movements,
            StockCommandMapper commands,ObjectMapper json,PlatformTransactionManager manager,String sourceZone) {
        this.balances=balances;this.items=items;this.movements=movements;this.commands=commands;this.json=json;this.sourceZone=ZoneId.of(sourceZone);
        read=new TransactionTemplate(manager);read.setReadOnly(true);read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    @Override public SourceSnapshot readSnapshot(SourceQuery query) {
        if(!"MAIN".equals(query.partition().scope().tenantNo()))throw new IllegalArgumentException("REPORT_SCOPE_UNSUPPORTED");
        return DataScopeContext.executeWithoutScope(()->Objects.requireNonNull(read.execute(tx->collect(query))));
    }
    private SourceSnapshot collect(SourceQuery query) {
        var scope=query.partition().scope();int limit=query.rowLimit();Instant observed=Instant.now();
        var start=LocalDateTime.ofInstant(query.day().startsAt(),sourceZone);
        var end=LocalDateTime.ofInstant(query.day().endsAt().isBefore(observed)?query.day().endsAt():observed,sourceZone);
        var rows=movements.selectList(Wrappers.<FnbStockMovement>lambdaQuery().eq(FnbStockMovement::getTenantNo,"MAIN")
                .eq(FnbStockMovement::getEntityNo,scope.entityNo()).eq(FnbStockMovement::getStoreNo,scope.storeNo())
                .ge(FnbStockMovement::getCreatedAt,start).lt(FnbStockMovement::getCreatedAt,end)
                .orderByAsc(FnbStockMovement::getId).last("LIMIT "+(limit+1)));
        var configured=balances.selectList(Wrappers.<FnbStockBalance>lambdaQuery().eq(FnbStockBalance::getTenantNo,"MAIN")
                .eq(FnbStockBalance::getEntityNo,scope.entityNo()).eq(FnbStockBalance::getStoreNo,scope.storeNo())
                .lt(FnbStockBalance::getCreatedAt,end).orderByAsc(FnbStockBalance::getId).last("LIMIT "+(limit+1)));
        var policies=commands.selectList(Wrappers.<FnbStockCommand>lambdaQuery().eq(FnbStockCommand::getTenantNo,"MAIN")
                .eq(FnbStockCommand::getEntityNo,scope.entityNo()).eq(FnbStockCommand::getStoreNo,scope.storeNo())
                .eq(FnbStockCommand::getStatus,"COMPLETED").in(FnbStockCommand::getAction,List.of("INITIALIZE","POLICY"))
                .lt(FnbStockCommand::getCreatedAt,end).orderByDesc(FnbStockCommand::getId).last("LIMIT "+(limit+1)));
        if(rows.size()+configured.size()+policies.size()>limit)throw new IllegalStateException("REPORT_SOURCE_ROW_LIMIT");
        var evidence=new ArrayList<Object>();var facts=new ArrayList<MetricFact>();var historyPolicy=new HashMap<String,BalanceVO>();
        for(var command:policies){var balance=json.readValue(command.getResultJson(),BalanceVO.class);
            historyPolicy.putIfAbsent(balance.itemNo(),balance);evidence.add(List.of(command.getCommandNo(),command.getRequestHash()));}
        boolean incomplete=configured.isEmpty();
        for(var movement:rows) {
            evidence.add(List.of(movement.getMovementNo(),movement.getCommandNo(),movement.getAction(),movement.getQuantity(),
                    movement.getOnHandBefore(),movement.getOnHandAfter(),movement.getReservedBefore(),movement.getReservedAfter()));
            String metric=switch(movement.getAction()) {
                case "ISSUE","SUPPLEMENT"->"MATERIAL_ACTUAL_ISSUE_QTY";
                case "RETURN"->"MATERIAL_ACTUAL_REVERSAL_QTY";
                case "LOSS"->"MATERIAL_WASTE_QTY";
                case "INITIALIZE"->"STOCK_RECEIPT_QTY";
                case "ADJUST","ADJUST_IN","ADJUST_OUT"->"STOCK_ADJUSTMENT_QTY";
                default->null;
            };
            if(metric!=null)facts.add(quantity(metric,"INVENTORY_MOVEMENT_DAY",movement.getItemNo(),movement.getBaseUnit(),
                    Set.of("ADJUST","ADJUST_IN","ADJUST_OUT").contains(movement.getAction())?movement.getOnHandAfter().subtract(movement.getOnHandBefore()):movement.getQuantity()));
        }
        for(var balance:configured) {
            var policy=historyPolicy.get(balance.getItemNo());
            if(policy==null){incomplete=true;continue;}
            evidence.add(List.of(balance.getItemNo(),policy.trackingMode(),policy.baseUnit()));
            if("UNTRACKED".equals(policy.trackingMode()))continue;
            if(!"TRACKED".equals(policy.trackingMode())){incomplete=true;continue;}
            var material=items.selectOne(Wrappers.<FnbStockItem>lambdaQuery().eq(FnbStockItem::getTenantNo,"MAIN")
                    .eq(FnbStockItem::getEntityNo,scope.entityNo()).eq(FnbStockItem::getItemNo,balance.getItemNo()));
            if(material==null||!material.getBaseUnit().equals(policy.baseUnit())){incomplete=true;continue;}
            var closing=boundary(scope,balance.getItemNo(),end);
            // A zero initialization with no movement is proved by its permanent management receipt.
            BigDecimal onHand=closing==null?policy.onHand():closing.getOnHandAfter();
            BigDecimal reserved=closing==null?policy.reservedQty():closing.getReservedAfter();
            if(onHand==null||reserved==null){incomplete=true;continue;}
            var opening=boundary(scope,balance.getItemNo(),start);
            if(balance.getCreatedAt().isBefore(start)&&opening==null&&policy.onHand()!=null&&policy.onHand().signum()!=0)incomplete=true;
            else facts.add(quantity("STOCK_OPENING_QTY","INVENTORY_BALANCE_DAY",balance.getItemNo(),policy.baseUnit(),opening==null?BigDecimal.ZERO:opening.getOnHandAfter()));
            facts.add(quantity("STOCK_CLOSING_QTY","INVENTORY_BALANCE_DAY",balance.getItemNo(),policy.baseUnit(),onHand));
            facts.add(quantity("STOCK_RESERVED_QTY","INVENTORY_BALANCE_DAY",balance.getItemNo(),policy.baseUnit(),reserved));
            facts.add(quantity("STOCK_AVAILABLE_QTY","INVENTORY_BALANCE_DAY",balance.getItemNo(),policy.baseUnit(),onHand.subtract(reserved)));
            evidence.add(Arrays.asList(balance.getItemNo(),opening==null?null:opening.getMovementNo(),closing==null?null:closing.getMovementNo(),onHand,reserved));
        }
        String digest=PricingSnapshotHash.of(List.of("FOOD_LOCAL_STOCK_V1",query.partition(),query.day(),sourceZone.getId(),evidence));
        return new SourceSnapshot(query.partition(),new SourceKey(Owner.INVENTORY,"STOCK"),digest,digest,observed,
                rows.size()+configured.size()+policies.size(),digest,incomplete?Coverage.PARTIAL:Coverage.COMPLETE,
                incomplete?"LOCAL_STOCK_POLICY_OR_HISTORY_UNAVAILABLE":null,facts,List.of());
    }
    private FnbStockMovement boundary(Scope scope,String item,LocalDateTime before) {
        var rows=movements.selectList(Wrappers.<FnbStockMovement>lambdaQuery().eq(FnbStockMovement::getTenantNo,"MAIN")
                .eq(FnbStockMovement::getEntityNo,scope.entityNo()).eq(FnbStockMovement::getStoreNo,scope.storeNo())
                .eq(FnbStockMovement::getItemNo,item).lt(FnbStockMovement::getCreatedAt,before)
                .orderByDesc(FnbStockMovement::getId).last("LIMIT 1"));return rows.isEmpty()?null:rows.getFirst();
    }
    private static MetricFact quantity(String code,String set,String item,String unit,BigDecimal amount) {
        return new MetricFact(code,set,Attribution.OCCURRENCE,Map.of("materialNo",item,"ownerId","FOOD_LOCAL"),Map.of(),MetricValue.quantity(amount,unit));
    }
}
