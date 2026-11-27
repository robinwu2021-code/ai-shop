package ai.neargo.shop.merchant.plan.mapper;

import ai.neargo.shop.merchant.plan.entity.MchEntityPlan;
import ai.neargo.shop.merchant.plan.entity.SysMerchantPlanDef;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * 套餐定义与主体订阅的 Mapper。
 *
 * <p>主体向平台买套餐是平台商城的业务，新的库表设计把这两张表放在平台商城库，不在平台基础库。
 * 所以从商户域的 Mapper 集合里拆出来；商户核心不直接用这里的 Mapper，
 * 开通主体时经 {@link ai.neargo.shop.merchant.plan.EntityPlanProvisioner} 建默认订阅。
 */
public final class PlanMappers {

    private PlanMappers() {
    }

    /** 增值包档位定义（平台配置，运营可调）。 */
    public interface PlanDefMapper extends BaseMapper<SysMerchantPlanDef> {
    }

    /** 主体的增值包订阅（一主体一行）。 */
    public interface EntityPlanMapper extends BaseMapper<MchEntityPlan> {
    }
}
