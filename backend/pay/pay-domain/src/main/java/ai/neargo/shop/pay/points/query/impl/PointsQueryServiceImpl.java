package ai.neargo.shop.pay.points.query.impl;

import ai.neargo.shop.pay.points.query.PointsQueryService;
import ai.neargo.shop.pay.points.store.PointsOperationsStore;
import ai.neargo.shop.pay.points.store.PointsOperationsStore.Kind;

import org.springframework.stereotype.Service;

@Service
public class PointsQueryServiceImpl implements PointsQueryService {
    private final PointsOperationsStore store;

    public PointsQueryServiceImpl(PointsOperationsStore store) {
        this.store = store;
    }

    @Override
    public Page page(String type, Kind kind, long after, int limit) {
        var rows = store.page(type, kind, after, limit);
        long next = rows.isEmpty() ? after : ((Number) rows.getLast().get("id")).longValue();
        return new Page(rows, next, rows.size() == limit);
    }
}
