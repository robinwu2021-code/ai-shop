package ai.neargo.shop.inventory.service.impl;

import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.inventory.entity.InvStockBalance;
import ai.neargo.shop.inventory.entity.InvStockCount;
import ai.neargo.shop.inventory.entity.InvStockCountLine;
import ai.neargo.shop.inventory.mapper.InventoryMappers.BalanceMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.StockCountLineMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.StockCountMapper;
import ai.neargo.shop.inventory.service.InboundService;
import ai.neargo.shop.inventory.service.OutboundService;
import ai.neargo.shop.inventory.service.StockCountService;
import ai.neargo.shop.inventory.support.InvEnums;
import ai.neargo.shop.inventory.support.InvKeys;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 盘点实现。 */
@ConditionalOnInventory
@Service
public class StockCountServiceImpl implements StockCountService {

    private final StockCountMapper countMapper;
    private final StockCountLineMapper lineMapper;
    private final BalanceMapper balanceMapper;
    private final InboundService inbound;
    private final OutboundService outbound;

    public StockCountServiceImpl(StockCountMapper countMapper, StockCountLineMapper lineMapper,
                                 BalanceMapper balanceMapper, InboundService inbound,
                                 OutboundService outbound) {
        this.countMapper = countMapper;
        this.lineMapper = lineMapper;
        this.balanceMapper = balanceMapper;
        this.inbound = inbound;
        this.outbound = outbound;
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public String open(String ownerId, String locationId, List<String> itemIds, String operator) {
        if (itemIds == null || itemIds.isEmpty()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        String no = InvKeys.next(InvKeys.COUNT);
        InvStockCount head = new InvStockCount();
        head.setCountNo(no);
        head.setOwnerId(ownerId);
        head.setLocationId(locationId);
        head.setScope("SELECTED");
        head.setStatus("COUNTING");
        head.setStartedAt(LocalDateTime.now());
        head.setOperator(operator);
        head.setCreatedBy(operator);
        countMapper.insert(head);

        int lineNo = 1;
        for (String itemId : itemIds) {
            InvStockCountLine row = new InvStockCountLine();
            row.setCountNo(no);
            row.setLineNo(lineNo++);
            row.setOwnerId(ownerId);
            row.setItemId(itemId);
            // ★ 账面数在这一刻快照，之后卖掉多少都不影响差异
            row.setBookQty(onHandOf(ownerId, itemId, locationId));
            row.setCreatedBy(operator);
            lineMapper.insert(row);
        }
        return no;
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public void fill(String ownerId, String countNo, List<Filled> lines) {
        InvStockCount head = mine(ownerId, countNo);
        if (InvEnums.DocStatus.POSTED.equals(head.getStatus())
                || InvEnums.DocStatus.VOIDED.equals(head.getStatus())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        Map<String, InvStockCountLine> byItem = rowsOf(countNo).stream()
                .collect(Collectors.toMap(InvStockCountLine::getItemId, Function.identity()));
        for (Filled f : lines) {
            InvStockCountLine row = byItem.get(f.itemId());
            if (row == null) {
                throw BizException.of(ErrorCode.NOT_FOUND);
            }
            // 实盘不许为负：库存不允许为负，那么「盘出 -3 件」这句话本身没有意义
            if (f.countedQty() < 0) {
                throw BizException.of(ErrorCode.BAD_REQUEST);
            }
            int diff = f.countedQty() - row.getBookQty();
            // 有差异就必须说清为什么：自由文本汇总不出「这个月报损了多少」
            if (diff != 0 && (f.reasonCode() == null || f.reasonCode().isBlank())) {
                throw BizException.of(ErrorCode.BAD_REQUEST);
            }
            row.setCountedQty(f.countedQty());
            row.setDiffQty(diff);
            row.setReasonCode(f.reasonCode());
            lineMapper.updateById(row);
        }
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public void post(String ownerId, String countNo, String operator) {
        InvStockCount head = mine(ownerId, countNo);
        if (InvEnums.DocStatus.POSTED.equals(head.getStatus())) {
            return;   // 幂等
        }
        List<InvStockCountLine> rows = rowsOf(countNo);
        List<InboundService.Line> gains = new ArrayList<>();
        List<OutboundService.Line> losses = new ArrayList<>();
        for (InvStockCountLine r : rows) {
            Integer diff = r.getDiffQty();
            if (diff == null || diff == 0) {
                continue;   // 变动 0 不生成任何东西
            }
            if (diff > 0) {
                gains.add(new InboundService.Line(r.getItemId(), diff, null, null));
            } else {
                losses.add(new OutboundService.Line(r.getItemId(), -diff, null));
            }
        }
        if (!gains.isEmpty()) {
            head.setGainInboundNo(inbound.postDirectly(new InboundService.Draft(
                    ownerId, head.getLocationId(), InvEnums.InboundSource.COUNT_GAIN, countNo,
                    null, LocalDateTime.now(), null, gains), operator));
        }
        if (!losses.isEmpty()) {
            head.setLossOutboundNo(outbound.postDirectly(new OutboundService.Draft(
                    ownerId, head.getLocationId(), InvEnums.OutboundPurpose.COUNT_LOSS, countNo,
                    null, InvEnums.Reason.CHECK, LocalDateTime.now(), null, losses), operator));
        }
        head.setStatus(InvEnums.DocStatus.POSTED);
        head.setPostedAt(LocalDateTime.now());
        head.setUpdatedBy(operator);
        countMapper.updateById(head);
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public void adjustOne(String ownerId, String locationId, String itemId, int countedQty,
                          String reasonCode, String operator) {
        String no = open(ownerId, locationId, List.of(itemId), operator);
        fill(ownerId, no, List.of(new Filled(itemId, countedQty, reasonCode)));
        post(ownerId, no, operator);
    }

    // ────────────────────────────────────────────────────────────────────

    private int onHandOf(String ownerId, String itemId, String locationId) {
        InvStockBalance b = balanceMapper.selectOne(Wrappers.<InvStockBalance>lambdaQuery()
                .eq(InvStockBalance::getOwnerId, ownerId)
                .eq(InvStockBalance::getItemId, itemId)
                .eq(InvStockBalance::getLocationId, locationId));
        return b == null ? 0 : b.getOnHand();
    }

    private List<InvStockCountLine> rowsOf(String countNo) {
        return lineMapper.selectList(Wrappers.<InvStockCountLine>lambdaQuery()
                .eq(InvStockCountLine::getCountNo, countNo)
                .orderByAsc(InvStockCountLine::getLineNo));
    }

    private InvStockCount mine(String ownerId, String countNo) {
        InvStockCount head = countMapper.selectOne(Wrappers.<InvStockCount>lambdaQuery()
                .eq(InvStockCount::getOwnerId, ownerId).eq(InvStockCount::getCountNo, countNo));
        if (head == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return head;
    }
}
