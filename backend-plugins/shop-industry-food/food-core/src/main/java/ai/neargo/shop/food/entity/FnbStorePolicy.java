package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Restaurant policy history; effectiveFrom represents a UTC instant, audit fields retain platform conventions. */
@Getter
@Setter
@TableName("fnb_store_policy")
public class FnbStorePolicy extends BaseEntity {
    private String entityNo;
    private String policyNo;
    private String storeNo;
    private String timezone;
    private Integer cutoverMinute;
    private LocalDateTime effectiveFrom;
    private String payPolicy;
    private String capabilities;
    private String settings;
}
