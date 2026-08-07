package ai.neargo.shop.marketing.group.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 改价留痕。**这是业务表不是日志表**：C 端要读它来公示涨价（ADR-003 §5）。
 * 藏起来的话，「报低价钓单再涨价」无人能发现 —— 那正是不做事前审核最怕的事。
 */
@Getter
@Setter
@TableName("mkt_quote_revision")
public class MktQuoteRevision {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String quoteNo;
    private String requestNo;
    private String merchantNo;
    private Long fromPriceMinor;
    private Long toPriceMinor;
    private Boolean raised;
    private Long at;
    private String tenantNo;
    private LocalDateTime createdAt;
}
