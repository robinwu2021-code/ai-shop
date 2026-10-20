package ai.neargo.shop.pay.storedvalue.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("sv_posting_line")
public class SvPostingLine extends BaseEntity {
    private String voucherNo;
    private Integer lineNo;
    private String pool;
    private String book;
    private String side;
    private Long amountMinor;
}
