package ai.neargo.shop.pay.points.query.impl;

import ai.neargo.shop.pay.points.query.PointsQueryService;
import ai.neargo.shop.pay.points.store.PointsOperationsStore;
import ai.neargo.shop.pay.points.store.PointsOperationsStore.Kind;

import org.springframework.stereotype.Service;

@Service
public class PointsQueryServiceImpl implements PointsQueryService {
    @org.springframework.beans.factory.annotation.Autowired
    private ai.neargo.shop.pay.entitlement.service.PayRightsDocumentNumbers documentNumbers;
    private final PointsOperationsStore store;

    public PointsQueryServiceImpl(PointsOperationsStore store) {
        this.store = store;
    }

    @Override
    public Page page(String type, Kind kind, long after, int limit) {
        var rows = store.page(type, kind, after, limit).stream().map(row -> {
            var result=new java.util.HashMap<String,Object>(row);
            if (kind == Kind.VOUCHER) result.put("documentNo", documentNumbers.find(
                    ai.neargo.shop.numbering.DocumentType.POINTS_VOUCHER,"MAIN",(String)row.get("voucher_no")));
            return (java.util.Map<String,Object>)result;
        }).toList();
        long next = rows.isEmpty() ? after : ((Number) rows.getLast().get("id")).longValue();
        return new Page(rows, next, rows.size() == limit);
    }
}
