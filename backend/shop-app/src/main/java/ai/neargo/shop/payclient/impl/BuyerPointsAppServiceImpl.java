package ai.neargo.shop.payclient.impl;

import ai.neargo.shop.pay.points.admin.PointsAdministrationService;
import ai.neargo.shop.pay.points.admin.PointsInspectionService;
import ai.neargo.shop.pay.points.model.Bucket;
import ai.neargo.shop.pay.points.model.HolderType;
import ai.neargo.shop.pay.points.model.PointType;
import ai.neargo.shop.payclient.BuyerPointsAppService;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class BuyerPointsAppServiceImpl implements BuyerPointsAppService {
    private final PointsAdministrationService types;
    private final PointsInspectionService inspection;

    public BuyerPointsAppServiceImpl(
            PointsAdministrationService types, PointsInspectionService inspection) {
        this.types = types;
        this.inspection = inspection;
    }

    @Override
    public List<Account> accounts(String userNo) {
        if (userNo == null || userNo.isBlank())
            throw new IllegalArgumentException("POINTS_BUYER_REQUIRED");
        List<Account> accounts = new ArrayList<>();
        for (var type : types.types()) {
            var view = inspection.account(type.typeCode(), HolderType.USER, userNo);
            if (!view.exists() && type.status() == PointType.Status.DRAFT) continue;
            var balance = view.snapshot();
            accounts.add(
                    new Account(
                            type.typeCode(),
                            type.market(),
                            type.currency(),
                            type.status().name(),
                            balance.points(Bucket.AVAILABLE),
                            balance.points(Bucket.PENDING),
                            balance.points(Bucket.HELD),
                            type.rate().pointsUnit(),
                            type.rate().minorUnit(),
                            type.deductRatioBp(),
                            type.idleDays(),
                            balance.frozen(),
                            balance.expireAt()));
        }
        return List.copyOf(accounts);
    }

    @Override
    public EntryPage records(String userNo, String typeCode, long after, int limit) {
        if (userNo == null || userNo.isBlank())
            throw new IllegalArgumentException("POINTS_BUYER_REQUIRED");
        var page = inspection.entries(typeCode, HolderType.USER, userNo, null, after, limit);
        return new EntryPage(
                page.rows().stream()
                        .map(
                                row ->
                                        new Entry(
                                                row.entryNo(),
                                                row.bucket().name(),
                                                row.deltaPoints(),
                                                row.afterPoints(),
                                                row.at()))
                        .toList(),
                page.next(),
                page.hasMore());
    }
}
