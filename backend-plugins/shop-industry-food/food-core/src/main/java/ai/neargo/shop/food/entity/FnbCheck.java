package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Restaurant reception fact; never owns retail money or inventory. */
@Getter
@Setter
@TableName("fnb_check")
public class FnbCheck extends BaseEntity {
    private String entityNo;
    private String checkNo;
    private String storeNo;
    private LocalDate businessDate;
    private String policyNo;
    private Integer partySize;
    private String payMode;
    private String status;
    private LocalDateTime openedAt;
    private LocalDateTime closedAt;
    private String serviceEmployeeNo;
    private String serviceState;
}
