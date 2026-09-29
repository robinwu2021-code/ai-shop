package ai.neargo.shop.elec.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("elc_rfq_line")
public class ElcRfqLine extends ElcMutableEntity {

    private String rfqNo;

    private Integer lineNo;

    private String partNo;

    private String mpnRaw;

    private String mfrRaw;

    private Long qty;

    private Long targetE6;

    /** 平台报的含税单价；空 = 这一行没报（没找到货） */
    private Long quoteE6;

    private Long quoteQty;

    private Integer quoteDcYear;

    private Integer quoteLeadDays;

    /** 这个价给的是什么货况。买家要原装而平台报的是散新，必须说出来 */
    private String quoteCond;

    private String quotePacking;

    private String quoteNote;
}
