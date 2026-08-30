package ai.neargo.shop.inventory.service.impl;

import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.inventory.dto.InventoryVOs;
import ai.neargo.shop.inventory.entity.InvItem;
import ai.neargo.shop.inventory.entity.InvLocation;
import ai.neargo.shop.inventory.entity.InvOutboundLine;
import ai.neargo.shop.inventory.entity.InvTransferOrder;
import ai.neargo.shop.inventory.mapper.InventoryMappers.ItemMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.OutboundLineMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.TransferOrderMapper;
import ai.neargo.shop.inventory.service.InboundService;
import ai.neargo.shop.inventory.service.LocationService;
import ai.neargo.shop.inventory.service.OutboundService;
import ai.neargo.shop.inventory.service.TransferService;
import ai.neargo.shop.inventory.support.InvEnums;
import ai.neargo.shop.inventory.support.InvKeys;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 调拨实现。
 *
 * <p><b>行不单独存</b>：出库单与入库单本身就是行的载体。再存一份调拨行，
 * 就有了「以哪一份为准」的问题 —— 而两份对不上时没人知道该信谁。
 * 草稿期的行暂存在出库单草稿里。
 */
@ConditionalOnInventory
@Service
public class TransferServiceImpl implements TransferService {

    private final ItemMapper itemMapper;
    private final TransferOrderMapper transferMapper;
    private final OutboundLineMapper outboundLineMapper;
    private final OutboundService outbound;
    private final InboundService inbound;
    private final LocationService locations;

    public TransferServiceImpl(ItemMapper itemMapper,
                               TransferOrderMapper transferMapper, OutboundLineMapper outboundLineMapper,
                               OutboundService outbound, InboundService inbound, LocationService locations) {
        this.itemMapper = itemMapper;
        this.transferMapper = transferMapper;
        this.outboundLineMapper = outboundLineMapper;
        this.outbound = outbound;
        this.inbound = inbound;
        this.locations = locations;
    }

