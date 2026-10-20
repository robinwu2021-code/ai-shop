package ai.neargo.shop.trade.entitlement.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("ord_card_sale_snapshot")
public class OrdCardSaleSnapshot extends BaseEntity {
    private String snapshotNo;
    private String sourceKey;
    private String orderNo;
    private String subOrderNo;
    private String orderLineNo;
    private Integer unitIndex;
    private Integer entitlementIndex;
    private String userNo;
    private String entityNo;
    private String storeNo;
    private String goodsNo;
    private String skuNo;
    private String templateVersionNo;
    private Long unitPriceMinor;
    private String currency;
    private String ruleSnapshot;
    private String snapshotHash;
    private String paidFactNo;
    private String deliveryStatus;
    private LocalDateTime paidAt;
}
