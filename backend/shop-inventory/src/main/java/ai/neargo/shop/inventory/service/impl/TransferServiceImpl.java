package ai.neargo.shop.inventory.service.impl;

import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.inventory.entity.InvOutboundLine;
import ai.neargo.shop.inventory.entity.InvTransferOrder;
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
import java.util.List;

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

    private final TransferOrderMapper transferMapper;
    private final OutboundLineMapper outboundLineMapper;
    private final OutboundService outbound;
    private final InboundService inbound;
    private final LocationService locations;

    public TransferServiceImpl(TransferOrderMapper transferMapper, OutboundLineMapper outboundLineMapper,
                               OutboundService outbound, InboundService inbound, LocationService locations) {
        this.transferMapper = transferMapper;
        this.outboundLineMapper = outboundLineMapper;
        this.outbound = outbound;
        this.inbound = inbound;
        this.locations = locations;
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
    public void ship(String ownerId, String transferNo, String operator) {
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
                null, LocalDateTime.now(), null, linesOf(head.getShippedOutboundNo())), operator);

        head.setStatus(InvEnums.TransferStatus.SHIPPED);
        head.setShippedAt(LocalDateTime.now());
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
                null, LocalDateTime.now(), null, lines), operator);

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
