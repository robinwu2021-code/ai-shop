package ai.neargo.shop.fulfillment.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 邻里自提点（C-GB-06 / ADR-005）：团发起人勾「送到我家」时建的**团粒度临时点**，随团生随团灭。
 *
 * <p><b>这个类刻意没有任何费用字段。</b>有费用列就迟早有人填，而一旦承接的邻居能收钱，
 * 他就是团长 —— ADR-004 消掉的合规问题会原样回来。零报酬不是默认值，是这张表的结构本身。
 *
 * <p>与 {@code cmt_pickup_point}（常驻网点主数据）分开建表的理由：那张表由运营维护、
 * 有营业时间与服务费口径；这里的点只活到本团核销完。混在一起会让「本社区有哪些自提点」
 * 每次都要额外排除一堆已经消失的临时点。
 */
@Getter
@Setter
@TableName("ful_group_pickup")
public class FulGroupPickup extends BaseEntity {

    public static final String ACTIVE = "ACTIVE";
    public static final String CLOSED = "CLOSED";

    private String pickupNo;

    /** 所属团。<b>核销的作用域就是它</b> —— 拿别团的码来核销必须被拒。 */
    private String groupNo;

    /** 承接人 = 团发起人本人，不能是别人。开放指定他人即等于团长招募。 */
    private String userNo;

    /** 如「3 幢老王家」。 */
    private String name;

    /**
     * 完整地址。<b>成团前只到楼栋，付款后才给完整门牌</b>（B13）——
     * 脱敏在下发处做，库里存完整值，否则发起人自己也看不到自家门牌。
     */
    private String address;

    /** 约定取货时段：邻居家不能一直堆着货（B15）。 */
    private String timeSlot;

    /** ACTIVE / CLOSED（随团结束）。 */
    private String status;

    /**
     * 批次签收时间。<b>未签收前不允许逐单核销</b> ——
     * 货还没到发起人手里就核销，等于替商家确认了一件没发生的事。
     */
    private Long receivedAt;
}
