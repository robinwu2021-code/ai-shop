package ai.neargo.shop.promotion.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** Core-owned activity facts; pay assets remain outside this persistence boundary. */
@Getter
@Setter
@TableName("pmt_quota")
public class PmtActivityParticipant extends BaseEntity {
    private String targetType;
    private String targetNo;
    private String quotaDimension;
    private String activityNo;
    private String userNo;
    private Long quotaHeld;
    private Long quotaUsed;
}
