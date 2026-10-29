package ai.neargo.inventory.domain.service.impl;

import ai.neargo.inventory.api.InventoryError;
import ai.neargo.inventory.api.InventoryException;
import ai.neargo.inventory.domain.config.ConditionalOnInventory;
import ai.neargo.inventory.domain.entity.InvDocumentSerial;
import ai.neargo.inventory.domain.entity.InvDocumentTarget;
import ai.neargo.inventory.domain.entity.InvInboundLine;
import ai.neargo.inventory.domain.entity.InvInboundOrder;
import ai.neargo.inventory.domain.entity.InvOutboundLine;
import ai.neargo.inventory.domain.entity.InvOutboundOrder;
import ai.neargo.inventory.domain.mapper.InventoryMappers.DocumentSerialMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.DocumentTargetMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.InboundLineMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.InboundOrderMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.OutboundLineMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.OutboundOrderMapper;
import ai.neargo.inventory.domain.service.DocumentQueryService;
import ai.neargo.inventory.domain.support.InvEnums;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;

import java.util.List;

@ConditionalOnInventory
@Service
public class DocumentQueryServiceImpl implements DocumentQueryService {

    private final InboundOrderMapper inboundMapper;
    private final InboundLineMapper inboundLineMapper;
    private final OutboundOrderMapper outboundMapper;
    private final OutboundLineMapper outboundLineMapper;
    private final DocumentSerialMapper serialMapper;
    private final DocumentTargetMapper targetMapper;

    public DocumentQueryServiceImpl(InboundOrderMapper inboundMapper, InboundLineMapper inboundLineMapper,
                                    OutboundOrderMapper outboundMapper, OutboundLineMapper outboundLineMapper,
                                    DocumentSerialMapper serialMapper, DocumentTargetMapper targetMapper) {
        this.inboundMapper = inboundMapper;
        this.inboundLineMapper = inboundLineMapper;
        this.outboundMapper = outboundMapper;
        this.outboundLineMapper = outboundLineMapper;
        this.serialMapper = serialMapper;
        this.targetMapper = targetMapper;
    }

    @Override
    public Document inbound(String ownerId, String inboundNo) {
        InvInboundOrder o = inboundMapper.selectOne(Wrappers.<InvInboundOrder>lambdaQuery()
                .eq(InvInboundOrder::getOwnerId, ownerId).eq(InvInboundOrder::getInboundNo, inboundNo));
        if (o == null) {
            throw InventoryException.of(InventoryError.NOT_FOUND, "inbound");
        }
        List<Line> lines = inboundLineMapper.selectList(Wrappers.<InvInboundLine>lambdaQuery()
                        .eq(InvInboundLine::getInboundNo, inboundNo).orderByAsc(InvInboundLine::getLineNo))
                .stream().map(l -> new Line(l.getLineNo(), l.getItemId(), l.getQtyQuantity(), l.getGateRef()))
                .toList();
        return new Document(o.getInboundNo(), InvEnums.DocKind.IN, o.getStatus(), o.getSourceType(),
                o.getLocationId(), o.getTotalQtyQuantity(), o.getProfileVersion(), lines,
                serials(ownerId, InvEnums.DocKind.IN, inboundNo), targets(ownerId, InvEnums.DocKind.IN, inboundNo));
    }

    @Override
    public Document outbound(String ownerId, String outboundNo) {
        InvOutboundOrder o = outboundMapper.selectOne(Wrappers.<InvOutboundOrder>lambdaQuery()
                .eq(InvOutboundOrder::getOwnerId, ownerId).eq(InvOutboundOrder::getOutboundNo, outboundNo));
        if (o == null) {
            throw InventoryException.of(InventoryError.NOT_FOUND, "outbound");
        }
        List<Line> lines = outboundLineMapper.selectList(Wrappers.<InvOutboundLine>lambdaQuery()
                        .eq(InvOutboundLine::getOutboundNo, outboundNo).orderByAsc(InvOutboundLine::getLineNo))
                .stream().map(l -> new Line(l.getLineNo(), l.getItemId(), l.getQtyQuantity(), null)).toList();
        return new Document(o.getOutboundNo(), InvEnums.DocKind.OUT, o.getStatus(), o.getPurpose(),
                o.getLocationId(), o.getTotalQtyQuantity(), o.getProfileVersion(), lines,
                serials(ownerId, InvEnums.DocKind.OUT, outboundNo), targets(ownerId, InvEnums.DocKind.OUT, outboundNo));
    }

    private List<Serial> serials(String ownerId, String kind, String docNo) {
        return serialMapper.selectList(Wrappers.<InvDocumentSerial>lambdaQuery()
                        .eq(InvDocumentSerial::getOwnerId, ownerId).eq(InvDocumentSerial::getDocKind, kind)
                        .eq(InvDocumentSerial::getDocNo, docNo)
                        .orderByAsc(InvDocumentSerial::getLineNo, InvDocumentSerial::getSerialNo))
                .stream().map(s -> new Serial(s.getLineNo(), s.getItemId(), s.getSerialNo(), s.getParentSerialNo(),
                        s.getGateRef())).toList();
    }

    private List<Target> targets(String ownerId, String kind, String docNo) {
        return targetMapper.selectList(Wrappers.<InvDocumentTarget>lambdaQuery()
                        .eq(InvDocumentTarget::getOwnerId, ownerId).eq(InvDocumentTarget::getDocKind, kind)
                        .eq(InvDocumentTarget::getDocNo, docNo).orderByAsc(InvDocumentTarget::getTargetType))
                .stream().map(t -> new Target(t.getTargetType(), t.getTargetRef(), t.getNameSnapshot())).toList();
    }
}
