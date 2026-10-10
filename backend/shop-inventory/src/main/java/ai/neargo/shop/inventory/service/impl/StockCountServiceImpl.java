package ai.neargo.shop.inventory.service.impl;

import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.inventory.dto.InventoryVOs;
import ai.neargo.shop.inventory.entity.InvItem;
import ai.neargo.shop.inventory.entity.InvStockBalance;
import ai.neargo.shop.inventory.entity.InvStockCount;
import ai.neargo.shop.inventory.entity.InvStockCountLine;
import ai.neargo.shop.inventory.mapper.InventoryMappers.BalanceMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.ItemMapper;
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
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 盘点实现。 */
@ConditionalOnInventory
@Service
public class StockCountServiceImpl implements StockCountService {

    private final ItemMapper itemMapper;
    private final StockCountMapper countMapper;
    private final StockCountLineMapper lineMapper;
    private final BalanceMapper balanceMapper;
    private final InboundService inbound;
    private final OutboundService outbound;

    public StockCountServiceImpl(ItemMapper itemMapper,
                                 StockCountMapper countMapper, StockCountLineMapper lineMapper,
                                 BalanceMapper balanceMapper, InboundService inbound,
                                 OutboundService outbound) {
        this.itemMapper = itemMapper;
        this.countMapper = countMapper;
        this.lineMapper = lineMapper;
        this.balanceMapper = balanceMapper;
        this.inbound = inbound;
        this.outbound = outbound;
    }

    @Override
    public InventoryVOs.CountVO detail(String ownerId, String countNo) {
        InvStockCount head = countMapper.selectOne(Wrappers.<InvStockCount>lambdaQuery()
                .eq(InvStockCount::getOwnerId, ownerId).eq(InvStockCount::getCountNo, countNo));
        if (head == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        List<InvStockCountLine> rows = lineMapper.selectList(Wrappers.<InvStockCountLine>lambdaQuery()
                .eq(InvStockCountLine::getOwnerId, ownerId)
                .eq(InvStockCountLine::getCountNo, countNo)
                .orderByAsc(InvStockCountLine::getLineNo));

        Map<String, InvItem> items = new HashMap<>();
        for (InvItem it : itemMapper.selectList(Wrappers.<InvItem>lambdaQuery()
                .eq(InvItem::getOwnerId, ownerId))) {
            items.put(it.getItemId(), it);
        }

        List<InventoryVOs.CountLineVO> lines = new ArrayList<>();
        for (InvStockCountLine r : rows) {
            InvItem it = items.get(r.getItemId());
            lines.add(new InventoryVOs.CountLineVO(r.getItemId(),
                    it == null ? r.getItemId() : it.getName(),
                    it == null ? null : it.getSpecText(),
                    it == null ? null : it.getBaseUom(),
                    r.getBookQty() == null ? 0 : r.getBookQty(),
                    r.getCountedQty(), r.getDiffQty(), r.getReasonCode()));
        }
        return new InventoryVOs.CountVO(head.getCountNo(), head.getStatus(), head.getLocationId(),
                head.getStartedAt(), head.getOperator(), lines);
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public String open(String ownerId, String locationId, List<String> itemIds, String operator) {
        if (itemIds == null || itemIds.isEmpty()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        /*
         * **一个库位同时只许有一张盘点单开着。**
         *
         * 开单这一刻会把每件货的账面数快照下来（见下面那行 `setBookQty`），而开单之后
         * 照常卖。同时开两张，两张锁的是两个时刻的数：先开那张过账时，会把这中间
         * 卖掉的量当成盘亏再扣一遍 —— **账朝一个方向错，而且不报错**。
         *
         * **按库位判而不是按业主**：账面数是按 (货, 库位) 锁的，两个库位各盘各的不冲突。
         * 判据与首页那个 `openCountNo` 用的是同一条（`StockQueryServiceImpl`），
         * 两边对不上的话，界面说没有开着的单、接口却拒绝，没人查得出来。
         *
         * 抛的时候**带上那张单的单号**：不带的话商家只知道「有一张」，
         * 而首页只显示最近的那一张，先开的那张要翻单据才找得到。
         */
        InvStockCount opened = countMapper.selectOne(Wrappers.<InvStockCount>lambdaQuery()
                .eq(InvStockCount::getOwnerId, ownerId)
                .eq(locationId != null, InvStockCount::getLocationId, locationId)
                .eq(InvStockCount::getStatus, InvEnums.DocStatus.COUNTING)
                .orderByDesc(InvStockCount::getId)
                .last("LIMIT 1"));
        if (opened != null) {
            throw BizException.of(ErrorCode.COUNT_ALREADY_OPEN, opened.getCountNo());
        }

        String no = InvKeys.next(InvKeys.COUNT);
        InvStockCount head = new InvStockCount();
        head.setCountNo(no);
        head.setOwnerId(ownerId);
        head.setLocationId(locationId);
        head.setScope("SELECTED");
        head.setStatus(InvEnums.DocStatus.COUNTING);
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
    public void cancel(String ownerId, String countNo, String operator) {
        InvStockCount head = mine(ownerId, countNo);
        if (InvEnums.DocStatus.VOIDED.equals(head.getStatus())) {
            return;   // 幂等：弱网下重复提交是常事
        }
        /*
         * **只放行还在盘的那张。** 已过账的盘盈／盘亏单都已经落了账、余额已经改了，
         * 把它弄回去是「反向再盘一次」，不是作废 —— 两件事塞进同一个动作，
         * 商家点下去之后账会朝哪边走谁都说不清。
         *
         * 与调拨 `cancel` 拒绝已发出的是同一条道理，那儿写着为什么。
         */
        if (!InvEnums.DocStatus.COUNTING.equals(head.getStatus())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        /*
         * **行不删。** 那些行上有 `bookQty` —— 开单那一刻的账面快照，
         * 是这张单当时看到什么的唯一记录。删掉之后「为什么作废」就再也查不回来了，
         * 而作废本身正是要留痕的那一类动作。
         */
        head.setStatus(InvEnums.DocStatus.VOIDED);
        head.setUpdatedBy(operator);
        countMapper.updateById(head);
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
                    // 盘盈：货本来就在架上，没有来处
                    null, null, LocalDateTime.now(), null, gains), operator));
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
