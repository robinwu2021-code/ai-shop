package ai.neargo.inventory.domain.service.impl;

import ai.neargo.inventory.api.InventoryError;
import ai.neargo.inventory.api.InventoryException;
import ai.neargo.inventory.domain.config.ConditionalOnInventory;
import ai.neargo.inventory.domain.entity.InvDocumentSerial;
import ai.neargo.inventory.domain.entity.InvInboundLine;
import ai.neargo.inventory.domain.entity.InvInboundOrder;
import ai.neargo.inventory.domain.entity.InvItem;
import ai.neargo.inventory.domain.entity.InvLocation;
import ai.neargo.inventory.domain.entity.InvOutboundLine;
import ai.neargo.inventory.domain.entity.InvStockEpisode;
import ai.neargo.inventory.domain.entity.InvTransferOrder;
import ai.neargo.inventory.domain.mapper.InventoryMappers.DocumentSerialMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.InboundLineMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.InboundOrderMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.LocationMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.OutboundLineMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.StockEpisodeMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.TransferOrderMapper;
import ai.neargo.inventory.domain.service.AuditService;
import ai.neargo.inventory.domain.service.Caller;
import ai.neargo.inventory.domain.service.EventPublisher;
import ai.neargo.inventory.domain.service.HostInboundService;
import ai.neargo.inventory.domain.service.HostOutboundService;
import ai.neargo.inventory.domain.service.HostTransferService;
import ai.neargo.inventory.domain.service.LocationService;
import ai.neargo.inventory.domain.support.InvEnums;
import ai.neargo.inventory.domain.support.InvKeys;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ConditionalOnInventory
@Service
public class HostTransferServiceImpl implements HostTransferService {

    private final HostDocumentSupport support;
    private final HostOutboundService outbounds;
    private final HostInboundService inbounds;
    private final LocationService locations;
    private final TransferOrderMapper transferMapper;
    private final LocationMapper locationMapper;
    private final OutboundLineMapper outboundLineMapper;
    private final InboundOrderMapper inboundOrderMapper;
    private final InboundLineMapper inboundLineMapper;
    private final DocumentSerialMapper docSerialMapper;
    private final StockEpisodeMapper episodeMapper;
    private final EventPublisher events;
    private final AuditService audit;

