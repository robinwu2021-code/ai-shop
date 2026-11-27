package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 门店的餐饮经营设置：时区、营业日切点、付款方式、能力码（行业库 ind_store_policy，餐饮与美业共用，按行业区分）。
 *
 * <p>TDD-餐饮域迁移新库 §2.5（用户 2026-11-27 定）：每店每行业一行，改策略即原地更新、立即生效，不保留历史版本，
 * 不支持未来生效。营业日自身冻结起止时间，不依赖策略历史。
 */
@Getter
@Setter
@TableName("ind_store_policy")
public class FnbStorePolicy extends BaseEntity {
    /** 餐饮在 ind_store_policy 上的行业代码。 */
    public static final String INDUSTRY = "FNB";
    public static final String ACTIVE = "ACTIVE";

    private String entityNo;
    private String policyNo;
    private String storeNo;
    private String industryCode;
    private String timezone;
    private Integer cutoverMinute;
    private String payPolicy;
    private String capabilities;
    private String status;
}
