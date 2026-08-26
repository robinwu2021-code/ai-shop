package ai.neargo.shop.inventory.service.impl;

import ai.neargo.shop.inventory.entity.InvDailySnapshot;
import ai.neargo.shop.inventory.entity.InvLedger;
import ai.neargo.shop.inventory.mapper.InventoryMappers.DailySnapshotMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.LedgerMapper;
import ai.neargo.shop.inventory.service.InventorySnapshotService;
import ai.neargo.shop.inventory.support.InvEnums;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 日快照实现：重放当天的流水。 */
@Service
public class InventorySnapshotServiceImpl implements InventorySnapshotService {

    private final LedgerMapper ledgerMapper;
    private final DailySnapshotMapper snapshotMapper;

    public InventorySnapshotServiceImpl(LedgerMapper ledgerMapper, DailySnapshotMapper snapshotMapper) {
        this.ledgerMapper = ledgerMapper;
        this.snapshotMapper = snapshotMapper;
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public int buildFor(LocalDate date) {
        List<InvLedger> rows = ledgerMapper.selectList(Wrappers.<InvLedger>lambdaQuery()
                // 按**业务发生时间**归期：补录上周五的进货要算进上周
                .ge(InvLedger::getOccurredAt, date.atStartOfDay())
                .lt(InvLedger::getOccurredAt, date.plusDays(1).atStartOfDay())
                .orderByAsc(InvLedger::getId));

        Map<String, Acc> byKey = new LinkedHashMap<>();
        for (InvLedger e : rows) {
            Acc acc = byKey.computeIfAbsent(
                    e.getOwnerId() + "|" + e.getItemId() + "|" + e.getLocationId(),
                    k -> new Acc(e.getOwnerId(), e.getItemId(), e.getLocationId()));
            acc.apply(e);
        }

        // 先删后插：**重跑结果逐字相同**。用 REPLACE 的话自增主键会跳号，
        // 而跳号会让人以为丢了数据
        List<InvDailySnapshot> out = new ArrayList<>();
        for (Acc a : byKey.values()) {
            snapshotMapper.delete(Wrappers.<InvDailySnapshot>lambdaQuery()
                    .eq(InvDailySnapshot::getOwnerId, a.ownerId)
                    .eq(InvDailySnapshot::getStatDate, date)
                    .eq(InvDailySnapshot::getItemId, a.itemId)
                    .eq(InvDailySnapshot::getLocationId, a.locationId));
            out.add(a.toRow(date));
        }
        out.forEach(snapshotMapper::insert);
        return out.size();
    }

    /** 一个 (业主 × 物料 × 库位) 当天的累计。 */
    private static final class Acc {
        private final String ownerId;
        private final String itemId;
        private final String locationId;
        private int inbound;
        private int outbound;
        private int sold;
        private long soldCost;
        private int closing;
        private boolean seen;
        private int firstBefore;

        Acc(String ownerId, String itemId, String locationId) {
            this.ownerId = ownerId;
            this.itemId = itemId;
            this.locationId = locationId;
        }

        void apply(InvLedger e) {
            int d = e.getQtyDelta();
            if (!seen) {
                // 期初 = 当天第一行**之前**的结存。从第一行倒推，
                // 比「查前一天的快照」可靠：前一天可能根本没有变动，也就没有快照行
                firstBefore = e.getBalanceAfter() - d;
                seen = true;
            }
            if (d > 0) {
                inbound += d;
            } else {
                outbound += -d;
                if (InvEnums.OutboundPurpose.SALE.equals(e.getReasonCode())) {
                    sold += -d;
                    soldCost += (e.getUnitCostMinor() == null ? 0 : e.getUnitCostMinor()) * -d;
                }
            }
            closing = e.getBalanceAfter();
        }

        InvDailySnapshot toRow(LocalDate date) {
            InvDailySnapshot r = new InvDailySnapshot();
            r.setOwnerId(ownerId);
            r.setStatDate(date);
            r.setItemId(itemId);
            r.setLocationId(locationId);
            r.setOpeningQty(firstBefore);
            r.setInboundQty(inbound);
            r.setOutboundQty(outbound);
            r.setSoldQty(sold);
            r.setSoldCostMinor(soldCost);
            r.setClosingQty(closing);
            r.setCreatedBy("SYSTEM");
            return r;
        }
    }
}
