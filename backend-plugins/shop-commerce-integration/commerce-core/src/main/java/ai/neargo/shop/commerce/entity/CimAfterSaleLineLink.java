package ai.neargo.shop.commerce.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@TableName("cim_after_sale_line_link")
public class CimAfterSaleLineLink extends BaseEntity {
    private String linkNo;
    private String entityNo;
    private String caseNo;
    private String orderLineLinkNo;
    private String sourceLineRef;
    private String componentRef;
    private String coreItemRef;
    private String unit;
    private String allocationEvidenceRef;
    private BigDecimal quantity;
    private Long refundCeilingMinor;
}
