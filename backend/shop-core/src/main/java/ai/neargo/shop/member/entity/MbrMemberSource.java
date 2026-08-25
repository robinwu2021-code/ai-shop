package ai.neargo.shop.member.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 会员来源明细：**每一次**都留一行。
 *
 * <p><b>为什么不塞成一列 JSON</b>：「李姐帮我拉来多少人」「上个月的店铺码带来几个会员」
 * 「小王录的那批人有几个真来消费了」—— 这三个问题都是按列聚合，JSON 里只能全表扫。
 * 而第一个问题正是分享激励要结算的那个数。
 */
@Getter
@Setter
@TableName("mbr_member_source")
public class MbrMemberSource extends BaseEntity {

    private String sourceNo;
    private String memberNo;
    private String entityNo;
    private String sourceType;
    private String storeNo;

    /** 哪一条分享链接 / 店铺码 */
    private String linkNo;

    /**
     * 这一次来源对应的单据号：{@code ORDER} 时是子订单号。
     *
     * <p><b>幂等靠它</b>：支付回调会重发，同一张子订单只该被算一次。
     * 用来源明细当台账，而不是另建一张「已处理事件表」—— 那是第二处要维护的东西，
     * 而这些行本来就要一行行留着。
     */
    private String refNo;

    /** 谁发的链接。分享激励结算读它 —— 只记「来自分享」就没法结算，商家也不知道该谢谁 */
    private String inviterUserNo;

    /** MERCHANT 商家自己发的 / STAFF 员工 / CUSTOMER 老客转发 */
    private String inviterRole;

    /** MANUAL 时哪个员工录的。录错了要找得到人 */
    private String operatorNo;

    private String activityNo;
    private Integer isFirst;
    private Long occurredAt;
}
