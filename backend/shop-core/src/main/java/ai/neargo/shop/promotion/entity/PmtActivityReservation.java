package ai.neargo.shop.promotion.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Core-owned activity facts; pay assets remain outside this persistence boundary. */
@Getter
@Setter
@TableName("pmt_activity_reservation")
public class PmtActivityReservation extends BaseEntity {
    private String reservationNo;
    private String sourceKey;
    private String activityNo;
    private String versionNo;
    private String tierNo;
    private String userNo;
    private String entityNo;
    private String storeNo;
    private String currency;
    private Integer quotaUnits;
    private Long budgetMinor;
    private String status;
    private String permitSnapshot;
    private String payloadHash;
    private LocalDateTime deadline;
    private String payFactNo;
}