    @Override
    public InventoryVOs.TransferVO detail(String ownerId, String transferNo) {
        InvTransferOrder head = transferMapper.selectOne(Wrappers.<InvTransferOrder>lambdaQuery()
                .eq(InvTransferOrder::getOwnerId, ownerId)
                .eq(InvTransferOrder::getTransferNo, transferNo));
        if (head == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }

        Map<String, String> names = new HashMap<>();
        for (InvLocation l : locations.list(ownerId)) {
            names.put(l.getLocationId(), l.getName());
        }

        /*
         * 行取自**发出那张出库单**（调拨不另建行表，见类注释）。
         * 还没发出时 shippedOutboundNo 是 null —— 这时返回空行**不是错误**，
         * 界面上要说成「还没发出」，说成「空单」会让人以为单据坏了。
         */
        List<InventoryVOs.TransferLineVO> lines = new ArrayList<>();
        int total = 0;
        if (head.getShippedOutboundNo() != null) {
            List<InvOutboundLine> rows = outboundLineMapper.selectList(
                    Wrappers.<InvOutboundLine>lambdaQuery()
                            .eq(InvOutboundLine::getOwnerId, ownerId)
                            .eq(InvOutboundLine::getOutboundNo, head.getShippedOutboundNo())
                            .orderByAsc(InvOutboundLine::getLineNo));
            Map<String, InvItem> items = new HashMap<>();
            for (InvItem it : itemMapper.selectList(Wrappers.<InvItem>lambdaQuery()
                    .eq(InvItem::getOwnerId, ownerId))) {
                items.put(it.getItemId(), it);
            }
            for (InvOutboundLine r : rows) {
                InvItem it = items.get(r.getItemId());
                int qty = r.getQty() == null ? 0 : r.getQty();
                total += qty;
                lines.add(new InventoryVOs.TransferLineVO(r.getItemId(),
                        it == null ? r.getItemId() : it.getName(),
                        it == null ? null : it.getSpecText(), qty, r.getUom()));
            }
        }

        return new InventoryVOs.TransferVO(head.getTransferNo(), head.getStatus(),
                head.getFromLocationId(), names.get(head.getFromLocationId()),
                head.getToLocationId(), names.get(head.getToLocationId()),
                head.getShippedAt(), head.getReceivedAt(),
                head.getCarrierName(), head.getTrackingNo(), total, lines);
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public String create(String ownerId, String fromLocationId, String toLocationId,
                         List<Line> lines, String operator) {
        if (lines == null || lines.isEmpty() || fromLocationId.equals(toLocationId)) {
            // from == to 不是调拨，是什么都没发生
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        String no = InvKeys.next(InvKeys.TRANSFER);
        List<OutboundService.Line> outLines = new ArrayList<>();
        for (Line l : lines) {
            outLines.add(new OutboundService.Line(l.itemId(), l.qty(), null));
        }
        // 出库草稿先建着：它同时是「这次调拨要搬哪些货」的存放处
        String outboundNo = outbound.createDraft(new OutboundService.Draft(
                ownerId, fromLocationId, InvEnums.OutboundPurpose.TRANSFER_OUT, no,
                null, null, LocalDateTime.now(), null, outLines));

        InvTransferOrder head = new InvTransferOrder();
        head.setTransferNo(no);
        head.setOwnerId(ownerId);
        head.setFromLocationId(fromLocationId);
        head.setToLocationId(toLocationId);
        head.setStatus(InvEnums.TransferStatus.DRAFT);
        head.setShippedOutboundNo(outboundNo);
        head.setOperator(operator);
        head.setCreatedBy(operator);
        transferMapper.insert(head);
        return no;
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public void ship(String ownerId, String transferNo, String carrierNo, String carrierName,
                     String trackingNo, String operator) {
        InvTransferOrder head = mine(ownerId, transferNo);
        if (!InvEnums.TransferStatus.DRAFT.equals(head.getStatus())) {
            return;   // 幂等
        }
        // 出库单的库位在建草稿时写的是来源库位；过账后货从来源移出。
        // 「移到在途」由入库那一步的对侧完成 —— 见 receive() 的注释
        outbound.post(ownerId, head.getShippedOutboundNo(), operator);
        // 立刻在在途库位上入一笔：**出与入成对，两边合计不变**
        String transit = locations.transitLocation(ownerId);
        inbound.postDirectly(new InboundService.Draft(
                ownerId, transit, InvEnums.InboundSource.TRANSFER_IN, transferNo,
                // 调拨在途：来处是自己的另一个库位，不是供应商
                null, null, LocalDateTime.now(), null, linesOf(head.getShippedOutboundNo())), operator);

        head.setStatus(InvEnums.TransferStatus.SHIPPED);
        head.setShippedAt(LocalDateTime.now());
        /*
         * 承运方三列一起写。**名字由调用方带下来，这里不去查** ——
         * 它在主库的 ful_carrier 里，而进销存是独立数据源；
         * 让服务层去查等于把跨库耦合塞进来，而它只是为了显示一个名字。
         */
        head.setCarrierNo(blankToNull(carrierNo));
        head.setCarrierName(blankToNull(carrierName));
        head.setTrackingNo(blankToNull(trackingNo));
        head.setUpdatedBy(operator);
        transferMapper.updateById(head);
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public void receive(String ownerId, String transferNo, String operator) {
        InvTransferOrder head = mine(ownerId, transferNo);
        if (InvEnums.TransferStatus.RECEIVED.equals(head.getStatus())) {
            return;   // 幂等
        }
        if (!InvEnums.TransferStatus.SHIPPED.equals(head.getStatus())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        List<InboundService.Line> lines = linesOf(head.getShippedOutboundNo());
        String transit = locations.transitLocation(ownerId);
        // 从在途出、到目标入 —— 又是成对的一出一入，合计仍然不变
        List<OutboundService.Line> outLines = new ArrayList<>();
        for (InboundService.Line l : lines) {
            outLines.add(new OutboundService.Line(l.itemId(), l.qty(), l.uom()));
        }
        outbound.postDirectly(new OutboundService.Draft(
                ownerId, transit, InvEnums.OutboundPurpose.TRANSFER_OUT, transferNo,
                null, null, LocalDateTime.now(), null, outLines), operator);
        String inboundNo = inbound.postDirectly(new InboundService.Draft(
                ownerId, head.getToLocationId(), InvEnums.InboundSource.TRANSFER_IN, transferNo,
                null, null, LocalDateTime.now(), null, lines), operator);

        head.setStatus(InvEnums.TransferStatus.RECEIVED);
        head.setReceivedAt(LocalDateTime.now());
        head.setReceivedInboundNo(inboundNo);
        head.setUpdatedBy(operator);
        transferMapper.updateById(head);
    }

    private List<InboundService.Line> linesOf(String outboundNo) {
        List<InboundService.Line> out = new ArrayList<>();
        for (InvOutboundLine r : outboundLineMapper.selectList(Wrappers.<InvOutboundLine>lambdaQuery()
                .eq(InvOutboundLine::getOutboundNo, outboundNo)
                .orderByAsc(InvOutboundLine::getLineNo))) {
            out.add(new InboundService.Line(r.getItemId(), r.getQty(), r.getUom(), r.getUnitCostMinor()));
        }
        return out;
    }

    /** 空白当没填 —— 端上没选承运方时发的是空串，存进去会让「有没有记」这件事分成两种写法 */
    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v;
    }

    private InvTransferOrder mine(String ownerId, String transferNo) {
        InvTransferOrder head = transferMapper.selectOne(Wrappers.<InvTransferOrder>lambdaQuery()
                .eq(InvTransferOrder::getOwnerId, ownerId)
                .eq(InvTransferOrder::getTransferNo, transferNo));
        if (head == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return head;
    }
}
