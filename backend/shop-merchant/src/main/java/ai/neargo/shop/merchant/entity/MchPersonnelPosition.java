package ai.neargo.shop.merchant.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 任职上的业务岗位；业务事实与登录授权分开保存。 */
@Getter
@Setter
@TableName("mch_personnel_position")
public class MchPersonnelPosition extends BaseEntity {
    /** 可信经营主体。 */
    private String entityNo;
    /** 岗位分配号。 */
    private String positionNo;
    /** 任职。 */
    private String assignmentNo;
    /** WAITER或TECHNICIAN等受控业务岗位。 */
    private String positionCode;
    /** ACTIVE或INACTIVE。 */
    private String status;
}
