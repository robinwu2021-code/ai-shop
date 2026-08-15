package ai.neargo.shop.merchant.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 增值包档位定义（P-11.2.3，V150）。平台配置，运营可调不发版
 * （与 {@code sys_industry} / {@code sys_pay_channel} 同构，ADR-010）。
 *
 * <p><b>改了只影响新订阅</b>：已订阅的人用的是 {@link MchEntityPlan} 上的快照。
 * 接口文案要把这句说出来 —— 不说的话，改的人会以为自己刚刚动了全部存量。
 */
@Getter
@Setter
@TableName("sys_merchant_plan_def")
public class SysMerchantPlanDef extends BaseEntity {

    private String planCode;
    private String name;
    private Integer storeQuota;
    private Integer staffQuota;

    /** 能力位：跨店总览与对比（B-11.12.5/6） */
    private Boolean crossStoreStats;

    /** 试用天数，0 = 该档不可试用 */
    private Integer trialDays;

    /** 停售：只影响**新订阅**，已订阅的人照常用到到期 */
    private Boolean enabled;

    private Integer sort;
}
