package ai.neargo.shop.beauty.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 美业门店政策头；复用平台审计，业务号独立于物理主键。 */
@Getter
@Setter
@TableName("bty_store_policy")
public class BtyStorePolicy extends BaseEntity {
    /** 主体数据域；由可信调用范围确定。 */
    private String entityNo;
    /** 政策号。 */
    private String policyNo;
    /** 门店。 */
    private String storeNo;
    /** 当前版本。 */
    private String currentPolicyVersionNo;
    /** ACTIVE或INACTIVE。 */
    private String status;
}
