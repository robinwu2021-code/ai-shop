package ai.neargo.shop.merchant.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 跨门店业务任职；业务事实与登录授权分开保存。 */
@Getter
@Setter
@TableName("mch_personnel_store")
public class MchPersonnelStore extends BaseEntity {
    /** 可信经营主体。 */
    private String entityNo;
    /** 任职号。 */
    private String assignmentNo;
    /** 人员。 */
    private String personnelNo;
    /** 任职门店。 */
    private String storeNo;
    /** 生效。 */
    private LocalDateTime validFrom;
    /** 结束。 */
    private LocalDateTime validTo;
    /** ACTIVE或INACTIVE。 */
    private String status;
    /** 对客展示。 */
    private Boolean publicVisible;
    /** 本店业务等级。 */
    private String levelCode;
    /** 展示顺序。 */
    private Integer sortOrder;
}
