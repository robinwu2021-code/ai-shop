package ai.neargo.shop.merchant.plan.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.merchant.plan.EntityPlanProvisioner;
import ai.neargo.shop.merchant.plan.entity.MchEntityPlan;
import ai.neargo.shop.merchant.plan.entity.SysMerchantPlanDef;
import ai.neargo.shop.merchant.plan.mapper.PlanMappers.EntityPlanMapper;
import ai.neargo.shop.merchant.plan.mapper.PlanMappers.PlanDefMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;

@Service
public class EntityPlanProvisionerImpl implements EntityPlanProvisioner {

    private final EntityPlanMapper entityPlans;
    private final PlanDefMapper planDefs;

    public EntityPlanProvisionerImpl(EntityPlanMapper entityPlans, PlanDefMapper planDefs) {
        this.entityPlans = entityPlans;
        this.planDefs = planDefs;
    }

    /**
     * 建 FREE 订阅行（V150）。
     *
     * <p>迁移里的回填只覆盖迁移那一刻的存量，新入驻的商家没人给它建行 ——
     * 漏了这一步的症状不是报错，是额度一路落到配置兜底：测试环境那个兜底是 3，
     * 于是新商家能开三家店，而他明明是 FREE。
     */
    @Override
    public void ensureFreePlan(String entityNo) {
        boolean exists = DataScopeContext.executeWithoutScope(() ->
                entityPlans.exists(Wrappers.<MchEntityPlan>lambdaQuery().eq(MchEntityPlan::getEntityNo, entityNo)));
        if (exists) {
            return;
        }
        var def = DataScopeContext.executeWithoutScope(() ->
                planDefs.selectOne(Wrappers.<SysMerchantPlanDef>lambdaQuery()
                        .eq(SysMerchantPlanDef::getPlanCode, MchEntityPlan.FREE).last("limit 1")));
        var row = new MchEntityPlan();
        row.setEntityNo(entityNo);
        row.setPlanCode(MchEntityPlan.FREE);
        // 档位定义缺失时给 1/0 —— 与 FREE 的种子逐字一致，不去读那个会被改的配置
        row.setStoreQuota(def == null || def.getStoreQuota() == null ? 1 : def.getStoreQuota());
        row.setStaffQuota(def == null || def.getStaffQuota() == null ? 0 : def.getStaffQuota());
        row.setCrossStoreStats(def != null && Boolean.TRUE.equals(def.getCrossStoreStats()));
        row.setStatus(MchEntityPlan.ACTIVE);
        row.setGrantedBy(MchEntityPlan.BY_SELF_PAID);
        row.setTrialUsed(false);
        DataScopeContext.executeWithoutScope(() -> entityPlans.insert(row));
    }
}
