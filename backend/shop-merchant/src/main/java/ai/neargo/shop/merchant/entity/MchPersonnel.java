package ai.neargo.shop.merchant.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 共用业务人员主档；业务事实与登录授权分开保存。 */
@Getter
@Setter
@TableName("mch_personnel")
public class MchPersonnel extends BaseEntity {
    /** 可信经营主体。 */
    private String entityNo;
    /** 稳定业务人员号。 */
    private String personnelNo;
    /** 业务姓名。 */
    private String name;
    /** 媒体引用。 */
    private String avatarRef;
    /** ACTIVE或SUSPENDED或LEFT。 */
    private String status;
    /** 受控联系资料引用。 */
    private String contactRef;
}
