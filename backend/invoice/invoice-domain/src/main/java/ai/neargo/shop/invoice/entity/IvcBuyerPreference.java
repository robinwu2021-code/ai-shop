package ai.neargo.shop.invoice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 付款前预存的买受人选择（V1）。自动开票时使用；同一来源以最后一次为准。 */
@Getter
@Setter
@TableName("ivc_buyer_preference")
public class IvcBuyerPreference {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantNo;
    private String entityNo;
    private String sourceType;
    private String sourceNo;
    private String buyerTaxId;
    private String buyerName;
    private String carrierType;
    private String carrierId;
    private String donateCode;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
