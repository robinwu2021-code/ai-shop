package ai.neargo.shop.invoice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 发票折让（V1）。 */
@Getter
@Setter
@TableName("ivc_allowance")
public class IvcAllowance {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String allowanceNo;
    private String tenantNo;
    private String entityNo;
    private String storeNo;
    private String invoiceNo;
    /** 含税折让金额 */
    private Long amountMinor;
    private Long taxMinor;
    private String reason;
    private String createdBy;
    private LocalDateTime createdAt;
}
