package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("fnb_stock_reservation_item")
public class FnbStockReservationItem extends BaseEntity {
    private String entityNo;
    private String storeNo;
    private String reservationNo;
    private String leafRef;
    private String sourceComponentRef;
    private String itemNo;
    private String baseUnit;
    private String phase;
    private String trackingMode;
    private String definitionHash;
    private String lastIssueMovementNo;
    private String lastIssueCommandNo;
    private String definitionSnapshot;
    private java.math.BigDecimal originalQty;
    private java.math.BigDecimal issuedQty;
    private java.math.BigDecimal releasedQty;
    private java.math.BigDecimal returnedQty;
}
