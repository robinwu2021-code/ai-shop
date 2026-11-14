package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/** Existing V1 closing evidence; signed rows are never overwritten by the closing service. */
@Getter
@Setter
@TableName("fnb_close_run")
public class FnbCloseRun extends BaseEntity {
    private String entityNo;
    private String closeNo;
    private String dayNo;
    private String currency;
    private String status;
    private String sourceWatermark;
    private String snapshotHash;
    private String signedBy;
    private String previousCloseNo;
    private Integer revision;
    private LocalDateTime signedAt;
}
