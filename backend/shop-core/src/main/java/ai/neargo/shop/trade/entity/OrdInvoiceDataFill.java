package ai.neargo.shop.trade.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 门店零售单建单后补开票资料的稽核（V692，TDD-统一订单与开票资料 AC5）。只增不改：每个空字段只能补一次，
 * 唯一键 (sub_order_no, line_no, field_code) 兜住「已补过的不能再补」。{@code lineNo=0} 是整单级的买受人。
 */
@Getter
@Setter
@TableName("ord_invoice_data_fill")
public class OrdInvoiceDataFill extends BaseEntity {
    public static final String NAME = "NAME";
    public static final String TAX_CLASS = "TAX_CLASS";
    public static final String BUYER = "BUYER";

    private String fillNo;
    private String orderNo;
    private String subOrderNo;
    private String entityNo;
    private String storeNo;
    private Integer lineNo;
    private String fieldCode;
    private String oldValue;
    private String newValue;
    private String reason;
    private String operatorNo;
    private String requestKeyHash;
}
