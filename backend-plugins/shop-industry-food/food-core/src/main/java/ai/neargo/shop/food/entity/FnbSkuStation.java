package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** Working configuration only; accepted kitchen tasks retain their previously frozen route. */
@Getter
@Setter
@TableName("fnb_sku_station")
public class FnbSkuStation extends BaseEntity {
    private String entityNo;
    private String skuNo;
    private String stationNo;
    private String taskRole;
    private Long routeVersion;
    private String storeNo;
}
