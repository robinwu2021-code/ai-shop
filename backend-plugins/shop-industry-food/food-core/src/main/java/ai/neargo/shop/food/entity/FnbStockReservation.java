package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("fnb_stock_reservation")
public class FnbStockReservation extends BaseEntity {
    private String entityNo;
    private String storeNo;
    private String reservationNo;
    private String sourceSystem;
    private String sourceNo;
    private String operationKey;
    private String requestHash;
    private String planHash;
    private String sourcePlanHash;
    private Integer protocolVersion;
    private String status;
    private Long sourceVersion;
    private Long lifecycleVersion;
    private java.time.LocalDateTime expiresAt;
}
