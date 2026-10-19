package ai.neargo.shop.inventory.service.impl;

import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.shop.inventory.entity.InvOutboundLine;
import ai.neargo.shop.inventory.entity.InvOutboundOrder;
import ai.neargo.shop.inventory.entity.InvReservation;
import ai.neargo.shop.inventory.entity.InvReservationLine;
import ai.neargo.shop.inventory.mapper.InventoryMappers.OutboundLineMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.OutboundOrderMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.ReservationLineMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.ReservationMapper;
import ai.neargo.shop.inventory.support.InvEnums;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Caller holds an inventory transaction; locks serialize all restore purposes against original sale facts. */
@Component
@ConditionalOnInventory
class OriginalReservationSource {
    private final ReservationMapper reservations;
    private final ReservationLineMapper lines;
    private final OutboundOrderMapper outbound;
    private final OutboundLineMapper outboundLines;

    OriginalReservationSource(ReservationMapper reservations, ReservationLineMapper lines,
                              OutboundOrderMapper outbound, OutboundLineMapper outboundLines) {
        this.reservations = reservations;
        this.lines = lines;
        this.outbound = outbound;
        this.outboundLines = outboundLines;
    }

    List<Source> lock(String originalRef) {
        List<InvReservation> heads = reservations.selectList(Wrappers.<InvReservation>lambdaQuery()
                .eq(InvReservation::getExternalRef, originalRef).orderByAsc(InvReservation::getOwnerId)
                .last("FOR UPDATE"));
        if (heads.isEmpty()) {
            throw invalid();
        }
        List<Source> result = new ArrayList<>();
        for (InvReservation head : heads) {
            result.addAll(prove(head, originalRef));
        }
        return result;
    }

    private List<Source> prove(InvReservation head, String originalRef) {
        if (Integer.valueOf(2).equals(head.getLifecycleVersion())) {
            return proveFulfillmentIssuedLines(head, originalRef);
        }
        if (!"COMMITTED".equals(head.getStatus()) || head.getOutboundNo() == null) {
            throw invalid();
        }
        InvOutboundOrder actual = outbound.selectOne(Wrappers.<InvOutboundOrder>lambdaQuery()
                .eq(InvOutboundOrder::getOutboundNo, head.getOutboundNo()).last("FOR UPDATE"));
        if (actual == null || !"POSTED".equals(actual.getStatus()) || !"SALE".equals(actual.getPurpose())
                || !Objects.equals(actual.getOwnerId(), head.getOwnerId())
                || !Objects.equals(actual.getSourceRef(), originalRef)
                || !Objects.equals(actual.getReservationId(), head.getReservationId())) {
            throw invalid();
        }
        List<InvReservationLine> original = lines.selectList(Wrappers.<InvReservationLine>lambdaQuery()
                .eq(InvReservationLine::getReservationId, head.getReservationId())
                .orderByAsc(InvReservationLine::getId));
        Map<String, Long> costs = verifyLines(actual, original);
        return original.stream().map(line -> new Source(line, costs.get(line.getItemId()), line.getQty())).toList();
    }

    private List<Source> proveFulfillmentIssuedLines(InvReservation head, String originalRef) {
        if (!List.of("COMMITTED", InvEnums.ReservationStatus.PARTIALLY_COMMITTED, "PARTIAL_RELEASED").contains(head.getStatus())) throw invalid();
        List<Source> result = new ArrayList<>();
        for (InvReservationLine line : lines.selectList(Wrappers.<InvReservationLine>lambdaQuery()
                .eq(InvReservationLine::getReservationId, head.getReservationId()).orderByAsc(InvReservationLine::getId))) {
            if (line.getIssuedQty() == null || line.getIssuedQty() == 0) continue;
            if (line.getIssuedQty() + (line.getReleasedQty() == null ? 0 : line.getReleasedQty()) > line.getQty()
                    || line.getOutboundNo() == null) throw invalid();
            InvOutboundOrder actual = outbound.selectOne(Wrappers.<InvOutboundOrder>lambdaQuery()
                    .eq(InvOutboundOrder::getOutboundNo, line.getOutboundNo()).last("FOR UPDATE"));
            if (actual == null || !"POSTED".equals(actual.getStatus()) || !"SALE".equals(actual.getPurpose())
                    || !Objects.equals(actual.getOwnerId(), head.getOwnerId())
                    || !Objects.equals(actual.getSourceRef(), originalRef)
                    || !Objects.equals(actual.getReservationId(), head.getReservationId())
                    || !Objects.equals(actual.getLocationId(), line.getLocationId())) throw invalid();
            Map<String, Integer> expected = Map.of(line.getItemId(), line.getIssuedQty());
            Map<String, Integer> posted = new HashMap<>();
            Map<String, Long> costs = new HashMap<>();
            for (InvOutboundLine outLine : outboundLines.selectList(Wrappers.<InvOutboundLine>lambdaQuery()
                    .eq(InvOutboundLine::getOutboundNo, actual.getOutboundNo()))) {
                if (!Objects.equals(outLine.getOwnerId(), head.getOwnerId()) || outLine.getQty() == null
                        || outLine.getQty() <= 0) throw invalid();
                posted.merge(outLine.getItemId(), outLine.getQty(), Math::addExact);
                costs.put(outLine.getItemId(), outLine.getUnitCostMinor());
            }
            if (!expected.equals(posted) || !costs.containsKey(line.getItemId())) throw invalid();
            result.add(new Source(line, costs.get(line.getItemId()), line.getIssuedQty()));
        }
        if (result.isEmpty()) throw invalid();
        return result;
    }

    private Map<String, Long> verifyLines(InvOutboundOrder actual, List<InvReservationLine> original) {
        Map<String, Integer> expected = new HashMap<>();
        for (InvReservationLine line : original) {
            if (!Objects.equals(line.getOwnerId(), actual.getOwnerId()) || line.getQty() == null
                    || line.getQty() <= 0 || !Objects.equals(line.getLocationId(), actual.getLocationId())) {
                throw invalid();
            }
            expected.merge(line.getItemId(), line.getQty(), Math::addExact);
        }
        Map<String, Integer> posted = new HashMap<>();
        Map<String, Long> costs = new HashMap<>();
        for (InvOutboundLine line : outboundLines.selectList(Wrappers.<InvOutboundLine>lambdaQuery()
                .eq(InvOutboundLine::getOutboundNo, actual.getOutboundNo()))) {
            if (!Objects.equals(line.getOwnerId(), actual.getOwnerId()) || line.getQty() == null
                    || line.getQty() <= 0 || (costs.containsKey(line.getItemId())
                    && !Objects.equals(costs.get(line.getItemId()), line.getUnitCostMinor()))) {
                throw invalid();
            }
            posted.merge(line.getItemId(), line.getQty(), Math::addExact);
            costs.put(line.getItemId(), line.getUnitCostMinor());
        }
        if (expected.isEmpty() || !expected.equals(posted)) {
            throw invalid();
        }
        return costs;
    }

    private IllegalStateException invalid() {
        return new IllegalStateException("STOCK_ORIGINAL_POSTING_UNCONFIRMED");
    }

    record Source(InvReservationLine line, Long unitCostMinor, int restorableQty) { }
}
