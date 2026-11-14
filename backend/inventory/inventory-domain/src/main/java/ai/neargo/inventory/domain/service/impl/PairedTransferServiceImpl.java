package ai.neargo.inventory.domain.service.impl;

import ai.neargo.inventory.api.InventoryError;
import ai.neargo.inventory.api.InventoryException;
import ai.neargo.inventory.domain.config.ConditionalOnInventory;
import ai.neargo.inventory.domain.entity.InvItem;
import ai.neargo.inventory.domain.entity.InvOutboundLine;
import ai.neargo.inventory.domain.entity.InvOutboundOrder;
import ai.neargo.inventory.domain.entity.InvOwner;
import ai.neargo.inventory.domain.entity.InvPairedTransfer;
import ai.neargo.inventory.domain.entity.InvTargetRef;
import ai.neargo.inventory.domain.mapper.InventoryMappers.ItemMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.PairedTransferMapper;
import ai.neargo.inventory.domain.service.AuditService;
import ai.neargo.inventory.domain.service.Caller;
import ai.neargo.inventory.domain.service.EventPublisher;
import ai.neargo.inventory.domain.service.HostCatalogService;
import ai.neargo.inventory.domain.service.HostCatalogService.ItemSpec;
import ai.neargo.inventory.domain.service.HostInboundService;
import ai.neargo.inventory.domain.service.PairedTransferService;
import ai.neargo.inventory.domain.service.ServicePlatformService;
import ai.neargo.inventory.domain.support.InvEnums;
import ai.neargo.inventory.domain.support.InvKeys;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ConditionalOnInventory
@Service
public class PairedTransferServiceImpl implements PairedTransferService {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<List<Line>> LINES = new TypeReference<>() {
    };

    private final PairedTransferMapper pairMapper;
    private final ItemMapper itemMapper;
    private final HostCatalogService catalog;
    private final HostInboundService inbound;
    private final ServicePlatformService platform;
    private final EventPublisher events;
    private final AuditService audit;

    public PairedTransferServiceImpl(PairedTransferMapper pairMapper, ItemMapper itemMapper,
                                     HostCatalogService catalog, HostInboundService inbound,
                                     ServicePlatformService platform, EventPublisher events, AuditService audit) {
        this.pairMapper = pairMapper;
        this.itemMapper = itemMapper;
        this.catalog = catalog;
        this.inbound = inbound;
        this.platform = platform;
        this.events = events;
        this.audit = audit;
    }

