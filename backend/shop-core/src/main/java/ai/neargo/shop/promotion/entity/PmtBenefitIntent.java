package ai.neargo.shop.promotion.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("pmt_benefit_intent")
public class PmtBenefitIntent extends BaseEntity {
    private String intentNo;
    private String userNo;
    private String requestKeyHash;
    private String payloadHash;
    private String status;
    private String planSnapshot;
}
