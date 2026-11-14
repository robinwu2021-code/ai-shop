package ai.neargo.shop.inventory.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.inventory.domain.entity.InvItem;
import ai.neargo.inventory.domain.entity.InvLedger;
import ai.neargo.inventory.domain.entity.InvStockBalance;
import ai.neargo.inventory.domain.mapper.InventoryMappers.BalanceMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.ItemMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.LedgerMapper;
import ai.neargo.inventory.domain.service.InventoryAclService;
import ai.neargo.shop.inventory.service.InventoryReportFactsService;
import ai.neargo.shop.spi.report.ReportContracts.Attribution;
import ai.neargo.shop.spi.report.ReportContracts.Coverage;
import ai.neargo.shop.spi.report.ReportContracts.MetricFact;
import ai.neargo.shop.spi.report.ReportContracts.MetricValue;
import ai.neargo.shop.spi.report.ReportContracts.Owner;
import ai.neargo.shop.spi.report.ReportContracts.SourceKey;
import ai.neargo.shop.spi.report.ReportContracts.SourceQuery;
import ai.neargo.shop.spi.report.ReportContracts.SourceSnapshot;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Exact physical stock, not COGS. Legacy local timestamps require an explicitly verified source zone. */
@Component
@ConditionalOnInventory
@ConditionalOnProperty(name="shop.inventory.report-source-zone")
public class InventoryReportFactsPortAdapter implements InventoryReportFactsService {
    private final InventoryAclService acl;
    private final LedgerMapper ledger;
    private final ItemMapper items;
    private final BalanceMapper balances;
    private final ZoneId zone;
    private final TransactionTemplate read;
    public InventoryReportFactsPortAdapter(InventoryAclService acl,LedgerMapper ledger,ItemMapper items,BalanceMapper balances,
            @Value("${shop.inventory.report-source-zone}") String zone,
            @Qualifier("invTransactionManager") PlatformTransactionManager manager) {
        this.acl=acl; this.ledger=ledger; this.items=items; this.balances=balances; this.zone=ZoneId.of(zone);
        read=new TransactionTemplate(manager); read.setReadOnly(true);
        read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    @Override public SourceKey source() { return new SourceKey(Owner.INVENTORY,"STOCK"); }
    @Override public SourceSnapshot readSnapshot(SourceQuery q) {
        if (!"MAIN".equals(q.partition().scope().tenantNo())) throw new IllegalArgumentException("REPORT_SCOPE_UNSUPPORTED");
        return Objects.requireNonNull(read.execute(tx->collect(q)));
    }
    private SourceSnapshot collect(SourceQuery q) {
        Instant snapshotAt=Instant.now();
        var scope=q.partition().scope();
        String owner=acl.findOwnerId(scope.entityNo());
        String location=acl.stockLocationOfExistingStore(scope.entityNo(),scope.storeNo());
        if (owner==null || location==null) throw new IllegalStateException("REPORT_INVENTORY_SCOPE_NOT_READY");
        LocalDateTime start=LocalDateTime.ofInstant(q.day().startsAt(),zone),end=LocalDateTime.ofInstant(q.day().endsAt(),zone);
        // Occurrence-day movement only. balanceAfter follows posting order and cannot prove a backdated boundary.
        var rows=ledger.selectList(Wrappers.<InvLedger>lambdaQuery().eq(InvLedger::getOwnerId,owner)
                .eq(InvLedger::getLocationId,location).ge(InvLedger::getOccurredAt,start).lt(InvLedger::getOccurredAt,end)
                .orderByAsc(InvLedger::getOccurredAt).orderByAsc(InvLedger::getId).last("LIMIT "+(q.rowLimit()+1)));
        if (rows.size()>q.rowLimit()) throw new IllegalStateException("REPORT_SOURCE_ROW_LIMIT");
        List<MetricFact> facts=new ArrayList<>(); List<Object> evidence=new ArrayList<>();
        Map<String,String> units=new LinkedHashMap<>();
        for (var row:rows) {
            if (row.getQtyDeltaQuantity()==null || row.getBalanceAfterQuantity()==null) unavailable();
            String unit=units.computeIfAbsent(row.getItemId(),item-> {
                var master=items.selectOne(Wrappers.<InvItem>lambdaQuery().eq(InvItem::getOwnerId,owner).eq(InvItem::getItemId,item));
                if (master==null || master.getBaseUom()==null) unavailable();
                return master.getBaseUom();
            });
            var dims=Map.of("materialNo",row.getItemId(),"ownerId",owner,"locationNo",location);
            if (!row.getOccurredAt().isBefore(start)) {
                String code=switch(row.getDocKind()) { case "IN"->"STOCK_RECEIPT_QTY"; case "OUT"->"STOCK_OUTBOUND_QTY";
                    default->throw new IllegalStateException("REPORT_INVENTORY_DIRECTION_UNSUPPORTED"); };
                BigDecimal amount="OUT".equals(row.getDocKind())?row.getQtyDeltaQuantity().negate():row.getQtyDeltaQuantity();
                facts.add(quantity(code,"INVENTORY_MOVEMENT_DAY",dims,amount,unit));
            }
            evidence.add(List.of(row.getId(),row.getItemId(),row.getDocNo(),row.getDocKind(),row.getOccurredAt(),
                    row.getQtyDeltaQuantity(),row.getBalanceAfterQuantity(),unit));
        }
        int count=rows.size();
        Instant now=Instant.now();
        if (!now.isBefore(q.day().startsAt()) && now.isBefore(q.day().endsAt())) {
            var current=balances.selectList(Wrappers.<InvStockBalance>lambdaQuery().eq(InvStockBalance::getOwnerId,owner)
                    .eq(InvStockBalance::getLocationId,location).orderByAsc(InvStockBalance::getItemId)
                    .last("LIMIT "+(q.rowLimit()-count+1)));
            count+=current.size();
            if (count>q.rowLimit()) throw new IllegalStateException("REPORT_SOURCE_ROW_LIMIT");
            for (var balance:current) {
                var material=items.selectOne(Wrappers.<InvItem>lambdaQuery().eq(InvItem::getOwnerId,owner).eq(InvItem::getItemId,balance.getItemId()));
                if (material==null || material.getBaseUom()==null || balance.getOnHandQuantity()==null || balance.getReservedQuantity()==null) unavailable();
                var dims=Map.of("materialNo",balance.getItemId(),"ownerId",owner,"locationNo",location);
                facts.add(quantity("STOCK_ON_HAND_QTY","INVENTORY_BALANCE_DAY",dims,balance.getOnHandQuantity(),material.getBaseUom()));
                facts.add(quantity("STOCK_RESERVED_QTY","INVENTORY_BALANCE_DAY",dims,balance.getReservedQuantity(),material.getBaseUom()));
                facts.add(quantity("STOCK_AVAILABLE_QTY","INVENTORY_BALANCE_DAY",dims,
                        balance.getOnHandQuantity().subtract(balance.getReservedQuantity()),material.getBaseUom()));
                evidence.add(List.of(balance.getItemId(),balance.getOnHandQuantity(),balance.getReservedQuantity(),
                        Objects.toString(balance.getVersion(),""),material.getBaseUom()));
            }
        }
        String digest=PricingSnapshotHash.of(List.of(q.partition(),q.day(),owner,location,zone.getId(),evidence));
        return new SourceSnapshot(q.partition(),source(),digest,digest,snapshotAt,count,digest,
                count==0?Coverage.EMPTY_VERIFIED:Coverage.COMPLETE,null,facts,List.of());
    }
    private static MetricFact quantity(String code,String set,Map<String,String> dimensions,BigDecimal amount,String unit) {
        return new MetricFact(code,set,Attribution.OCCURRENCE,dimensions,Map.of(),MetricValue.quantity(amount,unit));
    }
    private static void unavailable() { throw new IllegalStateException("REPORT_INVENTORY_EVIDENCE_UNAVAILABLE"); }
}
