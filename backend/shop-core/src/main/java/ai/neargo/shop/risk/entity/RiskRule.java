package ai.neargo.shop.risk.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 拦截规则（P-16.2.5）：一类一条。
 *
 * <p>⚠️ {@code autoBlock} 这一版**不接下单/支付链路**（TDD-运营端风控域 §二 D3）。
 * 它是运营对每类风险的**处置意愿声明**：接拦截点时读的就是它，
 * 不必再设计一次配置面。
 */
@Getter
@Setter
@TableName("risk_rule")
public class RiskRule extends BaseEntity {

    private String type;

    /** 必须 &gt; 0 —— 0 等于全量拦截。 */
    private Integer threshold;

    private Integer windowHours;
    private Boolean autoBlock;
}