    @Override
    public void checkShippable(InvTargetRef target, List<String> serialNos) {
        if (target.getLinkedOwnerId() == null) {
            // 没关联账本的对象只是一个去向，货出去就离开了库存服务；要一出一入，宿主得先同步关联
            throw InventoryException.of(InventoryError.BAD_REQUEST, "target.not_linked");
        }
        if (serialNos != null && !serialNos.isEmpty()) {
            throw InventoryException.of(InventoryError.BAD_REQUEST, "paired.serial_unsupported");
        }
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public void ship(Caller caller, InvOutboundOrder order, List<InvOutboundLine> lines, InvTargetRef target,
                     String receiptPurpose) {
        InvOwner to = platform.owner(target.getLinkedOwnerId());
        if (!InvEnums.WriteAuthority.SVC.equals(to.getWriteAuthority())) {
            // 对方这本账还由宿主内嵌在写：服务替它建单就成了两边同时写
            throw InventoryException.of(InventoryError.NOT_WRITER, to.getWriteAuthority());
        }
        Caller receiver = new Caller(caller.systemCode(), to.getOwnerId(), caller.actor(), null, caller.traceId());

        // 两本账的物料各自独立，按宿主物料编号对应；同一物料多行合成一行，收货时按物料确认
        Map<String, InvItem> fromItems = new HashMap<>();
        itemMapper.selectList(Wrappers.<InvItem>lambdaQuery()
                        .eq(InvItem::getOwnerId, caller.ownerId())
                        .in(InvItem::getItemId, lines.stream().map(InvOutboundLine::getItemId).distinct().toList()))
                .forEach(i -> fromItems.put(i.getItemId(), i));
        Map<String, Integer> shipped = new LinkedHashMap<>();
        for (InvOutboundLine line : lines) {
            shipped.merge(line.getItemId(), line.getQtyQuantity().intValueExact(), Integer::sum);
        }
        List<Line> pairLines = new ArrayList<>();
        List<HostInboundService.Line> inboundLines = new ArrayList<>();
        for (Map.Entry<String, Integer> e : shipped.entrySet()) {
            InvItem from = fromItems.get(e.getKey());
            if (from.getExternalRef() == null) {
                throw InventoryException.of(InventoryError.BAD_REQUEST, "paired.item_ref:" + from.getItemId());
            }
            String toItemId = receiverItem(receiver, from);
            pairLines.add(new Line(from.getExternalRef(), from.getItemId(), toItemId, e.getValue(), null));
            inboundLines.add(new HostInboundService.Line(toItemId, e.getValue(), null, null));
        }

        String pairNo = InvKeys.next(InvKeys.PAIRED);
        String inboundNo = inbound.create(receiver, new HostInboundService.Draft(target.getLinkedLocationId(),
                receiptPurpose, order.getOutboundNo(), "PAIRED:" + pairNo, null, order.getRemark(), inboundLines));

        InvPairedTransfer pair = new InvPairedTransfer();
        pair.setPairNo(pairNo);
        pair.setSystemCode(caller.systemCode());
        pair.setFromOwnerId(caller.ownerId());
        pair.setOutboundNo(order.getOutboundNo());
        pair.setFromLocationId(order.getLocationId());
        pair.setToOwnerId(to.getOwnerId());
        pair.setInboundNo(inboundNo);
        pair.setToLocationId(target.getLinkedLocationId());
        pair.setStatus(InvEnums.TransferStatus.SHIPPED);
        pair.setLineItems(JSON.writeValueAsString(pairLines));
        pair.setShippedAt(LocalDateTime.now());
        pair.setCreatedBy(HostDocumentSupport.operator(caller));
        pairMapper.insert(pair);

        InvOwner from = platform.owner(caller.ownerId());
        events.publish(receiver, "PairedInboundCreated", "INBOUND", inboundNo, 1, Map.of(
                "pairNo", pairNo, "inboundNo", inboundNo, "outboundNo", order.getOutboundNo(),
                "fromOwnerType", from.getExternalType(), "fromOwnerRef", from.getExternalRef()));
        audit.record(caller, "paired.ship", "PAIRED", pairNo, null, pairLines, null);
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public String receive(Caller caller, String inboundNo, List<Received> received) {
        InvPairedTransfer pair = pairMapper.selectOne(Wrappers.<InvPairedTransfer>lambdaQuery()
                .eq(InvPairedTransfer::getToOwnerId, caller.ownerId())
                .eq(InvPairedTransfer::getInboundNo, inboundNo)
                .last("FOR UPDATE"));
        if (pair == null) {
            throw InventoryException.of(InventoryError.NOT_FOUND, "paired");
        }
        if (!InvEnums.TransferStatus.SHIPPED.equals(pair.getStatus())) {
            throw InventoryException.of(InventoryError.CONFLICT, "status");
        }
        List<Line> lines = JSON.readValue(pair.getLineItems(), LINES);
        Map<String, Integer> got = new HashMap<>();
        for (Received r : received) {
            if (lines.stream().noneMatch(l -> l.toItemId().equals(r.itemId()))) {
                throw InventoryException.of(InventoryError.BAD_REQUEST, "item:" + r.itemId());
            }
            got.merge(r.itemId(), r.qty(), Integer::sum);
        }

        // 待收单按实收改写（行只由入库服务写）：收 0 的物料不上单；一件没收到就作废
        List<HostInboundService.Line> actual = lines.stream()
                .filter(l -> got.getOrDefault(l.toItemId(), 0) > 0)
                .map(l -> new HostInboundService.Line(l.toItemId(), got.get(l.toItemId()), null, null))
                .toList();
        if (actual.isEmpty()) {
            inbound.cancel(caller, inboundNo);
        } else {
            inbound.replaceLines(caller, inboundNo, actual);
            inbound.post(caller, inboundNo);
        }

        List<Line> settled = lines.stream()
                .map(l -> new Line(l.itemRef(), l.fromItemId(), l.toItemId(), l.shipped(),
                        got.getOrDefault(l.toItemId(), 0)))
                .toList();
        pairMapper.update(null, Wrappers.<InvPairedTransfer>lambdaUpdate()
                .set(InvPairedTransfer::getStatus, InvEnums.TransferStatus.RECEIVED)
                .set(InvPairedTransfer::getReceivedAt, LocalDateTime.now())
                .set(InvPairedTransfer::getLineItems, JSON.writeValueAsString(settled))
                .set(InvPairedTransfer::getUpdatedBy, HostDocumentSupport.operator(caller))
                .eq(InvPairedTransfer::getId, pair.getId()));

        Caller sender = new Caller(caller.systemCode(), pair.getFromOwnerId(), caller.actor(), null,
                caller.traceId());
        events.publish(sender, "PairedTransferReceived", "OUTBOUND", pair.getOutboundNo(), 1, Map.of(
                "pairNo", pair.getPairNo(), "outboundNo", pair.getOutboundNo(),
                "lines", settled.stream().map(l -> Map.of("itemRef", l.itemRef(), "shipped", l.shipped(),
                        "received", l.received(), "diff", l.received() - l.shipped())).toList()));
        audit.record(caller, "paired.receive", "PAIRED", pair.getPairNo(), lines, settled, null);
        return pair.getPairNo();
    }

    @Override
    public boolean pairedInbound(String ownerId, String inboundNo) {
        return pairMapper.exists(Wrappers.<InvPairedTransfer>lambdaQuery()
                .eq(InvPairedTransfer::getToOwnerId, ownerId)
                .eq(InvPairedTransfer::getInboundNo, inboundNo));
    }

    @Override
    public List<View> list(String ownerId, String direction, String status) {
        boolean out = !"IN".equals(direction);
        return pairMapper.selectList(Wrappers.<InvPairedTransfer>lambdaQuery()
                        .eq(out, InvPairedTransfer::getFromOwnerId, ownerId)
                        .eq(!out, InvPairedTransfer::getToOwnerId, ownerId)
                        .eq(status != null, InvPairedTransfer::getStatus, status)
                        .orderByDesc(InvPairedTransfer::getId))
                .stream().map(this::view).toList();
    }

    @Override
    public View get(String ownerId, String pairNo) {
        InvPairedTransfer pair = pairMapper.selectOne(Wrappers.<InvPairedTransfer>lambdaQuery()
                .eq(InvPairedTransfer::getPairNo, pairNo)
                .and(w -> w.eq(InvPairedTransfer::getFromOwnerId, ownerId)
                        .or().eq(InvPairedTransfer::getToOwnerId, ownerId)));
        if (pair == null) {
            throw InventoryException.of(InventoryError.NOT_FOUND, "paired");
        }
        return view(pair);
    }

    /** 收货方账本里的对应物料：按宿主编号找，没有就照发货方的建一条（放行策略不随过去，按收货方自己的） */
    private String receiverItem(Caller receiver, InvItem from) {
        InvItem existing = itemMapper.selectOne(Wrappers.<InvItem>lambdaQuery()
                .eq(InvItem::getOwnerId, receiver.ownerId())
                .eq(InvItem::getExternalRef, from.getExternalRef()));
        if (existing != null) {
            return existing.getItemId();
        }
        return catalog.upsert(receiver, from.getExternalRef(), new ItemSpec(from.getName(), from.getBaseUom(),
                InvEnums.TrackingMode.QUANTITY, InvEnums.GatePolicy.NONE, null, from.getAttributes()));
    }

    private View view(InvPairedTransfer p) {
        InvOwner from = platform.owner(p.getFromOwnerId());
        InvOwner to = platform.owner(p.getToOwnerId());
        return new View(p.getPairNo(), p.getStatus(),
                new Side(from.getOwnerId(), from.getExternalType(), from.getExternalRef(), p.getOutboundNo(),
                        p.getFromLocationId()),
                new Side(to.getOwnerId(), to.getExternalType(), to.getExternalRef(), p.getInboundNo(),
                        p.getToLocationId()),
                p.getShippedAt(), p.getReceivedAt(), JSON.readValue(p.getLineItems(), LINES));
    }
}
