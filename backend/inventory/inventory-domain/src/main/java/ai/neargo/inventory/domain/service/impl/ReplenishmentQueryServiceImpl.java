package ai.neargo.inventory.domain.service.impl;

import ai.neargo.inventory.api.InventoryError;
import ai.neargo.inventory.api.InventoryException;
import ai.neargo.inventory.api.replenishment.ReplenishmentContracts.*;
import ai.neargo.inventory.domain.config.ConditionalOnInventory;
import ai.neargo.inventory.domain.entity.*;
import ai.neargo.inventory.domain.mapper.InventoryMappers.*;
import ai.neargo.inventory.domain.service.ReplenishmentQueryService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import org.springframework.transaction.annotation.Transactional;

/** Only physically posted, unpaired quantity handovers may enter a simple business ledger. */
@Service
@ConditionalOnInventory
@Transactional(transactionManager="invTransactionManager", readOnly=true)
public class ReplenishmentQueryServiceImpl implements ReplenishmentQueryService {
    private final OutboundOrderMapper outbounds;
    private final OutboundLineMapper outLines;
    private final InboundOrderMapper inbounds;
    private final InboundLineMapper inLines;
    private final DocumentTargetMapper targets;
    private final DocumentSerialMapper serials;
    private final PairedTransferMapper pairs;
    private final LedgerMapper ledger;
    public ReplenishmentQueryServiceImpl(OutboundOrderMapper outbounds, OutboundLineMapper outLines,
            InboundOrderMapper inbounds, InboundLineMapper inLines, DocumentTargetMapper targets,
            DocumentSerialMapper serials, PairedTransferMapper pairs,LedgerMapper ledger) {
        this.outbounds=outbounds;this.outLines=outLines;this.inbounds=inbounds;this.inLines=inLines;
        this.targets=targets;this.serials=serials;this.pairs=pairs;this.ledger=ledger;
    }
    @Override public Checkpoint checkpoint(String owner){
        var last=ledger.selectOne(Wrappers.<InvLedger>lambdaQuery().eq(InvLedger::getOwnerId,owner)
                .orderByDesc(InvLedger::getId).last("LIMIT 1"));
        return new Checkpoint(owner,last==null?0:last.getId());
    }
    @Override public Outbound outbound(String owner, String no, int line) {
        var head=outbounds.selectOne(Wrappers.<InvOutboundOrder>lambdaQuery()
                .eq(InvOutboundOrder::getOwnerId,owner).eq(InvOutboundOrder::getOutboundNo,no));
        if(head==null)throw InventoryException.of(InventoryError.NOT_FOUND,"outbound");
        if(!"POSTED".equals(head.getStatus())||!"INTERNAL".equals(head.getPurpose())
                ||pairs.selectCount(Wrappers.<InvPairedTransfer>lambdaQuery()
                    .eq(InvPairedTransfer::getFromOwnerId,owner).eq(InvPairedTransfer::getOutboundNo,no))!=0)
            throw invalid();
        var destinations=targets.selectList(Wrappers.<InvDocumentTarget>lambdaQuery()
                .eq(InvDocumentTarget::getOwnerId,owner).eq(InvDocumentTarget::getDocKind,"OUT")
                .eq(InvDocumentTarget::getDocNo,no).eq(InvDocumentTarget::getTargetType,"STORE"));
        var row=outLines.selectOne(Wrappers.<InvOutboundLine>lambdaQuery()
                .eq(InvOutboundLine::getOwnerId,owner).eq(InvOutboundLine::getOutboundNo,no)
                .eq(InvOutboundLine::getLineNo,line));
        if(destinations.size()>1||row==null||row.getParentSerialNo()!=null)throw invalid();
        String store=destinations.isEmpty()?head.getTargetNo():destinations.getFirst().getTargetRef();
        // Legacy console records the same immutable destination in the head, not inv_document_target.
        if(store==null||store.isBlank()||destinations.isEmpty()&&!"STORE".equals(head.getTargetType())
                ||head.getTargetNo()!=null&&(!"STORE".equals(head.getTargetType())||!store.equals(head.getTargetNo())))throw invalid();
        quantityOnly(owner,"OUT",no);unit(row.getUom(),row.getQtyQuantity());
        var posted=ledger.selectOne(Wrappers.<InvLedger>lambdaQuery().eq(InvLedger::getOwnerId,owner)
                .eq(InvLedger::getDocKind,"OUT").eq(InvLedger::getDocNo,no).eq(InvLedger::getLineNo,line));
        if(posted==null||posted.getId()==null||!row.getItemId().equals(posted.getItemId())
                ||posted.getQtyDeltaQuantity()==null||posted.getQtyDeltaQuantity().compareTo(row.getQtyQuantity().negate())!=0)throw invalid();
        return new Outbound(owner,no,line,row.getItemId(),row.getUom(),row.getQtyQuantity(),store,posted.getId());
    }
    @Override public Inbound inbound(String owner, String no, int line) {
        var head=inbounds.selectOne(Wrappers.<InvInboundOrder>lambdaQuery()
                .eq(InvInboundOrder::getOwnerId,owner).eq(InvInboundOrder::getInboundNo,no));
        if(head==null)throw InventoryException.of(InventoryError.NOT_FOUND,"inbound");
        if(!"POSTED".equals(head.getStatus())||!"RETURN".equals(head.getSourceType())
                ||head.getSourceRef()==null||head.getSourceRef().isBlank())throw invalid();
        var row=inLines.selectOne(Wrappers.<InvInboundLine>lambdaQuery()
                .eq(InvInboundLine::getOwnerId,owner).eq(InvInboundLine::getInboundNo,no)
                .eq(InvInboundLine::getLineNo,line));
        if(row==null)throw invalid();quantityOnly(owner,"IN",no);unit(row.getUom(),row.getQtyQuantity());
        return new Inbound(owner,no,line,row.getItemId(),row.getUom(),row.getQtyQuantity(),head.getSourceRef());
    }
    private void quantityOnly(String owner,String kind,String no) {
        if(serials.selectCount(Wrappers.<InvDocumentSerial>lambdaQuery()
                .eq(InvDocumentSerial::getOwnerId,owner).eq(InvDocumentSerial::getDocKind,kind)
                .eq(InvDocumentSerial::getDocNo,no))!=0)throw invalid();
    }
    private static void unit(String uom,BigDecimal quantity) {
        if(uom==null||uom.isBlank()||quantity==null||quantity.signum()<=0)throw invalid();
    }
    private static InventoryException invalid(){return InventoryException.of(InventoryError.CONFLICT,"business.replenishment.original");}
}
