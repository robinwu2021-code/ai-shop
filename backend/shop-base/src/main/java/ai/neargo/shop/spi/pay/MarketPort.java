package ai.neargo.shop.spi.pay;

import java.util.List;
import java.util.Optional;

/**
 * platform / trade → pay：市场主数据。
 *
 * <h2>为什么要经过一层 Port</h2>
 * 市场归 pay（币种与账期口径是资金域的知识），而读它的地方遍布各域 ——
 * 平台配置页、商家进件、结算、C 端金额显示。
 * 各域直接 import pay 的实现的话，<b>ops 部署（不含支付域）连编译都过不去</b>，
 * 而架构守卫拦的正是这个。
 */
public interface MarketPort {

    /** 全部市场，含未启用的 */
    List<MarketBrief> all();

    /**
     * 某个市场。<b>查不到返回空，不兜底成大陆</b> ——
     * 兜底会让「市场码写错了」与「这单在大陆」在调用方看来一样。
     */
    Optional<MarketBrief> find(String market);

    /** 改汇率与启停。**币种与小数位不给改** —— 改它等于换账本 */
    void saveRate(String market, double displayRate, boolean enabled, String operatorNo);

    /**
     * @param currencyScale 小数位。日元 0、科威特第纳尔 3 ——
     *                      端上写死 2 会让金额差 100 倍且不报错
     * @param displayRate   相对 CNY 的展示汇率，<b>只折算显示，不参与结算</b>
     */
    record MarketBrief(String market, String name, String currency, int currencyScale,
                       String timeZone, double displayRate, boolean enabled) {
    }
}
