package ai.neargo.shop.pay.points.query.impl;

import ai.neargo.shop.pay.points.query.PointsOverviewService;
import ai.neargo.shop.pay.points.store.PointsOperationsStore;
import ai.neargo.shop.pay.points.store.PointsReadStore;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
@Transactional(value = "payTxManager", readOnly = true)
public class PointsOverviewServiceImpl implements PointsOverviewService {
    private final PointsReadStore read;
    private final PointsOperationsStore operations;

    public PointsOverviewServiceImpl(PointsReadStore read, PointsOperationsStore operations) {
        this.read = read;
        this.operations = operations;
    }

    @Override
    public ReceivablePage receivables(String type, String store, long after, int limit) {
        if (type == null
                || type.isBlank()
                || store == null
                || store.isBlank()
                || after < 0
                || limit < 1
                || limit > 200) {
            throw new IllegalArgumentException("POINTS_PAGE_INVALID");
        }
        var pointType =
                read.type(type)
                        .orElseThrow(() -> new IllegalArgumentException("POINTS_TYPE_UNKNOWN"));
        var rows =
                operations.receivables(type, store, after, limit).stream()
                        .map(
                                row -> {
                                    long original = number(row, "original_minor");
                                    long recovered = number(row, "recovered_minor");
                                    return new Receivable(
                                            number(row, "id"),
                                            (String) row.get("receivable_no"),
                                            (String) row.get("store_no"),
                                            (String) row.get("state"),
                                            original,
                                            recovered,
                                            number(row, "locked_minor"),
                                            Math.subtractExact(original, recovered),
                                            number(row, "version"));
                                })
                        .toList();
        return new ReceivablePage(
                pointType.typeCode(),
                pointType.currency(),
                pointType.scale(),
                rows,
                rows.isEmpty() ? after : rows.getLast().id(),
                rows.size() == limit);
    }

    private long number(Map<String, Object> row, String field) {
        return ((Number) row.get(field)).longValue();
    }

    @Override
    public Overview overview(String code, String market) {
        if ((code != null && code.isBlank())
                || (code == null && (market == null || market.isBlank()))) {
            throw new IllegalArgumentException("POINTS_TYPE_REQUIRED");
        }
        var type =
                (code == null ? read.activeType(market) : read.type(code))
                        .orElseThrow(() -> new IllegalArgumentException("POINTS_TYPE_UNKNOWN"));
        var pool = read.pool(type.typeCode());
        return new Overview(
                type.typeCode(),
                type.market(),
                type.currency(),
                type.scale(),
                type.rate().pointsUnit(),
                type.rate().minorUnit(),
                pool.u(),
                pool.c(),
                pool.d(),
                pool.b(),
                pool.reservedBMinor(),
                pool.lockedUMinor(),
                pool.lockedBMinor(),
                operations.outstandingReceivable(type.typeCode()));
    }
}
