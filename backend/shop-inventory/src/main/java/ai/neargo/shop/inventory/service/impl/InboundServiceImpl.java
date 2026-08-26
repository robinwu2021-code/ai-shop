package ai.neargo.shop.inventory.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.inventory.entity.InvInboundLine;
import ai.neargo.shop.inventory.entity.InvInboundOrder;
import ai.neargo.shop.inventory.entity.InvItem;
import ai.neargo.shop.inventory.mapper.InventoryMappers.InboundLineMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.InboundOrderMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.ItemMapper;
import ai.neargo.shop.inventory.service.InboundService;
import ai.neargo.shop.inventory.service.StockPostingService;
import ai.neargo.shop.inventory.support.InvEnums;
import ai.neargo.shop.inventory.support.InvKeys;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 入库单实现。改余额一律经 {@link StockPostingService}，这里只管单据本身。 */
@Service
public class InboundServiceImpl implements InboundService {

    private final InboundOrderMapper orderMapper;
    private final InboundLineMapper lineMapper;
    private final ItemMapper itemMapper;
    private final StockPostingService posting;

    public InboundServiceImpl(InboundOrderMapper orderMapper, InboundLineMapper lineMapper,
                              ItemMapper itemMapper, StockPostingService posting) {
        this.orderMapper = orderMapper;
        this.lineMapper = lineMapper;
        this.itemMapper = itemMapper;
        this.posting = posting;
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public String createDraft(Draft draft) {
        validate(draft);
        String no = InvKeys.next(InvKeys.INBOUND);
        InvInboundOrder head = new InvInboundOrder();
        head.setInboundNo(no);
        head.setOwnerId(draft.ownerId());
        head.setLocationId(draft.locationId());
        head.setSourceType(draft.sourceType());
        head.setSourceRef(draft.sourceRef());
        head.setSupplierName(draft.supplierName());
        head.setStatus(InvEnums.DocStatus.DRAFT);
        head.setOccurredAt(draft.occurredAt() == null ? LocalDateTime.now() : draft.occurredAt());
        head.setRemark(draft.remark());
        applyTotals(head, draft.lines());
        orderMapper.insert(head);
        saveLines(no, draft);
        return no;
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public void updateDraft(String ownerId, String inboundNo, Draft draft) {
        InvInboundOrder head = mine(ownerId, inboundNo);
        // 已过账的单**只能整单作废重录** —— 改单据等于改历史，而历史正是这张表存在的理由
        if (!InvEnums.DocStatus.DRAFT.equals(head.getStatus())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        validate(draft);
        head.setLocationId(draft.locationId());
        head.setSupplierName(draft.supplierName());
        head.setOccurredAt(draft.occurredAt() == null ? head.getOccurredAt() : draft.occurredAt());
        head.setRemark(draft.remark());
        applyTotals(head, draft.lines());
        orderMapper.updateById(head);
        lineMapper.delete(Wrappers.<InvInboundLine>lambdaQuery()
                .eq(InvInboundLine::getInboundNo, inboundNo));
        saveLines(inboundNo, draft);
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public void post(String ownerId, String inboundNo, String operator) {
        InvInboundOrder head = mine(ownerId, inboundNo);
        // 状态早退 = 幂等：重复点「过账」不会加两遍库存
        if (InvEnums.DocStatus.POSTED.equals(head.getStatus())) {
            return;
        }
        if (!InvEnums.DocStatus.DRAFT.equals(head.getStatus())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        doPost(head, operator);
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public void voidOrder(String ownerId, String inboundNo, String operator) {
        InvInboundOrder head = mine(ownerId, inboundNo);
        if (InvEnums.DocStatus.VOIDED.equals(head.getStatus())) {
            return;
        }
        if (InvEnums.DocStatus.POSTED.equals(head.getStatus())) {
            posting.reverse(ownerId, inboundNo, InvEnums.DocKind.IN, operator);
        }
        head.setStatus(InvEnums.DocStatus.VOIDED);
        head.setVoidedAt(LocalDateTime.now());
        head.setUpdatedBy(operator);
        orderMapper.updateById(head);
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public String postDirectly(Draft draft, String operator) {
        String no = createDraft(draft);
        post(draft.ownerId(), no, operator);
        return no;
    }

    // ────────────────────────────────────────────────────────────────────

    private void doPost(InvInboundOrder head, String operator) {
        List<InvInboundLine> rows = lineMapper.selectList(Wrappers.<InvInboundLine>lambdaQuery()
                .eq(InvInboundLine::getInboundNo, head.getInboundNo())
                .orderByAsc(InvInboundLine::getLineNo));
        List<StockPostingService.Line> lines = new ArrayList<>();
        for (InvInboundLine r : rows) {
            lines.add(new StockPostingService.Line(
                    r.getLineNo(), r.getItemId(), head.getLocationId(), r.getQty(), r.getUnitCostMinor()));
        }
        posting.post(new StockPostingService.PostingDoc(head.getOwnerId(), InvEnums.DocKind.IN,
                head.getInboundNo(), head.getSourceType(), head.getOccurredAt(), operator, lines));

        head.setStatus(InvEnums.DocStatus.POSTED);
        head.setPostedAt(LocalDateTime.now());
        head.setUpdatedBy(operator);
        orderMapper.updateById(head);

        if (InvEnums.InboundSource.PURCHASE.equals(head.getSourceType())) {
            rows.forEach(r -> updateCost(head.getOwnerId(), r));
        }
    }

    /**
     * 采购过账把成本带进来。
     *
     * <p>只动 {@code cost_method = LATEST} 的物料 —— 商家显式设了手工价，
     * 就是不想让进货价盖掉它；盖掉的话他下次看毛利会以为自己算错了。
     */
    private void updateCost(String ownerId, InvInboundLine row) {
        if (row.getUnitCostMinor() == null) {
            return;
        }
        InvItem item = itemMapper.selectOne(Wrappers.<InvItem>lambdaQuery()
                .eq(InvItem::getOwnerId, ownerId).eq(InvItem::getItemId, row.getItemId()));
        if (item == null || !InvEnums.CostMethod.LATEST.equals(item.getCostMethod())) {
            return;
        }
        item.setDefaultCostMinor(row.getUnitCostMinor());
        itemMapper.updateById(item);
    }

    private void validate(Draft draft) {
        if (draft.lines() == null || draft.lines().isEmpty()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        for (Line l : draft.lines()) {
            if (l.qty() <= 0) {
                throw BizException.of(ErrorCode.BAD_REQUEST);
            }
            // 采购必须有单价：允许空的话 LATEST 会把成本读成 null，
            // 而毛利 =（售价 − null）在界面上会静默变成「等于售价」
            if (InvEnums.InboundSource.PURCHASE.equals(draft.sourceType()) && l.unitCostMinor() == null) {
                throw BizException.of(ErrorCode.BAD_REQUEST);
            }
        }
    }

    private void applyTotals(InvInboundOrder head, List<Line> lines) {
        int qty = 0;
        long cost = 0;
        for (Line l : lines) {
            qty += l.qty();
            cost += l.unitCostMinor() == null ? 0 : l.unitCostMinor() * l.qty();
        }
        head.setTotalQty(qty);
        head.setTotalCostMinor(cost);
    }

    private void saveLines(String inboundNo, Draft draft) {
        int lineNo = 1;
        for (Line l : draft.lines()) {
            InvInboundLine row = new InvInboundLine();
            row.setInboundNo(inboundNo);
            row.setLineNo(lineNo++);
            row.setOwnerId(draft.ownerId());
            row.setItemId(l.itemId());
            row.setQty(l.qty());
            row.setUom(l.uom());
            row.setUnitCostMinor(l.unitCostMinor());
            lineMapper.insert(row);
        }
    }

    private InvInboundOrder mine(String ownerId, String inboundNo) {
        InvInboundOrder head = orderMapper.selectOne(Wrappers.<InvInboundOrder>lambdaQuery()
                .eq(InvInboundOrder::getOwnerId, ownerId)
                .eq(InvInboundOrder::getInboundNo, inboundNo));
        if (head == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return head;
    }
}
