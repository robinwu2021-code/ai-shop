package ai.neargo.shop.inventory;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import ai.neargo.shop.inventory.entity.InvItem;
import ai.neargo.shop.inventory.entity.InvLedger;
import ai.neargo.shop.inventory.mapper.InventoryMappers.ItemMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.LedgerMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.BalanceMapper;
import ai.neargo.shop.inventory.service.InventoryAclService;
import ai.neargo.shop.inventory.port.InventoryReportFactsPortAdapter;
import ai.neargo.shop.spi.report.ReportContracts.DayEvidence;
import ai.neargo.shop.spi.report.ReportContracts.Partition;
import ai.neargo.shop.spi.report.ReportContracts.Scope;
import ai.neargo.shop.spi.report.ReportContracts.SourceQuery;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

class InventoryReportFactsServiceTest {
    @Test void preservesFractionalMovementsAndDoesNotReadCurrentBalancesForHistory() {
        var acl=mock(InventoryAclService.class);var ledger=mock(LedgerMapper.class);var items=mock(ItemMapper.class);
        var balances=mock(BalanceMapper.class);var manager=mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        when(acl.findOwnerId("E1")).thenReturn("OWNER1");when(acl.stockLocationOfExistingStore("E1","S1")).thenReturn("LOC1");
        var row=new InvLedger();row.setId(1L);row.setItemId("M1");row.setDocNo("D1");row.setDocKind("IN");
        row.setOccurredAt(LocalDateTime.of(2020,1,1,12,0));row.setQtyDeltaQuantity(new BigDecimal("0.125"));
        row.setBalanceAfterQuantity(new BigDecimal("1.125"));when(ledger.selectList(any())).thenReturn(List.of(row));
        var item=new InvItem();item.setBaseUom("KG");when(items.selectOne(any())).thenReturn(item);
        var service=new InventoryReportFactsPortAdapter(acl,ledger,items,balances,"UTC",manager);
        var start=Instant.parse("2020-01-01T00:00:00Z");
        var result=service.readSnapshot(new SourceQuery(new Partition(new Scope("MAIN","E1","S1"),LocalDate.of(2020,1,1),1),
                new DayEvidence("D1","P1",0,"UTC",0,start,start.plusSeconds(86400)),"ACTOR",List.of(),100));
        assertThat(result.facts()).hasSize(1);assertThat(result.facts().getFirst().value().quantityValue()).isEqualByComparingTo("0.125");
        assertThat(result.facts().getFirst().value().unit()).isEqualTo("KG");verifyNoInteractions(balances);
    }
}
