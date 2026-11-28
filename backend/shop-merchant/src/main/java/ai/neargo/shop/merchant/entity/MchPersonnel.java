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
    /**
     * 关联的 B 端账号（可空）。原先另存在 mch_personnel_account，一对一并进人员（TDD-平台基础库换表 第 2 步 D，Q4）：
     * 关联号改用人员号，关联版本改用人员的 version。
     */
    private String mchAccountNo;
    /** 关联账号的时间；解除关联时清空 */
    private java.time.LocalDateTime accountLinkedAt;
}
