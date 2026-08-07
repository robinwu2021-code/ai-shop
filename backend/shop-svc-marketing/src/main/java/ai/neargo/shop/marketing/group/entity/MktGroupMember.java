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
}
