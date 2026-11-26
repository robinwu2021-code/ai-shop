package ai.neargo.shop.merchant.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 项目服务资格；业务事实与登录授权分开保存。 */
@Getter
@Setter
@TableName("mch_personnel_skill")
public class MchPersonnelSkill extends BaseEntity {
    /** 可信经营主体。 */
    private String entityNo;
    /** 资格号。 */
    private String skillNo;
    /** 人员。 */
    private String personnelNo;
    /** 可选门店限制。 */
    private String storeNo;
    /** 可服务项目。 */
    private String goodsNo;
    /** 可选规格约束。 */
    private String skuNo;
    /** 技能级别。 */
    private String levelCode;
    /** 生效。 */
    private LocalDateTime validFrom;
    /** 到期。 */
    private LocalDateTime validTo;
    /** PENDING或VERIFIED或REVOKED。 */
    private String status;
    /** 核验人。 */
    private String verifiedBy;
}
