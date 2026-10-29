package ai.neargo.inventory.domain.service.impl;

import ai.neargo.inventory.api.InventoryError;
import ai.neargo.inventory.api.InventoryException;
import ai.neargo.inventory.api.profile.BusinessProfile.InboundPurpose;
import ai.neargo.inventory.domain.config.ConditionalOnInventory;
import ai.neargo.inventory.domain.entity.InvDocumentSerial;
import ai.neargo.inventory.domain.entity.InvInboundLine;
import ai.neargo.inventory.domain.entity.InvInboundOrder;
import ai.neargo.inventory.domain.entity.InvItem;
import ai.neargo.inventory.domain.entity.InvSerial;
import ai.neargo.inventory.domain.entity.InvStockEpisode;
import ai.neargo.inventory.domain.mapper.InventoryMappers.DocumentSerialMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.InboundLineMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.InboundOrderMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.SerialMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.StockEpisodeMapper;
import ai.neargo.inventory.domain.service.AuditService;
import ai.neargo.inventory.domain.service.BusinessProfileService.Versioned;
import ai.neargo.inventory.domain.service.Caller;
import ai.neargo.inventory.domain.service.HostInboundService;
import ai.neargo.inventory.domain.service.StockPostingService;
import ai.neargo.inventory.domain.service.StockPostingService.PostingDoc;
import ai.neargo.inventory.domain.support.InvEnums;
import ai.neargo.inventory.domain.support.InvKeys;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ConditionalOnInventory
@Service
public class HostInboundServiceImpl implements HostInboundService {

    /** 只由撤回动作生成（M4），不能手建：手建的「纠正入库」等于凭空变出一件货。 */
    static final String CORRECTION = "CORRECTION";

    private final HostDocumentSupport support;
    private final InboundOrderMapper orderMapper;
    private final InboundLineMapper lineMapper;
    private final DocumentSerialMapper docSerialMapper;
    private final SerialMapper serialMapper;
    private final StockEpisodeMapper episodeMapper;
    private final StockPostingService posting;
    private final AuditService audit;

