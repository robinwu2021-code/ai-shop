package ai.neargo.shop.pay.service.recon;

import ai.neargo.shop.pay.points.recon.PointsReconService;
import ai.neargo.shop.pay.points.store.PointsReadStore;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Existing finance overview delegates to the new ledger, never the retired points tables. */
@Component
public class PointsPoolReconAxis implements ReconAxis {
    public static final String CODE = "POINTS_POOL";
    private final PointsReconService recon;
    private final PointsReadStore read;
    private final boolean enabled;

    public PointsPoolReconAxis(
            PointsReconService recon,
            PointsReadStore read,
            @Value("${shop.points.enabled:false}") boolean enabled) {
        this.recon = recon;
        this.read = read;
        this.enabled = enabled;
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public ScanOutcome scan(long now) {
        if (!enabled) {
            return ScanOutcome.none();
        }
        int scanned = 0, opened = 0, deferred = 0;
        for (var type : read.types()) {
            var report = recon.scan(type.typeCode(), now);
            scanned++;
            opened += report.differences().size();
            if (!report.complete()) {
                deferred++;
            }
        }
        return new ScanOutcome(scanned, 0, opened, deferred);
    }

    @Override
    public Coverage coverage() {
        return new Coverage(
                false,
                "核对积分新凭证、头寸、资金证据与预留；容量超限报告未完成。" + "可信源订单生产者最后接入，未接入时不代表订单事实已齐全。差异仅记录待处理，不自动改余额。");
    }
}
