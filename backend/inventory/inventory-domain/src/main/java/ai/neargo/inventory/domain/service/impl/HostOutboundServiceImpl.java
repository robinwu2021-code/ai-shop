package ai.neargo.inventory.domain.service.impl;

import ai.neargo.inventory.api.InventoryError;
import ai.neargo.inventory.api.InventoryException;
import ai.neargo.inventory.api.profile.BusinessProfile;
import ai.neargo.inventory.api.profile.BusinessProfile.OutboundPurpose;
import ai.neargo.inventory.domain.config.ConditionalOnInventory;
import ai.neargo.inventory.domain.entity.InvDocumentSerial;
import ai.neargo.inventory.domain.entity.InvDocumentTarget;
import ai.neargo.inventory.domain.entity.InvItem;
import ai.neargo.inventory.domain.entity.InvOutboundLine;
import ai.neargo.inventory.domain.entity.InvOutboundOrder;
import ai.neargo.inventory.domain.entity.InvSerial;
import ai.neargo.inventory.domain.entity.InvStockEpisode;
import ai.neargo.inventory.domain.entity.InvStockHold;
import ai.neargo.inventory.domain.entity.InvTargetRef;
import ai.neargo.inventory.domain.mapper.InventoryMappers.DocumentSerialMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.DocumentTargetMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.OutboundLineMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.OutboundOrderMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.SerialMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.StockEpisodeMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.StockHoldMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.TargetRefMapper;
import ai.neargo.inventory.domain.service.AuditService;
import ai.neargo.inventory.domain.service.BusinessProfileService.Versioned;
import ai.neargo.inventory.domain.service.Caller;
import ai.neargo.inventory.domain.service.HostInboundService;
import ai.neargo.inventory.domain.service.HostOutboundService;
import ai.neargo.inventory.domain.service.StockPostingService;
import ai.neargo.inventory.domain.service.StockPostingService.PostingDoc;
import ai.neargo.inventory.domain.support.InvEnums;
import ai.neargo.inventory.domain.support.InvKeys;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ConditionalOnInventory
@Service
public class HostOutboundServiceImpl implements HostOutboundService {

    private final HostDocumentSupport support;
    private final OutboundOrderMapper orderMapper;
    private final OutboundLineMapper lineMapper;
    private final DocumentSerialMapper docSerialMapper;
    private final DocumentTargetMapper docTargetMapper;
    private final TargetRefMapper targetMapper;
    private final SerialMapper serialMapper;
    private final StockEpisodeMapper episodeMapper;
    private final StockHoldMapper holdMapper;
    private final StockPostingService posting;
    private final AuditService audit;

