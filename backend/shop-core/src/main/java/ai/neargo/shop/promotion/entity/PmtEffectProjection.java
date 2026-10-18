package ai.neargo.shop.promotion.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Core-owned activity facts; pay assets remain outside this persistence boundary. */
@Getter
@Setter
@TableName("pmt_effect_projection")
public class PmtEffectProjection extends BaseEntity {
    private String sourceEventNo;
    private String activityNo;
    private String entityNo;
    private String effectKind;
    private Long amountMinor;
    private String currency;
    private Long units;
    private LocalDateTime occurredAt;
}
