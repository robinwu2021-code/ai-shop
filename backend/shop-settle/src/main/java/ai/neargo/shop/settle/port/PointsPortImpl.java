package ai.neargo.shop.settle.port;

import ai.neargo.shop.settle.PointsService;
import ai.neargo.shop.spi.settle.PointsPort;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@link PointsPort} 的实现：把 trade 要的「一次抵扣 + 各商家分摊」翻译成积分域的动作。
 *
 * <p><b>分摊在这一层算，trade 只落库</b> —— 与 {@code CouponPortImpl} 同一分工。
 */
@Component
public class PointsPortImpl implements PointsPort {

    private final PointsService pointsService;

    public PointsPortImpl(PointsService pointsService) {
        this.pointsService = pointsService;
    }

    @Override
    public Deduction deduct(String userNo, long wantPoints, List<Target> targets) {
        if (targets == null || targets.isEmpty()) {
            return Deduction.none();
        }
        PointsService.DeductResult r = pointsService.deductOnPlace(userNo, wantPoints,
                targets.stream()
                        .map(t -> new PointsService.DeductTarget(
                                t.merchantNo(), t.payableMinor(), t.subOrderNo()))
                        .toList());
        if (r.points() <= 0) {
            return Deduction.none();
        }
        return new Deduction(r.points(), r.amountMinor(), r.shares().stream()
                .map(x -> new Share(x.subOrderNo(), x.merchantNo(), x.points(), x.amountMinor()))
                .toList());
    }

    @Override
    public void reverse(String subOrderNo, String reason) {
        pointsService.reverse(subOrderNo, reason);
    }

    @Override
    public GrantResult grant(String userNo, String merchantNo,
                             java.util.List<EarnLine> lines, String subOrderNo) {
        return pointsService.grantOnPay(userNo, merchantNo, lines, subOrderNo);
    }
}
