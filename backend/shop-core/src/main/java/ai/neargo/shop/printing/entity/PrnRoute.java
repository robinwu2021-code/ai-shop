package ai.neargo.shop.printing.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Shared printing persistence; business identifiers alone cross the API boundary. */
@Getter
@Setter
@TableName("prn_route")
public class PrnRoute extends BaseEntity {
    private String entityNo;
    private String routeNo;
    private String storeNo;
    private String scene;
    private String stationNo;
    private String deviceNo;
    private String templateNo;
    private Integer copies;
    private String matchRule;
}
