package ai.neargo.shop.marketing.group.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 参团成员。一人一团只能参一次（唯一索引），否则「还差 N 人」会被一个人刷满。 */
@Getter
@Setter
@TableName("mkt_group_member")
public class MktGroupMember extends BaseEntity {

    private String groupNo;
    private String userNo;
    private String nickname;
    private Long joinedAt;

    /**
     * 参团付款的子单（V336）。<b>付款成功才落成员行</b> —— 没付钱的人不该让「还差 N 人」变少。
     * 唯一键在这一列上：同一笔付款回调重放，成员只加一次。存量行为空。
     */
    private String subOrderNo;
}
