package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** Working configuration only; accepted kitchen tasks retain their previously frozen route. */
@Getter
@Setter
@TableName("fnb_station")
public class FnbStation extends BaseEntity {
    private String entityNo;
    private String stationNo;
    private String storeNo;
    private String name;
    private String stationType;
    private Boolean enabled;
    private String publishedRevisionNo;
}
