package ai.neargo.shop.pay.entitlement.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Pay-owned durable use fact; never written through a core transaction. */
@Getter
@Setter
@TableName("ent_usage")
public class EntUsage extends BaseEntity {
    private String usageNo;
    private String operationNo;
    private String userNo;
    private String entityNo;
    private String storeNo;
    private String storeName;
    private String assetKind;
    private String title;
    private Integer units;
    private Long amountMinor;
    private String currency;
    private String itemsSnapshot;
    private String serviceStaffName;
    private String sourceOrderNo;
    private String operatorNo;
    private String status;
    private LocalDateTime occurredAt;
}
