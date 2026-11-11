package ai.neargo.inventory.domain.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import ai.neargo.inventory.api.InventoryException;
import ai.neargo.inventory.domain.entity.*;
import ai.neargo.inventory.domain.mapper.InventoryMappers.*;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReplenishmentQueryServiceTest {
    final OutboundOrderMapper out=mock(OutboundOrderMapper.class);
    final OutboundLineMapper lines=mock(OutboundLineMapper.class);
    final InboundOrderMapper in=mock(InboundOrderMapper.class);
    final InboundLineMapper inLines=mock(InboundLineMapper.class);
    final DocumentTargetMapper targets=mock(DocumentTargetMapper.class);
    final DocumentSerialMapper serials=mock(DocumentSerialMapper.class);
    final PairedTransferMapper pairs=mock(PairedTransferMapper.class);
    final LedgerMapper ledger=mock(LedgerMapper.class);
    final ReplenishmentQueryServiceImpl service=new ReplenishmentQueryServiceImpl(out,lines,in,inLines,targets,serials,pairs,ledger);
    InvOutboundOrder head;InvOutboundLine line;
    @BeforeEach void setup(){
        for(Class<?> t:List.of(InvOutboundOrder.class,InvOutboundLine.class,InvInboundOrder.class,InvInboundLine.class,InvDocumentTarget.class,InvDocumentSerial.class,InvPairedTransfer.class,InvLedger.class))
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(),"test"),t);
        head=new InvOutboundOrder();head.setStatus("POSTED");head.setPurpose("INTERNAL");when(out.selectOne(any())).thenReturn(head);
        line=new InvOutboundLine();line.setItemId("ITEM");line.setUom("KG");line.setQtyQuantity(new BigDecimal("0.125"));when(lines.selectOne(any())).thenReturn(line);
        var target=new InvDocumentTarget();target.setTargetRef("STORE");when(targets.selectList(any())).thenReturn(List.of(target));
        var posting=new InvLedger();posting.setId(20L);posting.setItemId("ITEM");posting.setQtyDeltaQuantity(new BigDecimal("-0.125"));when(ledger.selectOne(any())).thenReturn(posting);
        when(pairs.selectCount(any())).thenReturn(0L);when(serials.selectCount(any())).thenReturn(0L);
    }
    @Test void postedOriginalExportsExactQuantityAndHistoricalLineUnit(){
        var result=service.outbound("OWNER","OUT",1);assertThat(result.baseUnit()).isEqualTo("KG");
        assertThat(result.quantity()).isEqualByComparingTo("0.125");assertThat(result.targetStoreNo()).isEqualTo("STORE");
        var query=org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(out).selectOne(query.capture());assertThat(query.getValue().getSqlSegment()).contains("owner_id","outbound_no");
    }
    @Test void checkpointAndPhysicalLineSequenceDoNotUseMutableDraftIdentity(){
        assertThat(service.checkpoint("OWNER").postingSequence()).isEqualTo(20);
        assertThat(service.outbound("OWNER","OUT",1).postingSequence()).isEqualTo(20);
        when(ledger.selectOne(any())).thenReturn(null);assertThat(service.checkpoint("EMPTY").postingSequence()).isZero();
        assertThatThrownBy(()->service.outbound("OWNER","OUT",1)).isInstanceOf(InventoryException.class);
    }
    @Test void draftSalesPairedAndSerialOriginalsCannotFeedAnotherLocalLedger(){
        head.setStatus("DRAFT");assertThatThrownBy(()->service.outbound("O","D",1)).isInstanceOf(InventoryException.class);
        head.setStatus("POSTED");head.setPurpose("SALE");assertThatThrownBy(()->service.outbound("O","D",1)).isInstanceOf(InventoryException.class);
        head.setPurpose("INTERNAL");when(pairs.selectCount(any())).thenReturn(1L);assertThatThrownBy(()->service.outbound("O","D",1)).isInstanceOf(InventoryException.class);
        when(pairs.selectCount(any())).thenReturn(0L);when(serials.selectCount(any())).thenReturn(1L);assertThatThrownBy(()->service.outbound("O","D",1)).isInstanceOf(InventoryException.class);
        when(serials.selectCount(any())).thenReturn(0L);when(targets.selectList(any())).thenReturn(List.of());assertThatThrownBy(()->service.outbound("O","D",1)).isInstanceOf(InventoryException.class);
    }
    @Test void legacyConsoleTargetIsAnOriginalFactAndConflictingSnapshotsFailClosed(){
        when(targets.selectList(any())).thenReturn(List.of());head.setTargetType("STORE");head.setTargetNo("LEGACY_STORE");
        assertThat(service.outbound("O","D",1).targetStoreNo()).isEqualTo("LEGACY_STORE");
        var target=new InvDocumentTarget();target.setTargetRef("DIFFERENT");when(targets.selectList(any())).thenReturn(List.of(target));
        assertThatThrownBy(()->service.outbound("O","D",1)).isInstanceOf(InventoryException.class);
        when(targets.selectList(any())).thenReturn(List.of());head.setTargetType("PERSON");
        assertThatThrownBy(()->service.outbound("O","D",1)).isInstanceOf(InventoryException.class);
    }
    @Test void returnAcknowledgementRequiresPostedReturnAndOriginalSourceReference(){
        var h=new InvInboundOrder();h.setStatus("POSTED");h.setSourceType("RETURN");h.setSourceRef("FRP1");when(in.selectOne(any())).thenReturn(h);
        var l=new InvInboundLine();l.setItemId("ITEM");l.setUom("KG");l.setQtyQuantity(new BigDecimal("0.125"));when(inLines.selectOne(any())).thenReturn(l);
        assertThat(service.inbound("O","IN",1).sourceRef()).isEqualTo("FRP1");
        h.setStatus("DRAFT");assertThatThrownBy(()->service.inbound("O","IN",1)).isInstanceOf(InventoryException.class);
        h.setStatus("POSTED");h.setSourceType("PURCHASE");assertThatThrownBy(()->service.inbound("O","IN",1)).isInstanceOf(InventoryException.class);
        h.setSourceType("RETURN");h.setSourceRef(null);assertThatThrownBy(()->service.inbound("O","IN",1)).isInstanceOf(InventoryException.class);
    }
}
