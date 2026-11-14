package ai.neargo.shop.merchant.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 当前人员账号关联；业务事实与登录授权分开保存。 */
@Getter
@Setter
@TableName("mch_personnel_account")
public class MchPersonnelAccount extends BaseEntity {
    /** 可信经营主体。 */
    private String entityNo;
    /** 关联业务号。 */
    private String linkNo;
    /** 人员。 */
    private String personnelNo;
    /** 当前账号，无绑定可空。 */
    private String mchAccountNo;
    /** 绑定时刻。 */
    private LocalDateTime linkedAt;
}
