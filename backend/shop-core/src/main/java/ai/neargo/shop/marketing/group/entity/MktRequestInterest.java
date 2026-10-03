package ai.neargo.shop.marketing.group.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 求团 +1。是意向表达，不产生任何交易。 */
@Getter
@Setter
@TableName("mkt_request_interest")
public class MktRequestInterest extends BaseEntity {

    private String requestNo;
    private String userNo;
    private Long at;
}