    public HostOutboundServiceImpl(HostDocumentSupport support, OutboundOrderMapper orderMapper,
                                   OutboundLineMapper lineMapper, DocumentSerialMapper docSerialMapper,
                                   DocumentTargetMapper docTargetMapper, TargetRefMapper targetMapper,
                                   SerialMapper serialMapper, StockEpisodeMapper episodeMapper,
                                   StockHoldMapper holdMapper, StockPostingService posting, AuditService audit) {
        this.support = support;
        this.orderMapper = orderMapper;
        this.lineMapper = lineMapper;
        this.docSerialMapper = docSerialMapper;
        this.docTargetMapper = docTargetMapper;
        this.targetMapper = targetMapper;
        this.serialMapper = serialMapper;
        this.episodeMapper = episodeMapper;
        this.holdMapper = holdMapper;
        this.posting = posting;
        this.audit = audit;
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public String create(Caller caller, Draft draft) {
        Versioned profile = support.profile(caller.ownerId());
        OutboundPurpose purpose = purpose(profile.profile(), draft.purpose());
        if (purpose.handover() == BusinessProfile.Handover.POOL) {
            // 交接出库要生成出库池记录，归 M4（出库池）；在那之前不放行，免得货出去了却没有交接凭证
            throw InventoryException.of(InventoryError.BAD_REQUEST, "purpose.handover");
        }
        support.requireActiveLocation(caller.ownerId(), draft.locationId());
        Map<String, InvTargetRef> targets = resolveTargets(caller, profile.profile(), purpose,
                draft.targets() == null ? Map.of() : draft.targets());

        InvOutboundOrder order = new InvOutboundOrder();
        order.setOutboundNo(InvKeys.next(InvKeys.OUTBOUND));
        order.setOwnerId(caller.ownerId());
        order.setLocationId(draft.locationId());
        order.setPurpose(purpose.code());
        order.setReasonCode(draft.reasonCode());
        order.setProfileVersion(profile.version());
        order.setStatus(InvEnums.DocStatus.DRAFT);
        order.setTotalQtyQuantity(BigDecimal.ZERO);
        order.setOccurredAt(LocalDateTime.now());
        order.setOperator(HostDocumentSupport.operator(caller));
        order.setRemark(draft.remark());
        order.setCreatedBy(HostDocumentSupport.operator(caller));
        orderMapper.insert(order);
        for (InvTargetRef t : targets.values()) {
            InvDocumentTarget row = new InvDocumentTarget();
            row.setOwnerId(caller.ownerId());
            row.setDocKind(InvEnums.DocKind.OUT);
            row.setDocNo(order.getOutboundNo());
            row.setTargetType(t.getObjectType());
            row.setTargetRef(t.getObjectRef());
            row.setNameSnapshot(t.getName());
            row.setCreatedBy(HostDocumentSupport.operator(caller));
            docTargetMapper.insert(row);
        }
        addLines(caller, order, draft.lines(), draft.serialNos());
        audit.record(caller, "outbound.create", "OUTBOUND", order.getOutboundNo(), null, draft, null);
        return order.getOutboundNo();
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public void append(Caller caller, String outboundNo, List<Line> lines, List<String> serialNos) {
        InvOutboundOrder order = orderMapper.selectOne(Wrappers.<InvOutboundOrder>lambdaQuery()
                .eq(InvOutboundOrder::getOwnerId, caller.ownerId())
                .eq(InvOutboundOrder::getOutboundNo, outboundNo).last("FOR UPDATE"));
        if (order == null) {
            throw InventoryException.of(InventoryError.NOT_FOUND, "outbound");
        }
        if (!InvEnums.DocStatus.DRAFT.equals(order.getStatus())) {
            throw InventoryException.of(InventoryError.CONFLICT, "status");
        }
        addLines(caller, order, lines, serialNos);
        audit.record(caller, "outbound.append", "OUTBOUND", outboundNo, null, serialNos, null);
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public void cancel(Caller caller, String outboundNo) {
        int changed = orderMapper.update(null, Wrappers.<InvOutboundOrder>lambdaUpdate()
                .set(InvOutboundOrder::getStatus, InvEnums.DocStatus.VOIDED)
                .set(InvOutboundOrder::getVoidedAt, LocalDateTime.now())
                .eq(InvOutboundOrder::getOwnerId, caller.ownerId())
                .eq(InvOutboundOrder::getOutboundNo, outboundNo)
                .eq(InvOutboundOrder::getStatus, InvEnums.DocStatus.DRAFT));
        if (changed == 0) {
            // 已过账的出库单不作废：货已经离开仓库，作废只会让账面「变回在仓」而实物不在
            throw InventoryException.of(InventoryError.CONFLICT, "status");
        }
        audit.record(caller, "outbound.cancel", "OUTBOUND", outboundNo, null, null, null);
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public void post(Caller caller, String outboundNo) {
        int changed = orderMapper.update(null, Wrappers.<InvOutboundOrder>lambdaUpdate()
                .set(InvOutboundOrder::getStatus, InvEnums.DocStatus.POSTED)
                .set(InvOutboundOrder::getPostedAt, LocalDateTime.now())
                .eq(InvOutboundOrder::getOwnerId, caller.ownerId())
                .eq(InvOutboundOrder::getOutboundNo, outboundNo)
                .eq(InvOutboundOrder::getStatus, InvEnums.DocStatus.DRAFT));
        if (changed == 0) {
            throw InventoryException.of(InventoryError.CONFLICT, "status");
        }
        InvOutboundOrder order = orderMapper.selectOne(Wrappers.<InvOutboundOrder>lambdaQuery()
                .eq(InvOutboundOrder::getOwnerId, caller.ownerId()).eq(InvOutboundOrder::getOutboundNo, outboundNo));

        // 目标在建单后被宿主停用：不再发给它
        for (InvDocumentTarget t : docTargetMapper.selectList(Wrappers.<InvDocumentTarget>lambdaQuery()
                .eq(InvDocumentTarget::getOwnerId, caller.ownerId())
                .eq(InvDocumentTarget::getDocKind, InvEnums.DocKind.OUT)
                .eq(InvDocumentTarget::getDocNo, outboundNo))) {
            InvTargetRef current = target(caller, t.getTargetType(), t.getTargetRef());
            if (current == null || !InvEnums.MasterStatus.ACTIVE.equals(current.getStatus())) {
                throw InventoryException.of(InventoryError.BAD_REQUEST, "target.disabled:" + t.getTargetType());
            }
        }

        List<InvDocumentSerial> serials = new ArrayList<>(docSerialMapper.selectList(
                Wrappers.<InvDocumentSerial>lambdaQuery()
                        .eq(InvDocumentSerial::getOwnerId, caller.ownerId())
                        .eq(InvDocumentSerial::getDocKind, InvEnums.DocKind.OUT)
                        .eq(InvDocumentSerial::getDocNo, outboundNo)));
        serials.sort(Comparator.comparing(InvDocumentSerial::getSerialNo));
        Set<String> conflicts = new LinkedHashSet<>();
        Map<String, InvSerial> rows = new HashMap<>();
        Map<String, InvStockEpisode> episodes = new HashMap<>();
        for (InvDocumentSerial s : serials) {
            InvSerial row = support.lockSerial(caller.ownerId(), s.getSerialNo());
            InvStockEpisode episode = row == null || row.getActiveEpisodeId() == null ? null
                    : episodeMapper.selectOne(Wrappers.<InvStockEpisode>lambdaQuery()
                            .eq(InvStockEpisode::getOwnerId, caller.ownerId())
                            .eq(InvStockEpisode::getEpisodeId, row.getActiveEpisodeId()));
            if (row == null || !row.getItemId().equals(s.getItemId())) {
                conflicts.add(s.getSerialNo() + ":item_mismatch");
            } else if (episode == null || !order.getLocationId().equals(episode.getLocationId())) {
                conflicts.add(s.getSerialNo() + ":not_in_stock");
            } else if (holdMapper.exists(Wrappers.<InvStockHold>lambdaQuery()
                    .eq(InvStockHold::getOwnerId, caller.ownerId())
                    .eq(InvStockHold::getSerialId, row.getSerialId())
                    .eq(InvStockHold::getStatus, InvEnums.HoldStatus.ACTIVE))) {
                conflicts.add(s.getSerialNo() + ":held");
            } else {
                rows.put(s.getSerialNo(), row);
                episodes.put(s.getSerialNo(), episode);
            }
        }
        if (!conflicts.isEmpty()) {
            throw InventoryException.of(InventoryError.SERIAL_CONFLICT, conflicts.toArray());
        }

        List<StockPostingService.Line> postingLines = new ArrayList<>();
        for (InvOutboundLine line : lineMapper.selectList(Wrappers.<InvOutboundLine>lambdaQuery()
                .eq(InvOutboundLine::getOutboundNo, outboundNo).orderByAsc(InvOutboundLine::getLineNo))) {
            postingLines.add(new StockPostingService.Line(line.getLineNo(), line.getItemId(), order.getLocationId(),
                    line.getQtyQuantity().intValueExact(), null));
        }
        posting.post(new PostingDoc(caller.ownerId(), InvEnums.DocKind.OUT, outboundNo, order.getPurpose(),
                order.getOccurredAt(), HostDocumentSupport.operator(caller), postingLines));

        for (InvDocumentSerial s : serials) {
            InvSerial row = rows.get(s.getSerialNo());
            InvStockEpisode episode = episodes.get(s.getSerialNo());
            episode.setStatus("CLOSED");
            episode.setCloseDocNo(outboundNo);
            episode.setClosedAt(LocalDateTime.now());
            episode.setUpdatedBy(HostDocumentSupport.operator(caller));
            episodeMapper.updateById(episode);
            if (serialMapper.vacate(caller.ownerId(), row.getSerialId(), episode.getEpisodeId(),
                    HostDocumentSupport.operator(caller)) == 0) {
                throw InventoryException.of(InventoryError.SERIAL_CONFLICT, s.getSerialNo() + ":not_in_stock");
            }
            s.setSerialId(row.getSerialId());
            docSerialMapper.updateById(s);
        }
        audit.record(caller, "outbound.post", "OUTBOUND", outboundNo, null, null, null);
    }

    private void addLines(Caller caller, InvOutboundOrder order, List<Line> lines, List<String> serialNos) {
        List<Line> quantityLines = lines == null ? List.of() : lines;
        List<String> scanned = serialNos == null ? List.of() : serialNos;
        Map<String, InvItem> items = support.activeItems(caller.ownerId(),
                quantityLines.stream().map(Line::itemId).distinct().toList());
        List<InvDocumentSerial> already = docSerialMapper.selectList(Wrappers.<InvDocumentSerial>lambdaQuery()
                .eq(InvDocumentSerial::getOwnerId, caller.ownerId())
                .eq(InvDocumentSerial::getDocKind, InvEnums.DocKind.OUT)
                .eq(InvDocumentSerial::getDocNo, order.getOutboundNo()));
        Set<String> onDoc = new LinkedHashSet<>();
        already.forEach(s -> onDoc.add(s.getSerialNo()));

        // 扫上级件即带出它当前挂着的下级件（柜 → 柜内宝）；展开结果逐件记在单据上
        Set<String> conflicts = new LinkedHashSet<>();
        Map<String, List<Expanded>> byItem = new LinkedHashMap<>();
        for (String no : scanned) {
            InvSerial serial = no == null ? null : serialMapper.selectOne(Wrappers.<InvSerial>lambdaQuery()
                    .eq(InvSerial::getOwnerId, caller.ownerId()).eq(InvSerial::getSerialNo, no));
            if (serial == null) {
                conflicts.add(no + ":unknown");
                continue;
            }
            add(byItem, onDoc, conflicts, new Expanded(serial.getItemId(), serial.getSerialNo(), null));
            for (InvSerial child : serialMapper.selectList(Wrappers.<InvSerial>lambdaQuery()
                    .eq(InvSerial::getOwnerId, caller.ownerId())
                    .eq(InvSerial::getParentSerialId, serial.getSerialId())
                    .isNotNull(InvSerial::getActiveEpisodeId)
                    .orderByAsc(InvSerial::getSerialNo))) {
                add(byItem, onDoc, conflicts, new Expanded(child.getItemId(), child.getSerialNo(), serial.getSerialNo()));
            }
        }
        if (!conflicts.isEmpty()) {
            throw InventoryException.of(InventoryError.SERIAL_CONFLICT, conflicts.toArray());
        }
        if (onDoc.size() > HostInboundService.MAX_SERIALS) {
            throw InventoryException.of(InventoryError.BAD_REQUEST, "serials.limit", HostInboundService.MAX_SERIALS);
        }
        Map<String, InvItem> serialItems = support.activeItems(caller.ownerId(), byItem.keySet());

        int lineNo = lineMapper.selectList(Wrappers.<InvOutboundLine>lambdaQuery()
                        .eq(InvOutboundLine::getOutboundNo, order.getOutboundNo()))
                .stream().mapToInt(InvOutboundLine::getLineNo).max().orElse(0) + 1;
        BigDecimal added = BigDecimal.ZERO;
        for (Line line : quantityLines) {
            InvItem item = items.get(line.itemId());
            if (HostDocumentSupport.serial(item) || line.qty() == null || line.qty() <= 0) {
                // 逐件物料只能按 SN 出：按数量出就不知道出去的是哪几台
                throw InventoryException.of(InventoryError.BAD_REQUEST, "qty:" + item.getItemId());
            }
            insertLine(caller, order, lineNo++, item, line.qty());
            added = added.add(BigDecimal.valueOf(line.qty()));
        }
        for (Map.Entry<String, List<Expanded>> e : byItem.entrySet()) {
            InvItem item = serialItems.get(e.getKey());
            if (!HostDocumentSupport.serial(item)) {
                throw InventoryException.of(InventoryError.BAD_REQUEST, "serials:" + item.getItemId());
            }
            int no = lineNo++;
            insertLine(caller, order, no, item, e.getValue().size());
            for (Expanded x : e.getValue()) {
                InvDocumentSerial ds = new InvDocumentSerial();
                ds.setOwnerId(caller.ownerId());
                ds.setDocKind(InvEnums.DocKind.OUT);
                ds.setDocNo(order.getOutboundNo());
                ds.setLineNo(no);
                ds.setItemId(x.itemId());
                ds.setSerialNo(x.serialNo());
                ds.setParentSerialNo(x.parentSerialNo());
                ds.setCreatedBy(HostDocumentSupport.operator(caller));
                docSerialMapper.insert(ds);
            }
            added = added.add(BigDecimal.valueOf(e.getValue().size()));
        }
        order.setTotalQtyQuantity(order.getTotalQtyQuantity().add(added));
        orderMapper.updateById(order);
    }

    private static void add(Map<String, List<Expanded>> byItem, Set<String> onDoc, Set<String> conflicts, Expanded x) {
        if (!onDoc.add(x.serialNo())) {
            conflicts.add(x.serialNo() + ":duplicate");
            return;
        }
        byItem.computeIfAbsent(x.itemId(), k -> new ArrayList<>()).add(x);
    }

    private void insertLine(Caller caller, InvOutboundOrder order, int lineNo, InvItem item, int qty) {
        InvOutboundLine row = new InvOutboundLine();
        row.setOutboundNo(order.getOutboundNo());
        row.setLineNo(lineNo);
        row.setOwnerId(caller.ownerId());
        row.setItemId(item.getItemId());
        row.setQtyQuantity(BigDecimal.valueOf(qty));
        row.setUom(item.getBaseUom());
        row.setCreatedBy(HostDocumentSupport.operator(caller));
        lineMapper.insert(row);
    }

    private static OutboundPurpose purpose(BusinessProfile profile, String code) {
        return (profile.outboundPurposes() == null ? List.<OutboundPurpose>of() : profile.outboundPurposes())
                .stream().filter(p -> p.code().equals(code)).findFirst()
                .orElseThrow(() -> InventoryException.of(InventoryError.BAD_REQUEST, "purpose"));
    }

    /** 按档案的收货方规则：必填的都要给、只许给登记过的类型、可选目标须挂在所选的上级目标下。 */
    private Map<String, InvTargetRef> resolveTargets(Caller caller, BusinessProfile profile, OutboundPurpose purpose,
                                                     Map<String, String> given) {
        List<String> required = purpose.requiredTargets() == null ? List.of() : purpose.requiredTargets();
        List<String> optional = purpose.optionalTargets() == null ? List.of() : purpose.optionalTargets();
        for (String type : required) {
            if (!given.containsKey(type)) {
                throw InventoryException.of(InventoryError.BAD_REQUEST, "target.required:" + type);
            }
        }
        Map<String, InvTargetRef> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : given.entrySet()) {
            if (!required.contains(e.getKey()) && !optional.contains(e.getKey())) {
                throw InventoryException.of(InventoryError.BAD_REQUEST, "target.type:" + e.getKey());
            }
            InvTargetRef t = target(caller, e.getKey(), e.getValue());
            if (t == null || !InvEnums.MasterStatus.ACTIVE.equals(t.getStatus())) {
                throw InventoryException.of(InventoryError.BAD_REQUEST, "target:" + e.getKey());
            }
            resolved.put(e.getKey(), t);
        }
        for (InvTargetRef t : resolved.values()) {
            if (optional.contains(t.getObjectType()) && t.getParentType() != null) {
                InvTargetRef parent = resolved.get(t.getParentType());
                if (parent == null || !parent.getObjectRef().equals(t.getParentRef())) {
                    // 例：站点必须属于所选商家
                    throw InventoryException.of(InventoryError.BAD_REQUEST, "target.parent:" + t.getObjectType());
                }
            }
        }
        return resolved;
    }

    private InvTargetRef target(Caller caller, String type, String ref) {
        return targetMapper.selectOne(Wrappers.<InvTargetRef>lambdaQuery()
                .eq(InvTargetRef::getOwnerId, caller.ownerId())
                .eq(InvTargetRef::getSystemCode, caller.systemCode())
                .eq(InvTargetRef::getObjectType, type)
                .eq(InvTargetRef::getObjectRef, ref));
    }

    private record Expanded(String itemId, String serialNo, String parentSerialNo) {
    }
}
