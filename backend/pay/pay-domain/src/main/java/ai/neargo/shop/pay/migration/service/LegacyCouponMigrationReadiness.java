package ai.neargo.shop.pay.migration.service;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.migration.entity.EntMigrationStage;
import java.util.List;
/** Current migration capability gate shared by admission to source commitment and target import. */
public final class LegacyCouponMigrationReadiness {
    private LegacyCouponMigrationReadiness() { }
    public static void requireSupported(LegacyCouponTargetPlan plan,List<EntMigrationStage> stages) {
        if(stages.size()!=plan.sources().size()
                || stages.stream().anyMatch(stage->stage.getHistoryCount()==null || stage.getHistoryCount()<0)
                || (stages.getFirst().getHistoryCount()==0 && plan.assets().stream().anyMatch(asset->asset.remainingUnits()!=asset.totalUnits())))
            throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
    }
}
