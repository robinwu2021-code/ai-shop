package ai.neargo.shop.beauty.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.Getter;
import lombok.Setter;

/**
 * 美业门店政策头，存在行业共用表 {@code ind_store_policy}（与餐饮同表，每店每行业一行；TDD-美业域迁移新库 B-2）。
 * 读一律经 {@link #beautyQuery()} 带上 {@code industry_code='BEAUTY'}，写入时行业码由字段默认值带上。
 */
@Getter
@Setter
@TableName("ind_store_policy")
public class BtyStorePolicy extends BaseEntity {
    public static final String INDUSTRY = "BEAUTY";

    /** 共用表的 {@code tenant_no} 没有库默认值（旧美业表默认 'MAIN'），平台审计也不填它：在这里带上。 */
    public BtyStorePolicy() {
        setTenantNo("MAIN");
    }

    /** 主体数据域；由可信调用范围确定。 */
    private String entityNo;
    /** 政策号。 */
    private String policyNo;
    /** 门店。 */
    private String storeNo;
    /** 行业：美业固定为 BEAUTY；同店的餐饮行由 FNB 区分。 */
    private String industryCode = INDUSTRY;
    /** 当前版本。 */
    private String currentPolicyVersionNo;
    /** ACTIVE或INACTIVE。 */
    private String status;

    /** 只看美业的行。 */
    public static LambdaQueryWrapper<BtyStorePolicy> beautyQuery() {
        return Wrappers.<BtyStorePolicy>lambdaQuery().eq(BtyStorePolicy::getIndustryCode, INDUSTRY);
    }
}
