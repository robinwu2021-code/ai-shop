package ai.neargo.shop.inventory.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.inventory.entity.InvItem;
import ai.neargo.shop.inventory.entity.InvOutboundLine;
import ai.neargo.shop.inventory.entity.InvOutboundOrder;
import ai.neargo.shop.inventory.mapper.InventoryMappers.ItemMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.OutboundLineMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.OutboundOrderMapper;
import ai.neargo.shop.inventory.service.OutboundService;
import ai.neargo.shop.inventory.service.StockPostingService;
import ai.neargo.shop.inventory.support.InvEnums;
import ai.neargo.shop.inventory.support.InvKeys;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 出库单实现。 */
@Service
public class OutboundServiceImpl implements OutboundService {

    private final OutboundOrderMapper orderMapper;
    private final OutboundLineMapper lineMapper;
    private final ItemMapper itemMapper;
    private final StockPostingService posting;

    public OutboundServiceImpl(OutboundOrderMapper orderMapper, OutboundLineMapper lineMapper,
                               ItemMapper itemMapper, StockPostingService posting) {
        this.orderMapper = orderMapper;
        this.lineMapper = lineMapper;
        this.itemMapper = itemMapper;
        this.posting = posting;
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public String createDraft(Draft draft) {
        /*
         * **销售出库不接受手工创建**。
         *
         * 它只能由预留 commit 产生（走 postDirectly），因为一张 SALE 出库单意味着
         * 「有一笔已付款的订单」—— 手工建得出来的话，商家可以凭空造销量，
         * 而动销榜、毛利、进销存月报全部按它算。
         */
        if (InvEnums.OutboundPurpose.SALE.equals(draft.purpose())) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        return doCreate(draft);
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public void updateDraft(String ownerId, String outboundNo, Draft draft) {
        InvOutboundOrder head = mine(ownerId, outboundNo);
        if (!InvEnums.DocStatus.DRAFT.equals(head.getStatus())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        validate(draft);
        head.setLocationId(draft.locationId());
        head.setReasonCode(draft.reasonCode());
        head.setOccurredAt(draft.occurredAt() == null ? head.getOccurredAt() : draft.occurredAt());
        head.setRemark(draft.remark());
        head.setTotalQty(draft.lines().stream().mapToInt(Line::qty).sum());
        orderMapper.updateById(head);
        lineMapper.delete(Wrappers.<InvOutboundLine>lambdaQuery()
                .eq(InvOutboundLine::getOutboundNo, outboundNo));
        saveLines(outboundNo, draft);
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public void post(String ownerId, String outboundNo, String operator) {
        InvOutboundOrder head = mine(ownerId, outboundNo);
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
    public void voidOrder(String ownerId, String outboundNo, String operator) {
        InvOutboundOrder head = mine(ownerId, outboundNo);
        if (InvEnums.DocStatus.VOIDED.equals(head.getStatus())) {
            return;
        }
        if (InvEnums.DocStatus.POSTED.equals(head.getStatus())) {
            posting.reverse(ownerId, outboundNo, InvEnums.DocKind.OUT, operator);
        }
        head.setStatus(InvEnums.DocStatus.VOIDED);
        head.setVoidedAt(LocalDateTime.now());
        head.setUpdatedBy(operator);
        orderMapper.updateById(head);
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public String postDirectly(Draft draft, String operator) {
        String no = doCreate(draft);
        post(draft.ownerId(), no, operator);
        return no;
    }

    // ────────────────────────────────────────────────────────────────────

    private String doCreate(Draft draft) {
        validate(draft);
        String no = InvKeys.next(InvKeys.OUTBOUND);
        InvOutboundOrder head = new InvOutboundOrder();
        head.setOutboundNo(no);
        head.setOwnerId(draft.ownerId());
        head.setLocationId(draft.locationId());
        head.setPurpose(draft.purpose());
        head.setSourceRef(draft.sourceRef());
        head.setReservationId(draft.reservationId());
        head.setReasonCode(draft.reasonCode());
        head.setStatus(InvEnums.DocStatus.DRAFT);
        head.setOccurredAt(draft.occurredAt() == null ? LocalDateTime.now() : draft.occurredAt());
        head.setRemark(draft.remark());
        head.setTotalQty(draft.lines().stream().mapToInt(Line::qty).sum());
        head.setTotalCostMinor(0L);
        orderMapper.insert(head);
        saveLines(no, draft);
        return no;
    }

    private void doPost(InvOutboundOrder head, String operator) {
        List<InvOutboundLine> rows = lineMapper.selectList(Wrappers.<InvOutboundLine>lambdaQuery()
                .eq(InvOutboundLine::getOutboundNo, head.getOutboundNo())
                .orderByAsc(InvOutboundLine::getLineNo));
        List<StockPostingService.Line> lines = new ArrayList<>();
        long total = 0;
        for (InvOutboundLine r : rows) {
            /*
             * 成本在**过账那一刻**结转并快照到行上。
             *
             * 不落快照、每次按物料当前成本现算的话，历史出库单的成本会跟着现价变 ——
             * 表现是上个月的毛利今天再看又变了一个数，而没有任何操作记录能解释。
             */
            Long unitCost = costOf(head.getOwnerId(), r.getItemId());
            r.setUnitCostMinor(unitCost);
            lineMapper.updateById(r);
            total += unitCost == null ? 0 : unitCost * r.getQty();
            lines.add(new StockPostingService.Line(
                    r.getLineNo(), r.getItemId(), head.getLocationId(), r.getQty(), unitCost));
        }
        posting.post(new StockPostingService.PostingDoc(head.getOwnerId(), InvEnums.DocKind.OUT,
                head.getOutboundNo(), head.getPurpose(), head.getOccurredAt(), operator, lines));

        head.setStatus(InvEnums.DocStatus.POSTED);
        head.setPostedAt(LocalDateTime.now());
        head.setTotalCostMinor(total);
        head.setUpdatedBy(operator);
        orderMapper.updateById(head);
    }

    private Long costOf(String ownerId, String itemId) {
        InvItem item = itemMapper.selectOne(Wrappers.<InvItem>lambdaQuery()
                .eq(InvItem::getOwnerId, ownerId).eq(InvItem::getItemId, itemId));
        return item == null ? null : item.getDefaultCostMinor();
    }

    private void validate(Draft draft) {
        if (draft.lines() == null || draft.lines().isEmpty()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        if (draft.lines().stream().anyMatch(l -> l.qty() <= 0)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        // 报损必须说清为什么：自由文本汇总不出「这个月报损了多少」，
        // 而那正是商家盘完之后唯一想知道的数
        if (InvEnums.OutboundPurpose.SCRAP.equals(draft.purpose())
                && (draft.reasonCode() == null || draft.reasonCode().isBlank())) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }

    private void saveLines(String outboundNo, Draft draft) {
        int lineNo = 1;
        for (Line l : draft.lines()) {
            InvOutboundLine row = new InvOutboundLine();
            row.setOutboundNo(outboundNo);
            row.setLineNo(lineNo++);
            row.setOwnerId(draft.ownerId());
            row.setItemId(l.itemId());
            row.setQty(l.qty());
            row.setUom(l.uom());
            lineMapper.insert(row);
        }
    }

    private InvOutboundOrder mine(String ownerId, String outboundNo) {
        InvOutboundOrder head = orderMapper.selectOne(Wrappers.<InvOutboundOrder>lambdaQuery()
                .eq(InvOutboundOrder::getOwnerId, ownerId)
                .eq(InvOutboundOrder::getOutboundNo, outboundNo));
        if (head == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return head;
    }
}
