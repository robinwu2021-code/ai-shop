package ai.neargo.shop.promotion.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("pmt_recharge_intent")
public class PmtRechargeIntent extends BaseEntity {
    private String intentNo;
    private String rechargeNo;
    private String entityNo;
    private String operatorNo;
    private String requestKeyHash;
    private String payloadHash;
    private String status;
    private String planSnapshot;
}
