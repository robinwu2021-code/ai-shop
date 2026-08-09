package ai.neargo.shop.platform.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 运营员工。与 C 端用户是**两套体系**（realm=OPERATOR vs CONSUMER）。
 *
 * <p>同一个人可能既是运营也是消费者，但两个身份的权限、会话、审计完全独立 ——
 * 合成一张表的话，「给自己加个管理员角色」会退化成一次普通的用户更新。
 */
@Getter
@Setter
@TableName("sys_ops_staff")
public class SysOpsStaff extends BaseEntity {

    private String staffNo;
    private String username;

    /** 生产存 bcrypt；一期是占位哈希，接 auth-core 时替换。 */
    private String password;

    private String realName;

    /** JSON 数组：角色码。 */
    private String roles;

    private String status;
}