    public HostInboundServiceImpl(HostDocumentSupport support, InboundOrderMapper orderMapper,
                                  InboundLineMapper lineMapper, DocumentSerialMapper docSerialMapper,
                                  SerialMapper serialMapper, StockEpisodeMapper episodeMapper,
                                  StockPostingService posting, AuditService audit) {
        this.support = support;
        this.orderMapper = orderMapper;
        this.lineMapper = lineMapper;
        this.docSerialMapper = docSerialMapper;
        this.serialMapper = serialMapper;
        this.episodeMapper = episodeMapper;
        this.posting = posting;
        this.audit = audit;
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public String create(Caller caller, Draft draft) {
        Versioned profile = support.profile(caller.ownerId());
        InboundPurpose purpose = (profile.profile().inboundPurposes() == null
                ? List.<InboundPurpose>of() : profile.profile().inboundPurposes()).stream()
                .filter(p -> p.code().equals(draft.purpose()))
                .findFirst()
                .orElseThrow(() -> InventoryException.of(InventoryError.BAD_REQUEST, "purpose"));
        if (CORRECTION.equals(purpose.code())) {
            throw InventoryException.of(InventoryError.BAD_REQUEST, "purpose");
        }
        if (purpose.basisRequired() && (draft.basis() == null || draft.basis().isBlank())) {
            throw InventoryException.of(InventoryError.BAD_REQUEST, "basis");
        }
        List<String> attachments = draft.attachmentRefs() == null ? List.of() : draft.attachmentRefs();
        if (purpose.attachmentRequired() && attachments.isEmpty()) {
            throw InventoryException.of(InventoryError.BAD_REQUEST, "attachmentRefs");
        }
        support.requireActiveLocation(caller.ownerId(), draft.locationId());

        InvInboundOrder order = new InvInboundOrder();
        order.setInboundNo(InvKeys.next(InvKeys.INBOUND));
        order.setOwnerId(caller.ownerId());
        order.setLocationId(draft.locationId());
        order.setSourceType(purpose.code());
        order.setSourceRef(draft.sourceRef());
        order.setBasis(draft.basis());
        order.setAttachmentRefs(attachments.isEmpty() ? null : String.join(",", attachments));
        order.setProfileVersion(profile.version());
        order.setStatus(InvEnums.DocStatus.DRAFT);
        order.setTotalQtyQuantity(BigDecimal.ZERO);
        order.setOccurredAt(LocalDateTime.now());
        order.setOperator(HostDocumentSupport.operator(caller));
        order.setRemark(draft.remark());
        order.setCreatedBy(HostDocumentSupport.operator(caller));
        orderMapper.insert(order);
        addLines(caller, order, draft.lines() == null ? List.of() : draft.lines());
        audit.record(caller, "inbound.create", "INBOUND", order.getInboundNo(), null, draft, null);
        return order.getInboundNo();
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public void append(Caller caller, String inboundNo, List<Line> lines) {
        InvInboundOrder order = draft(caller, inboundNo);
        addLines(caller, order, lines == null ? List.of() : lines);
        audit.record(caller, "inbound.append", "INBOUND", inboundNo, null, lines, null);
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public void cancel(Caller caller, String inboundNo) {
        int changed = orderMapper.update(null, Wrappers.<InvInboundOrder>lambdaUpdate()
                .set(InvInboundOrder::getStatus, InvEnums.DocStatus.VOIDED)
                .set(InvInboundOrder::getVoidedAt, LocalDateTime.now())
                .eq(InvInboundOrder::getOwnerId, caller.ownerId())
                .eq(InvInboundOrder::getInboundNo, inboundNo)
                .eq(InvInboundOrder::getStatus, InvEnums.DocStatus.DRAFT));
        if (changed == 0) {
            throw InventoryException.of(InventoryError.CONFLICT, "status");
        }
        audit.record(caller, "inbound.cancel", "INBOUND", inboundNo, null, null, null);
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public void post(Caller caller, String inboundNo) {
        // 先抢状态：同一张单并发过账，只有一个能把它从草稿翻成已过账
        int changed = orderMapper.update(null, Wrappers.<InvInboundOrder>lambdaUpdate()
                .set(InvInboundOrder::getStatus, InvEnums.DocStatus.POSTED)
                .set(InvInboundOrder::getPostedAt, LocalDateTime.now())
                .eq(InvInboundOrder::getOwnerId, caller.ownerId())
                .eq(InvInboundOrder::getInboundNo, inboundNo)
                .eq(InvInboundOrder::getStatus, InvEnums.DocStatus.DRAFT));
        if (changed == 0) {
            throw InventoryException.of(InventoryError.CONFLICT, "status");
        }
        InvInboundOrder order = orderMapper.selectOne(Wrappers.<InvInboundOrder>lambdaQuery()
                .eq(InvInboundOrder::getOwnerId, caller.ownerId()).eq(InvInboundOrder::getInboundNo, inboundNo));
        List<InvInboundLine> lines = lineMapper.selectList(Wrappers.<InvInboundLine>lambdaQuery()
                .eq(InvInboundLine::getInboundNo, inboundNo).orderByAsc(InvInboundLine::getLineNo));
        List<InvDocumentSerial> serials = docSerials(caller.ownerId(), inboundNo);
        Map<String, InvItem> items = support.activeItems(caller.ownerId(),
                lines.stream().map(InvInboundLine::getItemId).distinct().toList());

        // ① 放行凭证：缺的一次列全（AC-04）
        List<String> missing = new ArrayList<>();
        for (InvInboundLine line : lines) {
            InvItem item = items.get(line.getItemId());
            if (!InvEnums.GatePolicy.REF_REQUIRED.equals(item.getGatePolicy())) {
                continue;
            }
            if (HostDocumentSupport.serial(item)) {
                serials.stream().filter(s -> s.getLineNo().equals(line.getLineNo()) && blank(s.getGateRef()))
                        .forEach(s -> missing.add(s.getSerialNo()));
            } else if (blank(line.getGateRef())) {
                missing.add(line.getItemId());
            }
        }
        if (!missing.isEmpty()) {
            throw InventoryException.of(InventoryError.GATE_REF_MISSING, missing.toArray());
        }

        // ② SN：按 SN 号升序加锁（固定锁序），问题一次列全
        serials.sort(Comparator.comparing(InvDocumentSerial::getSerialNo));
        Set<String> inDoc = new HashSet<>();
        serials.forEach(s -> inDoc.add(s.getSerialNo()));
        Map<String, InvSerial> existing = new HashMap<>();
        Set<String> conflicts = new LinkedHashSet<>();
        for (InvDocumentSerial s : serials) {
            InvSerial row = support.lockSerial(caller.ownerId(), s.getSerialNo());
            if (row != null) {
                existing.put(s.getSerialNo(), row);
                if (!row.getItemId().equals(s.getItemId())) {
                    conflicts.add(s.getSerialNo() + ":item_mismatch");
                } else if (row.getActiveEpisodeId() != null) {
                    conflicts.add(s.getSerialNo() + ":in_stock");
                }
            }
            if (!blank(s.getGateRef()) && gateReused(caller.ownerId(), s)) {
                conflicts.add(s.getSerialNo() + ":gate_reused");
            }
            if (s.getParentSerialNo() != null && !validParent(caller.ownerId(), order.getLocationId(), s, inDoc,
                    serials)) {
                conflicts.add(s.getSerialNo() + ":parent");
            }
        }
        if (!conflicts.isEmpty()) {
            throw InventoryException.of(InventoryError.SERIAL_CONFLICT, conflicts.toArray());
        }

        // ③ 数量：余额、流水、事件（同一事务）
        List<StockPostingService.Line> postingLines = new ArrayList<>();
        for (InvInboundLine line : lines) {
            postingLines.add(new StockPostingService.Line(line.getLineNo(), line.getItemId(), order.getLocationId(),
                    line.getQtyQuantity().intValueExact(), null));
        }
        posting.post(new PostingDoc(caller.ownerId(), InvEnums.DocKind.IN, inboundNo, order.getSourceType(),
                order.getOccurredAt(), HostDocumentSupport.operator(caller), postingLines));

        // ④ 逐件：开在库周期、占用 SN（双保险：条件更新 + 生成列唯一索引）
        Map<String, String> serialIds = new HashMap<>();
        for (InvDocumentSerial s : serials) {
            InvSerial row = existing.get(s.getSerialNo());
            if (row == null) {
                row = new InvSerial();
                row.setSerialId(InvKeys.next(InvKeys.SERIAL));
                row.setOwnerId(caller.ownerId());
                row.setItemId(s.getItemId());
                row.setSerialNo(s.getSerialNo());
                row.setCreatedBy(HostDocumentSupport.operator(caller));
                try {
                    serialMapper.insert(row);
                } catch (DuplicateKeyException race) {
                    throw InventoryException.of(InventoryError.SERIAL_CONFLICT, s.getSerialNo() + ":in_stock");
                }
            }
            openEpisode(caller, order, row, s);
            serialIds.put(s.getSerialNo(), row.getSerialId());
            s.setSerialId(row.getSerialId());
            docSerialMapper.updateById(s);
        }
        // ⑤ 组装关系：这一轮在仓期间，宝挂在哪个柜下
        for (InvDocumentSerial s : serials) {
            if (s.getParentSerialNo() == null) {
                continue;
            }
            String parentId = serialIds.containsKey(s.getParentSerialNo()) ? serialIds.get(s.getParentSerialNo())
                    : support.lockSerial(caller.ownerId(), s.getParentSerialNo()).getSerialId();
            serialMapper.update(null, Wrappers.<InvSerial>lambdaUpdate()
                    .set(InvSerial::getParentSerialId, parentId)
                    .eq(InvSerial::getOwnerId, caller.ownerId())
                    .eq(InvSerial::getSerialId, serialIds.get(s.getSerialNo())));
        }
        audit.record(caller, "inbound.post", "INBOUND", inboundNo, null, null, null);
    }

    private void openEpisode(Caller caller, InvInboundOrder order, InvSerial row, InvDocumentSerial s) {
        InvStockEpisode episode = new InvStockEpisode();
        episode.setEpisodeId(InvKeys.next(InvKeys.EPISODE));
        episode.setOwnerId(caller.ownerId());
        episode.setSerialId(row.getSerialId());
        episode.setItemId(row.getItemId());
        episode.setLocationId(order.getLocationId());
        episode.setStatus("OPEN");
        episode.setGateRef(s.getGateRef());
        episode.setOpenDocNo(order.getInboundNo());
        episode.setOpenedAt(LocalDateTime.now());
        episode.setCreatedBy(HostDocumentSupport.operator(caller));
        try {
            episodeMapper.insert(episode);
        } catch (DuplicateKeyException race) {
            throw InventoryException.of(InventoryError.SERIAL_CONFLICT, s.getSerialNo() + ":in_stock");
        }
        if (serialMapper.occupy(caller.ownerId(), row.getSerialId(), episode.getEpisodeId(),
                HostDocumentSupport.operator(caller)) == 0) {
            throw InventoryException.of(InventoryError.SERIAL_CONFLICT, s.getSerialNo() + ":in_stock");
        }
    }

    /** 一张放行凭证只放行一件：同一凭证号已经放行过别的 SN，就是凭证被挪用了。 */
    private boolean gateReused(String ownerId, InvDocumentSerial s) {
        return docSerialMapper.exists(Wrappers.<InvDocumentSerial>lambdaQuery()
                .eq(InvDocumentSerial::getOwnerId, ownerId)
                .eq(InvDocumentSerial::getDocKind, InvEnums.DocKind.IN)
                .eq(InvDocumentSerial::getGateRef, s.getGateRef())
                .isNotNull(InvDocumentSerial::getSerialId)
                .ne(InvDocumentSerial::getSerialNo, s.getSerialNo()));
    }

    /** 上级件：同单里的件（自己不再挂别人），或本库位已在仓、没有上级的件。 */
    private boolean validParent(String ownerId, String locationId, InvDocumentSerial s, Set<String> inDoc,
                                List<InvDocumentSerial> serials) {
        if (s.getParentSerialNo().equals(s.getSerialNo())) {
            return false;
        }
        if (inDoc.contains(s.getParentSerialNo())) {
            return serials.stream().noneMatch(o -> o.getSerialNo().equals(s.getParentSerialNo())
                    && o.getParentSerialNo() != null);
        }
        InvSerial parent = support.lockSerial(ownerId, s.getParentSerialNo());
        if (parent == null || parent.getActiveEpisodeId() == null || parent.getParentSerialId() != null) {
            return false;
        }
        InvStockEpisode episode = episodeMapper.selectOne(Wrappers.<InvStockEpisode>lambdaQuery()
                .eq(InvStockEpisode::getOwnerId, ownerId)
                .eq(InvStockEpisode::getEpisodeId, parent.getActiveEpisodeId()));
        return episode != null && locationId.equals(episode.getLocationId());
    }

    private void addLines(Caller caller, InvInboundOrder order, List<Line> lines) {
        if (lines.isEmpty()) {
            return;
        }
        Map<String, InvItem> items = support.activeItems(caller.ownerId(),
                lines.stream().map(Line::itemId).distinct().toList());
        List<InvDocumentSerial> already = docSerials(caller.ownerId(), order.getInboundNo());
        Set<String> seen = new HashSet<>();
        already.forEach(s -> seen.add(s.getSerialNo()));
        int total = already.size();
        Set<String> duplicates = new LinkedHashSet<>();
        int lineNo = nextLineNo(order.getInboundNo());
        BigDecimal added = BigDecimal.ZERO;
        for (Line line : lines) {
            InvItem item = items.get(line.itemId());
            List<Serial> serials = line.serials() == null ? List.of() : line.serials();
            int qty;
            if (HostDocumentSupport.serial(item)) {
                if (serials.isEmpty() || line.qty() != null && line.qty() != serials.size()) {
                    throw InventoryException.of(InventoryError.BAD_REQUEST, "serials:" + item.getItemId());
                }
                qty = serials.size();
                total += qty;
            } else {
                if (!serials.isEmpty() || line.qty() == null || line.qty() <= 0) {
                    throw InventoryException.of(InventoryError.BAD_REQUEST, "qty:" + item.getItemId());
                }
                qty = line.qty();
            }
            if (total > MAX_SERIALS) {
                throw InventoryException.of(InventoryError.BAD_REQUEST, "serials.limit", MAX_SERIALS);
            }
            InvInboundLine row = new InvInboundLine();
            row.setInboundNo(order.getInboundNo());
            row.setLineNo(lineNo);
            row.setOwnerId(caller.ownerId());
            row.setItemId(item.getItemId());
            row.setQtyQuantity(BigDecimal.valueOf(qty));
            row.setUom(item.getBaseUom());
            row.setGateRef(HostDocumentSupport.serial(item) ? null : line.gateRef());
            row.setCreatedBy(HostDocumentSupport.operator(caller));
            lineMapper.insert(row);
            for (Serial s : serials) {
                if (s == null || blank(s.serialNo()) || s.serialNo().length() > 64) {
                    throw InventoryException.of(InventoryError.BAD_REQUEST, "serialNo");
                }
                if (!seen.add(s.serialNo())) {
                    duplicates.add(s.serialNo() + ":duplicate");
                    continue;
                }
                InvDocumentSerial ds = new InvDocumentSerial();
                ds.setOwnerId(caller.ownerId());
                ds.setDocKind(InvEnums.DocKind.IN);
                ds.setDocNo(order.getInboundNo());
                ds.setLineNo(lineNo);
                ds.setItemId(item.getItemId());
                ds.setSerialNo(s.serialNo());
                ds.setGateRef(s.gateRef());
                ds.setParentSerialNo(s.parentSerialNo());
                ds.setCreatedBy(HostDocumentSupport.operator(caller));
                docSerialMapper.insert(ds);
            }
            added = added.add(BigDecimal.valueOf(qty));
            lineNo++;
        }
        if (!duplicates.isEmpty()) {
            throw InventoryException.of(InventoryError.SERIAL_CONFLICT, duplicates.toArray());
        }
        order.setTotalQtyQuantity(order.getTotalQtyQuantity().add(added));
        orderMapper.updateById(order);
    }

    private InvInboundOrder draft(Caller caller, String inboundNo) {
        InvInboundOrder order = orderMapper.selectOne(Wrappers.<InvInboundOrder>lambdaQuery()
                .eq(InvInboundOrder::getOwnerId, caller.ownerId())
                .eq(InvInboundOrder::getInboundNo, inboundNo)
                .last("FOR UPDATE"));
        if (order == null) {
            throw InventoryException.of(InventoryError.NOT_FOUND, "inbound");
        }
        if (!InvEnums.DocStatus.DRAFT.equals(order.getStatus())) {
            throw InventoryException.of(InventoryError.CONFLICT, "status");
        }
        return order;
    }

    private List<InvDocumentSerial> docSerials(String ownerId, String docNo) {
        return new ArrayList<>(docSerialMapper.selectList(Wrappers.<InvDocumentSerial>lambdaQuery()
                .eq(InvDocumentSerial::getOwnerId, ownerId)
                .eq(InvDocumentSerial::getDocKind, InvEnums.DocKind.IN)
                .eq(InvDocumentSerial::getDocNo, docNo)));
    }

    private int nextLineNo(String inboundNo) {
        return lineMapper.selectList(Wrappers.<InvInboundLine>lambdaQuery()
                        .eq(InvInboundLine::getInboundNo, inboundNo))
                .stream().mapToInt(InvInboundLine::getLineNo).max().orElse(0) + 1;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
