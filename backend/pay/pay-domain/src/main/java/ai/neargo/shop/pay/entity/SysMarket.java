package ai.neargo.shop.pay.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 市场主数据（V294 · S11）。
 *
 * <h2>它归 pay，而不是 platform</h2>
 * 市场决定的是<b>币种、账期时区、可用通道</b> —— 都是资金域的口径。
 * 放平台配置里的话，「一个市场一种记账币种」这条规则就没有主人：
 * 谁都能加一个市场，而没有人负责它的账怎么算。
 *
 * <h2>升格自一段 JSON</h2>
 * 此前市场存在平台设置的 `platform.markets` 里。JSON 的问题不是存不下，
 * 是<b>无法被引用与约束</b>：`market` 这个列早就在五张表上用着
 * （商品 SKU、门店价、积分账户、积分流水、积分池），
 * 而没有任何东西保证那些值真的在 JSON 里 ——
 * 写错一个市场码，积分会记进一个不存在的市场，<b>而不报错</b>。
 */
@Getter
@Setter
@TableName("sys_market")
public class SysMarket extends BaseEntity {

    /** 市场码。**引用列一律叫 market**，与既有五张表同名，不新造 market_code */
    private String market;

    private String name;

    /**
     * 记账币种。<b>一个市场一种</b> —— 改它等于换账本，
     * 所以运营端的保存接口只让改汇率与启停，碰不到这个字段。
     */
    private String currency;

    /**
     * 小数位。
     *
     * <p>日元是 0 位、科威特第纳尔是 3 位。全系统按「分」存整数是对的，
     * 但<b>「一元等于多少分」不是常量</b> —— 端上写死 2 会让日元的金额
     * 差 100 倍，而它不会报错，用户看到的付款金额直接是错的。
     */
    private Integer currencyScale;

    /** 账期与对账按它切天 */
    private String timeZone;

    /**
     * 相对 CNY 的展示汇率。
     *
     * <p><b>只用于折算显示，绝不参与结算</b>。参与的话汇率一动历史账就变 ——
     * 而结算是按各自币种独立成批的，本来也不需要换算。
     */
    private java.math.BigDecimal displayRate;

    /** 默认关。开一个市场要先配通道、费率、进件材料，那是一串运营动作 */
    private Boolean enabled;

    private Integer sortNo;
}
