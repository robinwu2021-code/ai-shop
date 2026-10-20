package ai.neargo.shop.pay.entitlement.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
@TableName("ent_sale_reservation")
public class EntSaleReservation extends BaseEntity {
    private String reservationNo;
    private String sourceKey;
    private String sourceSnapshot;
    private String commandSnapshot;
    private String payloadHash;
    private String templateNo;
    private String definitionSnapshot;
    private String userNo;
    private String entityNo;
    private String currency;
    private String status;
    private String grantNo;
    private String sourceDecisionNo;
}
