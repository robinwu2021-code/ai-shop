package ai.neargo.shop.platform.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;
import java.util.Set;

/**
 * 入驻申请。与 {@code mch_entity} 分开：申请可以被驳回后重提，
 * 而商家主体一旦创建就有了商品、订单、结算，不该跟着申请状态来回变。
 */
@Getter
@Setter
@TableName("mch_entity_apply")
public class MchEntityApply extends BaseEntity {

    public static final String PENDING = "PENDING";
    /** 已受理，客服正在看。<b>不是流程完整性摆设</b> —— 商家提交后一直显示「待审核」
     *  不知道有没有人在看；客服接手时点一下，那边就有反馈。 */
    public static final String REVIEWING = "REVIEWING";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";

    /**
     * 合法迁移。<b>APPROVED 是终态</b> —— 已经建了商家、发了账号，回退没有意义。
     *
     * <p><b>PENDING 可以直接到 APPROVED</b>，不强制先经 REVIEWING：一期运营就几个人，
     * 看一眼就批是常态。把「受理」做成必经步骤只会让人为了走流程而点一下，
     * 那样 REVIEWING 就退化成一个没有信息量的状态。它的价值在于**商家看到有人在看**，
     * 而不在于流程完整。
     *
     * <p>REJECTED 是终态，<b>重提是新开一份申请单而不是把旧的改回 PENDING</b> ——
     * 原地改会把驳回记录抹掉，事后就查不出这家店被驳回过几次、为什么。
     */
    public static final Map<String, Set<String>> TRANSITIONS = Map.of(
            PENDING, Set.of(REVIEWING, APPROVED, REJECTED),
            REVIEWING, Set.of(APPROVED, REJECTED),
            REJECTED, Set.of(),
            APPROVED, Set.of());

    /** 状态机是否允许这次迁移。未知状态一律拒 —— 库里出现脏值时该拦住，不是放行。 */
    public static boolean canMove(String from, String to) {
        return TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    /** 进行中 = 还占着「一人一份」的名额 */
    public static boolean inProgress(String status) {
        return PENDING.equals(status) || REVIEWING.equals(status);
    }

    private String applyNo;
    private String userNo;

    /** 审核通过后回填。 */
    private String entityNo;

    private String name;
    private String legalForm;
    private String contactPhone;

    /** 联系人姓名。审核要打电话找人，只有号码没有姓名不合适 */
    private String contactName;

    /** 主营类目。决定通过后授予的类目授权范围 */
    private String category;

    /** 店铺简介。C 端门店主页要展示 */
    private String description;

    /** 通过后写入 mch_entity.service_scope（ADR-009） */
    private String serviceScope;

    /**
     * JSON 数组。<b>serviceScope=COMMUNITY 时通过审核必填</b> ——
     * 空着就是「商家上着架却一个订单都不来」，而他和运营都查不出原因。
     */
    private String communityNos;

    /**
     * 进行中时 = user_no，进入终态时置 NULL。配合唯一键挡住重复提交 ——
     * 唯一索引忽略 NULL，所以终态的历史申请不占名额。
     *
     * <p><b>{@code updateStrategy = ALWAYS} 是这个字段的命根子</b>：
     * MyBatis-Plus 默认 {@code NOT_NULL}，会把 null 字段直接从 UPDATE 的 SET 里剔掉 ——
     * 于是「审核完成时释放名额」这行代码<b>一句话都没执行</b>，
     * 被驳回的申请永远占着位子，商家补完料再也提交不上来。
     *
     * <p>这个故障只在<b>驳回之后重提</b>时才现形：直接通过的链路把 activeOwner
     * 留在那儿也没人再查，所以先前的测试全绿。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String activeOwner;

    /**
     * 是否愿意承接自提点（ADR-005）。<b>只记录意愿，不代表点已建立</b> ——
     * 建点要谈服务费口径，一期由运营在通过后另行处理。
     */
    private Boolean asPickupPoint;
    private String qualifications;
    private String status;
    private String rejectReason;
    private String auditedBy;
    private Long auditedAt;

    /**
     * 所属行业（{@code sys_industry.industry}）。
     *
     * <p><b>与商品类目是两个维度</b>：行业挂商家，类目挂商品。
     * 它决定商家<b>可选的主体类型</b> —— 线上业态不能选小微。
     */
    private String industry;
}
