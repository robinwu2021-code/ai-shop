package ai.neargo.shop.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.inventory.entity.InvOwner;
import ai.neargo.shop.inventory.entity.InvOutboundOrder;
import ai.neargo.shop.inventory.entity.InvOutboundLine;
import ai.neargo.shop.inventory.mapper.InventoryMappers.OwnerMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.OutboundOrderMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.OutboundLineMapper;
import ai.neargo.shop.inventory.port.InventoryProductionPortImpl;
import ai.neargo.shop.spi.inventory.InventoryProductionPort.Command;
import ai.neargo.shop.spi.inventory.InventoryProductionPort.Line;
import ai.neargo.shop.spi.inventory.InventoryProductionFacts;
import ai.neargo.shop.spi.inventory.MaterialCatalogQueryPort;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Original owner receipt and base-unit precision; no fake physical inventory posting. */
class InventoryProductionPortTest {
    final InventoryAclService acl=mock(InventoryAclService.class);
    final OwnerMapper owners=mock(OwnerMapper.class);
    final OutboundOrderMapper heads=mock(OutboundOrderMapper.class);
    final OutboundLineMapper lines=mock(OutboundLineMapper.class);
    final OutboundService outbound=mock(OutboundService.class);
    final MaterialCatalogQueryPort materials=mock(MaterialCatalogQueryPort.class);
    final InventoryProductionPortImpl service=new InventoryProductionPortImpl(acl,materials,owners,heads,lines,outbound);
    Command command(String qty) {
        return new Command("E","S","EVENT","PRODUCTION",Instant.parse("2026-10-24T00:00:00Z"),
                List.of(new Line("I",new BigDecimal(qty),"G","a".repeat(64))));
    }
    void owner() {
        when(acl.findOwnerId("E")).thenReturn("OWNER");
        when(acl.stockLocationOfExistingStore("E","S")).thenReturn("LOCATION");
        var owner=new InvOwner(); owner.setOwnerId("OWNER"); owner.setStatus("ACTIVE");
        when(owners.selectOne(any())).thenReturn(owner);
    }
    void original(Command c,String actualQty) {
        var head=new InvOutboundOrder(); head.setOwnerId("OWNER"); head.setOutboundNo("OUT");
        head.setStatus("POSTED"); head.setRemark(InventoryProductionFacts.hash(c));
        when(heads.selectOne(any())).thenReturn(head);
        var line=new InvOutboundLine(); line.setOwnerId("OWNER"); line.setOutboundNo("OUT");
        line.setItemId("I"); line.setQty(Integer.valueOf(actualQty)); line.setUom("G");
        when(lines.selectList(any())).thenReturn(List.of(line));
    }
    @Test void fractionalQuantityCannotBeRoundedIntoOwnerIntegerPosting() {
        owner();
        assertThatThrownBy(() -> service.consume(command("0.5"))).isInstanceOf(BizException.class);
        verifyNoInteractions(outbound,materials);
    }
    @Test void exactOriginalPostedReceiptReplaysWithoutAnotherIssueOrCurrentDefinitionLookup() {
        owner(); original(command("2"),"2");
        var receipt=service.consume(command("2.000000"));
        assertThat(receipt.status()).isEqualTo("CONFIRMED");
        assertThat(receipt.documentNo()).isEqualTo("OUT");
        assertThat(receipt.costMinor()).isNull(); assertThat(receipt.currency()).isNull();
        verifyNoInteractions(outbound,materials);
    }
    @Test void changedOriginalQuantityOrRequestNeverBecomesAnotherPosting() {
        owner(); original(command("2"),"1");
        assertThatThrownBy(() -> service.consume(command("2"))).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.consume(command("3"))).isInstanceOf(BizException.class);
        verifyNoInteractions(outbound,materials);
    }
    @Test void queryAbsenceDoesNotCreateOwnerLocationOrOutbound() {
        when(acl.findOwnerId("E")).thenReturn("OWNER");
        assertThat(service.query(command("2")).status()).isEqualTo("ABSENT");
        verify(acl,never()).stockLocationOfExistingStore(anyString(),anyString());
        verifyNoInteractions(outbound,materials,owners,lines);
    }
}
