package ai.neargo.shop.pay.market;

import java.util.List;
import java.util.Optional;

/**
 * 市场主数据（S11 · V294）。
 *
 * <p>市场决定<b>币种、小数位、账期时区、可用通道</b> —— 都是资金域的口径，
 * 所以它归 pay。此前这些存在平台设置的一段 JSON 里，
 * 而 JSON <b>无法被引用与约束</b>：`market` 这个列早就在五张表上用着，
 * 却没有任何东西保证那些值真的存在 —— 写错一个市场码，
 * 积分会记进一个不存在的市场，<b>而不报错</b>。
 */
public interface MarketService {

    /** 全部市场，含未启用的 —— 运营要能看到「有哪些可开」 */
    List<MarketRow> all();

    /** 已启用的市场 */
    List<MarketRow> enabled();

    /**
     * 某个市场。
     *
     * <p>查不到返回空，<b>不兜底成大陆</b> —— 兜底会让「市场码写错了」
     * 与「这单在大陆」在调用方看来一样，而前者该报错、后者该正常算账。
     */
    Optional<MarketRow> find(String market);

    /**
     * 这个市场用什么币种记账。
     *
     * <p>查不到给 {@code null}。端上拿不到币种时应当<b>不显示金额</b>，
     * 而不是按 2 位小数显示 —— 日元是 0 位，按 2 位显示会差 100 倍。
     */
    String currencyOf(String market);

    /**
     * 改汇率与启停。
     *
     * <p><b>只让改这两样。</b>币种与小数位一旦有交易就不能动 ——
     * 改它等于换账本，而历史账不会跟着变。
     * 接口层面不提供入口，比「改了但历史数据不动」那种半吊子行为干净。
     */
    MarketRow saveRate(String market, double displayRate, boolean enabled, String operatorNo);

    /**
     * @param currencyScale 小数位。<b>日元 0、科威特第纳尔 3</b> ——
     *                      写死 2 会让金额差 100 倍且不报错
     * @param displayRate   相对 CNY 的展示汇率，<b>只折算显示，不参与结算</b>
     */
    record MarketRow(String market, String name, String currency, int currencyScale,
                     String timeZone, double displayRate, boolean enabled, int sortNo) {
    }
}
