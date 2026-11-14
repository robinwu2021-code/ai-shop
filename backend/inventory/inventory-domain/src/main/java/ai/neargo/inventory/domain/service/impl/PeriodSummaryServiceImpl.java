package ai.neargo.inventory.domain.service.impl;

import ai.neargo.inventory.api.InventoryError;
import ai.neargo.inventory.api.InventoryException;
import ai.neargo.inventory.domain.config.ConditionalOnInventory;
import ai.neargo.inventory.domain.entity.InvLedger;
import ai.neargo.inventory.domain.mapper.InventoryMappers.LedgerMapper;
import ai.neargo.inventory.domain.service.PeriodSummaryService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@ConditionalOnInventory
@Service
public class PeriodSummaryServiceImpl implements PeriodSummaryService {

    /** 一次汇总最多扫这么多行流水：再多就该按仓或物料拆开查，而不是一次拖垮库。 */
    private static final int MAX_ROWS = 200_000;

    private final LedgerMapper ledgerMapper;

    public PeriodSummaryServiceImpl(LedgerMapper ledgerMapper) {
        this.ledgerMapper = ledgerMapper;
    }

    @Override
    public List<Row> summarize(String ownerId, LocalDateTime from, LocalDateTime to, String locationId,
                               String itemId) {
        if (from == null || to == null || !from.isBefore(to)) {
            throw InventoryException.of(InventoryError.BAD_REQUEST, "period");
        }
        // 期初 = 期间开始前全部变动之和。不取「最后一行的结存」：单据的发生时间可以回填，
        // 按 id 排的最后一行未必是按时间排的最后一行 —— 用和，恒等式在补录下也成立
        Map<String, Acc> acc = new TreeMap<>();
        for (InvLedger l : ledgerMapper.selectList(Wrappers.<InvLedger>lambdaQuery()
                .eq(InvLedger::getOwnerId, ownerId)
                .eq(locationId != null, InvLedger::getLocationId, locationId)
                .eq(itemId != null, InvLedger::getItemId, itemId)
                .lt(InvLedger::getOccurredAt, to)
                .orderByAsc(InvLedger::getId)
                .last("LIMIT " + (MAX_ROWS + 1)))) {
            Acc a = acc.computeIfAbsent(l.getLocationId() + "\n" + l.getItemId(),
                    k -> new Acc(l.getLocationId(), l.getItemId()));
            BigDecimal delta = l.getQtyDeltaQuantity();
            if (l.getOccurredAt().isBefore(from)) {
                a.opening = a.opening.add(delta);
                continue;
            }
            if (delta.signum() >= 0) {
                a.in.merge(l.getReasonCode(), delta, BigDecimal::add);
            } else {
                a.out.merge(l.getReasonCode(), delta.negate(), BigDecimal::add);
            }
            a.rows++;
            if (a.rows > MAX_ROWS) {
                throw InventoryException.of(InventoryError.BAD_REQUEST, "period.too_large");
            }
        }
        List<Row> rows = new ArrayList<>();
        for (Acc a : acc.values()) {
            BigDecimal in = a.in.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal out = a.out.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            rows.add(new Row(a.locationId, a.itemId, strip(a.opening), strip(a.in), strip(a.out),
                    strip(a.opening.add(in).subtract(out))));
        }
        return rows;
    }

    private static BigDecimal strip(BigDecimal v) {
        BigDecimal s = v.stripTrailingZeros();
        return s.scale() < 0 ? s.setScale(0) : s;
    }

    private static Map<String, BigDecimal> strip(Map<String, BigDecimal> m) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(k, strip(v)));
        return out;
    }

    private static final class Acc {
        final String locationId;
        final String itemId;
        BigDecimal opening = BigDecimal.ZERO;
        final Map<String, BigDecimal> in = new TreeMap<>();
        final Map<String, BigDecimal> out = new TreeMap<>();
        int rows;

        Acc(String locationId, String itemId) {
            this.locationId = locationId;
            this.itemId = itemId;
        }
    }
}
