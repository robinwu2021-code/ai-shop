package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Restaurant reception fact; never owns retail money or inventory. */
@Getter
@Setter
@TableName("fnb_table_occupancy")
public class FnbTableOccupancy extends BaseEntity {
    private String entityNo;
    private String occupancyNo;
    private String tableNo;
    private String checkNo;
    private Integer seats;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private String resourceClaimNo;
    private String sharingMode;
}
