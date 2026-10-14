package ai.neargo.shop.merchant.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 员工与授权的操作日志（B-11.10.3）。
 *
 * <p><b>授权变更是权限扩散的唯一入口</b>：加人、停用、给角色、撤角色 ——
 * 这四个动作决定了「谁能碰这家店的什么」。在这张表之前它们做完就没了，
 * 三个月后问「谁把张三提成了店长」，库里只有一行当前状态。
 *
 * <p>其余动作（改商品、发货、核销）都有各自的业务单据兜底，唯独授权没有。
 *
 * <p><b>与运营端的 {@code sys_audit_log} 刻意不合并</b>：那张表的
 * {@code staff_no} 语义是平台运营员工，读它的是运营端的审计列表；
 * 而这里要的是「商家能查自己店里的授权变更」。
 */
@Getter
@Setter
@TableName("mch_staff_log")
public class MchStaffLog extends BaseEntity {

    /** 加员工（含把已停用的重新启用 —— 对老板来说那就是「把人加回来」） */
    public static final String STAFF_ADD = "STAFF_ADD";
    public static final String STAFF_ENABLE = "STAFF_ENABLE";
    public static final String STAFF_DISABLE = "STAFF_DISABLE";
    /** 授予某店某角色 */
    public static final String ROLE_GRANT = "ROLE_GRANT";
    /** 撤销某店某角色。撤到一个不剩 = 从这家店移除他 */
    public static final String ROLE_REVOKE = "ROLE_REVOKE";

    /*
     * 下面三个是**角色定义本身**的变更（V71 自定义角色）。
     *
     * 它们比「给某个人授权」影响更大：改一次角色的权限码，
     * **所有持有这个角色的人同时变**，而且下一个请求就生效。
     * 所以它同样要留痕，且 targetAccountNo 为空 —— 这类记录没有具体的「被操作的人」。
     */
    public static final String ROLE_CREATE = "ROLE_CREATE";
    public static final String ROLE_UPDATE = "ROLE_UPDATE";
    public static final String ROLE_DELETE = "ROLE_DELETE";

    private String logNo;
    private String entityNo;

    /**
     * 谁做的。
     *
     * <p>取不到当前身份时留 {@code null}，<b>不编一个值</b> ——
     * 「系统」这种占位在审计里是有害的：它把「查不出是谁」伪装成「就是系统干的」。
     */
    private String actorAccountNo;

    private String targetAccountNo;
    private String action;

    /** 涉及的门店；加人与启停不涉及门店，为 null */
    private String storeNo;
    /** 涉及的角色，取值域同 {@link MchStoreRole#GRANTABLE}；加人与启停为 null */
    private String role;

    /** 人能读的一句话，直接展示。**列留给查，它留给读** */
    private String detail;
}