    public HostTransferServiceImpl(HostDocumentSupport support, HostOutboundService outbounds,
                                   HostInboundService inbounds, LocationService locations,
                                   TransferOrderMapper transferMapper, LocationMapper locationMapper,
                                   OutboundLineMapper outboundLineMapper, InboundOrderMapper inboundOrderMapper,
                                   InboundLineMapper inboundLineMapper, DocumentSerialMapper docSerialMapper,
                                   StockEpisodeMapper episodeMapper, EventPublisher events, AuditService audit) {
        this.support = support;
        this.outbounds = outbounds;
        this.inbounds = inbounds;
        this.locations = locations;
        this.transferMapper = transferMapper;
        this.locationMapper = locationMapper;
        this.outboundLineMapper = outboundLineMapper;
        this.inboundOrderMapper = inboundOrderMapper;
        this.inboundLineMapper = inboundLineMapper;
        this.docSerialMapper = docSerialMapper;
        this.episodeMapper = episodeMapper;
        this.events = events;
        this.audit = audit;
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public String create(Caller caller, Draft draft) {
        if ((draft.lines() == null || draft.lines().isEmpty())
                && (draft.serialNos() == null || draft.serialNos().isEmpty())) {
            throw InventoryException.of(InventoryError.BAD_REQUEST, "lines");
        }
        if (draft.fromLocationId().equals(draft.toLocationId())) {
            // 同一库位不是调拨，是什么都没发生
            throw InventoryException.of(InventoryError.BAD_REQUEST, "toLocationId");
        }
        support.requireActiveLocation(caller.ownerId(), draft.toLocationId());
        InvLocation to = locationMapper.selectOne(Wrappers.<InvLocation>lambdaQuery()
                .eq(InvLocation::getOwnerId, caller.ownerId())
                .eq(InvLocation::getLocationId, draft.toLocationId()));
        if (InvEnums.LocationKind.TRANSIT.equals(to.getKind())) {
            throw InventoryException.of(InventoryError.BAD_REQUEST, "toLocationId.transit");
        }
        String no = InvKeys.next(InvKeys.TRANSFER);
        // 调出草稿同时是「这次要搬哪些货」的存放处；扫柜带出柜内宝，逐件记在单上
        String outboundNo = outbounds.transferDraft(caller, draft.fromLocationId(), no, draft.lines(),
                draft.serialNos());
        InvTransferOrder head = new InvTransferOrder();
        head.setTransferNo(no);
        head.setOwnerId(caller.ownerId());
        head.setFromLocationId(draft.fromLocationId());
        head.setToLocationId(draft.toLocationId());
        head.setStatus(InvEnums.TransferStatus.DRAFT);
        head.setShippedOutboundNo(outboundNo);
        head.setOperator(HostDocumentSupport.operator(caller));
        head.setRemark(draft.remark());
        head.setCreatedBy(HostDocumentSupport.operator(caller));
        transferMapper.insert(head);
        audit.record(caller, "transfer.create", "TRANSFER", no, null, draft, null);
        return no;
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public void ship(Caller caller, String transferNo) {
        InvTransferOrder head = lock(caller.ownerId(), transferNo, InvEnums.TransferStatus.DRAFT);
        outbounds.post(caller, head.getShippedOutboundNo());
        // 同一事务进在途：出与入成对，账本合计不变；SN 的放行凭证取自刚关闭的那一轮
        String transit = locations.transitLocation(caller.ownerId());
        inbounds.transferIn(caller, transit, transferNo,
                inboundLines(caller, head.getShippedOutboundNo(), shippedSerials(caller, head), transferNo));
        head.setStatus(InvEnums.TransferStatus.SHIPPED);
        head.setShippedAt(LocalDateTime.now());
        head.setUpdatedBy(HostDocumentSupport.operator(caller));
        transferMapper.updateById(head);
        events.publish(caller, "TransferShipped", "TRANSFER", transferNo, 1,
                Map.of("fromLocationId", head.getFromLocationId(), "toLocationId", head.getToLocationId()));
        audit.record(caller, "transfer.ship", "TRANSFER", transferNo, null, null, null);
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public void receive(Caller caller, String transferNo) {
        InvTransferOrder head = lock(caller.ownerId(), transferNo, InvEnums.TransferStatus.SHIPPED);
        String transit = locations.transitLocation(caller.ownerId());
        InvInboundOrder inTransit = inboundOrderMapper.selectOne(Wrappers.<InvInboundOrder>lambdaQuery()
                .eq(InvInboundOrder::getOwnerId, caller.ownerId())
                .eq(InvInboundOrder::getLocationId, transit)
                .eq(InvInboundOrder::getSourceType, InvEnums.InboundSource.TRANSFER_IN)
                .eq(InvInboundOrder::getSourceRef, transferNo));
        List<InvDocumentSerial> serials = docSerials(caller.ownerId(), InvEnums.DocKind.IN, inTransit.getInboundNo());
        Map<String, Integer> quantities = new LinkedHashMap<>();
        for (InvInboundLine l : inboundLineMapper.selectList(Wrappers.<InvInboundLine>lambdaQuery()
                .eq(InvInboundLine::getOwnerId, caller.ownerId())
                .eq(InvInboundLine::getInboundNo, inTransit.getInboundNo()))) {
            if (serials.stream().noneMatch(s -> s.getItemId().equals(l.getItemId()))) {
                quantities.merge(l.getItemId(), l.getQtyQuantity().intValueExact(), Integer::sum);
            }
        }
        // 从在途调出：逐件列出（不再展开），组装关系与凭证按在途那张入库单原样带到调入仓
        String out = outbounds.transferOut(caller, transit, transferNo,
                quantities.entrySet().stream().map(e -> new HostOutboundService.Line(e.getKey(), e.getValue()))
                        .toList(),
                serials.stream().map(InvDocumentSerial::getSerialNo).toList());
        Map<String, List<HostInboundService.Serial>> bySerialItem = new LinkedHashMap<>();
        for (InvDocumentSerial s : serials) {
            bySerialItem.computeIfAbsent(s.getItemId(), k -> new ArrayList<>())
                    .add(new HostInboundService.Serial(s.getSerialNo(), s.getGateRef(), s.getParentSerialNo()));
        }
        List<HostInboundService.Line> lines = new ArrayList<>();
        bySerialItem.forEach((item, list) -> lines.add(new HostInboundService.Line(item, null, null, list)));
        quantities.forEach((item, qty) -> lines.add(new HostInboundService.Line(item, qty,
                "TRANSFER:" + transferNo, List.of())));
        String received = inbounds.transferIn(caller, head.getToLocationId(), transferNo, lines);
        head.setStatus(InvEnums.TransferStatus.RECEIVED);
        head.setReceivedInboundNo(received);
        head.setReceivedAt(LocalDateTime.now());
        head.setUpdatedBy(HostDocumentSupport.operator(caller));
        transferMapper.updateById(head);
        events.publish(caller, "TransferReceived", "TRANSFER", transferNo, 1,
                Map.of("transitOutboundNo", out, "inboundNo", received));
        audit.record(caller, "transfer.receive", "TRANSFER", transferNo, null, received, null);
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public void cancel(Caller caller, String transferNo) {
        InvTransferOrder head = lock(caller.ownerId(), transferNo, InvEnums.TransferStatus.DRAFT);
        outbounds.cancel(caller, head.getShippedOutboundNo());
        head.setStatus(InvEnums.TransferStatus.VOIDED);
        head.setUpdatedBy(HostDocumentSupport.operator(caller));
        transferMapper.updateById(head);
        audit.record(caller, "transfer.cancel", "TRANSFER", transferNo, null, null, null);
    }

    @Override
    public View get(String ownerId, String transferNo) {
        InvTransferOrder head = transferMapper.selectOne(Wrappers.<InvTransferOrder>lambdaQuery()
                .eq(InvTransferOrder::getOwnerId, ownerId).eq(InvTransferOrder::getTransferNo, transferNo));
        if (head == null) {
            throw InventoryException.of(InventoryError.NOT_FOUND, "transfer");
        }
        List<InvDocumentSerial> serials = docSerials(ownerId, InvEnums.DocKind.OUT, head.getShippedOutboundNo());
        List<Line> lines = new ArrayList<>();
        for (InvOutboundLine l : outboundLines(ownerId, head.getShippedOutboundNo())) {
            lines.add(new Line(l.getItemId(), l.getQtyQuantity().intValueExact(), serials.stream()
                    .filter(s -> s.getItemId().equals(l.getItemId()) && s.getLineNo().equals(l.getLineNo()))
                    .map(InvDocumentSerial::getSerialNo).toList()));
        }
        return new View(head.getTransferNo(), head.getStatus(), head.getFromLocationId(), head.getToLocationId(),
                head.getShippedOutboundNo(), head.getReceivedInboundNo(), head.getShippedAt(), head.getReceivedAt(),
                lines);
    }

    /** 发运单上的 SN：放行凭证取自出库关闭的那一轮在库周期，上级件取单据上记下的展开关系 */
    private Map<String, List<HostInboundService.Serial>> shippedSerials(Caller caller, InvTransferOrder head) {
        Map<String, List<HostInboundService.Serial>> out = new LinkedHashMap<>();
        for (InvDocumentSerial s : docSerials(caller.ownerId(), InvEnums.DocKind.OUT, head.getShippedOutboundNo())) {
            InvStockEpisode closed = episodeMapper.selectOne(Wrappers.<InvStockEpisode>lambdaQuery()
                    .eq(InvStockEpisode::getOwnerId, caller.ownerId())
                    .eq(InvStockEpisode::getSerialId, s.getSerialId())
                    .eq(InvStockEpisode::getCloseDocNo, head.getShippedOutboundNo()));
            out.computeIfAbsent(s.getItemId(), k -> new ArrayList<>()).add(new HostInboundService.Serial(
                    s.getSerialNo(), closed.getGateRef(), s.getParentSerialNo()));
        }
        return out;
    }

    /** SN 物料按件入，数量物料按行入、放行依据记调拨单号 */
    private List<HostInboundService.Line> inboundLines(Caller caller, String outboundNo,
                                                       Map<String, List<HostInboundService.Serial>> serials,
                                                       String transferNo) {
        List<HostInboundService.Line> lines = new ArrayList<>();
        serials.forEach((item, list) -> lines.add(new HostInboundService.Line(item, null, null, list)));
        List<InvOutboundLine> outLines = outboundLines(caller.ownerId(), outboundNo);
        Map<String, InvItem> items = support.activeItems(caller.ownerId(),
                outLines.stream().map(InvOutboundLine::getItemId).distinct().toList());
        Map<String, Integer> quantities = new HashMap<>();
        for (InvOutboundLine l : outLines) {
            if (!HostDocumentSupport.serial(items.get(l.getItemId()))) {
                quantities.merge(l.getItemId(), l.getQtyQuantity().intValueExact(), Integer::sum);
            }
        }
        quantities.forEach((item, qty) -> lines.add(new HostInboundService.Line(item, qty,
                "TRANSFER:" + transferNo, List.of())));
        return lines;
    }

    private InvTransferOrder lock(String ownerId, String transferNo, String expected) {
        InvTransferOrder head = transferMapper.selectOne(Wrappers.<InvTransferOrder>lambdaQuery()
                .eq(InvTransferOrder::getOwnerId, ownerId).eq(InvTransferOrder::getTransferNo, transferNo)
                .last("FOR UPDATE"));
        if (head == null) {
            throw InventoryException.of(InventoryError.NOT_FOUND, "transfer");
        }
        if (!expected.equals(head.getStatus())) {
            throw InventoryException.of(InventoryError.CONFLICT, "status");
        }
        return head;
    }

    private List<InvOutboundLine> outboundLines(String ownerId, String outboundNo) {
        return outboundLineMapper.selectList(Wrappers.<InvOutboundLine>lambdaQuery()
                .eq(InvOutboundLine::getOwnerId, ownerId).eq(InvOutboundLine::getOutboundNo, outboundNo)
                .orderByAsc(InvOutboundLine::getLineNo));
    }

    private List<InvDocumentSerial> docSerials(String ownerId, String kind, String docNo) {
        return docSerialMapper.selectList(Wrappers.<InvDocumentSerial>lambdaQuery()
                .eq(InvDocumentSerial::getOwnerId, ownerId).eq(InvDocumentSerial::getDocKind, kind)
                .eq(InvDocumentSerial::getDocNo, docNo).orderByAsc(InvDocumentSerial::getSerialNo));
    }
}
