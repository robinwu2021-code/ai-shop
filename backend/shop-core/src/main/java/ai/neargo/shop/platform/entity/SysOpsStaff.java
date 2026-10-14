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

    /**
     * 首登必须改密。
     *
     * <p>建号时后端生成随机初始密码、<b>只在创建响应里返回一次</b> ——
     * 那个密码经过了建号人的屏幕与剪贴板，可能还有一条聊天记录，
     * 它只是「拿到账号」的凭据，不是长期口令。
     */
    private Boolean mustChangePassword;

    /**
     * 数据域：<b>空 = 不限定（全量）</b>，不是「还没配」。
     *
     * <p>⚠️ <b>当前只存不用</b> —— 各域的查询还没有按它裁剪。
     * 配了以为限定住了而实际没有，比不配更危险，所以 UI 上标明了尚未生效。
     * 真正的裁剪是单独一批（要动几十处查询，漏一处就是越权）。
     */
    private String merchantNo;

    private String communityNo;

    private String pickupNo;

    /** 最近登录时刻（毫秒）。停用一个长期没登录的账号之前要知道这个。 */
    private Long lastLoginAt;
}
