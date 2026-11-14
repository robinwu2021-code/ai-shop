package ai.neargo.shop.common;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 快递公司 —— <b>按门店所属主体的经营市场分表</b>（TDD-商户App快递公司）。
 * 大陆那张<b>存的、发的都是微信的 {@code delivery_id}</b>；台湾那张是我方自定义的码，
 * <b>不是微信码</b>，台湾单永远不会上报微信（{@code WxShippingUploadResolver} 只认大陆表）。
 *
 * <h2>码全局唯一</h2>
 * 订单上只存一个码，{@link #nameOf} 不带市场也能反查。台湾若也上顺丰，用独立码（如 {@code SF_TW}）——
 * 大陆 {@code SF} 的语义是「微信 delivery_id」，混用会让台湾单被当成能报微信的单。
 *
 * <h2>不认得的市场给空表</h2>
 * 不回落大陆：回落的话，阿联酋门店会看到一排大陆快递，选了以后发货成功、买家查不到件 ——
 * 比「一家都没有」更难发现。（市场<b>为空</b>是另一回事：那是存量主体没写过市场，由调用方按
 * {@code shop.merchant.default-market} 回落，见 {@link StoreMarkets}。）
 *
 * <h2>{@code Company} 的形状不要改</h2>
 * {@code packages/shared/tests/express-companies.test.ts} 用正则从本文件按市场抠
 * {@code new Company("…", "…")} 与 {@code BY_MARKET} 来和端上那份对账；改成三参或换写法，正则会静默抠空。
 *
 * <h2>为什么不自己编一套码再映射</h2>
 * 映射意味着多一处会错的地方，而错了的表现是微信回
 * {@code 268485227 快递公司编码不存在} —— 或者更糟：编码存在但不是这一家，
 * <b>微信收下了</b>（它不校验公司与单号是否匹配），
 * 买家点「查看物流」查到的是别家的单号，查无此单。
 *
 * <h2>为什么是一张内置表，而不是每次去微信拉</h2>
 * 微信全量表 1509 家（2026-09-20 实测），绝大多数是跨境小承运商，
 * 我们的商家用不到。拉全量意味着：一个新端点、一层缓存、
 * 一个 1509 项的搜索控件 —— 而发货界面上真正要的是十几个按钮。
 *
 * <p>代价是这张表会<b>锈</b>：微信改了码我们不知道。接支付 / 换号时要拿微信的全量表逐个核对
 * （此前注释里说的 {@code ExpressCompaniesFreshnessTest} 在仓库里并不存在）。
 *
 * <h2>大陆每一个码都是核验过的，不是凭印象写的</h2>
 * 2026-09-20 用 {@code /cgi-bin/express/delivery/open_msg/get_delivery_list}
 * 的全量表逐个查过。几个容易写错的：
 * <ul>
 *   <li>韵达是 {@code YD}，不是 {@code YUNDA}（不存在）；</li>
 *   <li>邮政包裹是 {@code YZPY}，不是 {@code POSTB}（不存在）；</li>
 *   <li>顺丰有 {@code SF}（速运）与 {@code NSF}（新顺丰）两个，要的是前者；</li>
 *   <li>百世快递是 {@code HTKY}，而 {@code BTWL} 是百世快运（另一家业务）。</li>
 * </ul>
 */
public final class ExpressCompanies {

    private ExpressCompanies() {
    }

    /**
     * @param code 承运商码。大陆是微信 {@code delivery_id}，原样上报；台湾是我方自定义码
     * @param name 展示名。大陆与微信的 {@code delivery_name} 一致 —— 不一致的话，商家在我们这儿选的和微信那边
     *             显示的对不上；台湾用官方繁体名（不随语言翻译，与面单一致）
     */
    public record Company(String code, String name) {
    }

    /** 大陆。顺序就是 b 端发货界面的显示顺序：按国内件实际用量从高到低 */
    private static final List<Company> CN = List.of(
            new Company("SF", "顺丰速运"),
            new Company("ZTO", "中通快递"),
            new Company("YTO", "圆通速递"),
            new Company("YD", "韵达速递"),
            new Company("STO", "申通快递"),
            new Company("JTSD", "极兔速递"),
            new Company("JD", "京东快递"),
            new Company("YZPY", "邮政快递包裹"),
            new Company("EMS", "EMS"),
            new Company("DBL", "德邦快递"),
            new Company("HTKY", "百世快递"),
            new Company("FWX", "丰网速运"),
            new Company("UC", "优速快递"),
            new Company("ZJS", "宅急送"));

    /**
     * 台湾首批（TDD-商户App快递公司 已拍板 X1）。码是我方自定义的；黑貓第一 —— App 默认选第一项，
     * 与它改版前发货页的默认值「黑貓宅急便」一致。首期都查不了轨迹（已拍板 X3）。
     */
    private static final List<Company> TW = List.of(
            new Company("TCAT", "黑貓宅急便"),
            new Company("HCT", "新竹物流"),
            new Company("TWPOST", "中華郵政"),
            new Company("KERRYTJ", "嘉里大榮物流"),
            new Company("PELICAN", "宅配通"));

    private static final Map<String, List<Company>> BY_MARKET = Map.of("CN", CN, "TW", TW);

    private static final Map<String, String> NAME_BY_CODE = new LinkedHashMap<>();

    static {
        for (List<Company> list : BY_MARKET.values()) {
            for (Company c : list) {
                if (NAME_BY_CODE.put(c.code(), c.name()) != null) {
                    throw new IllegalStateException("快递公司码跨市场重复：" + c.code());
                }
            }
        }
    }

    /** 本市场能选的，顺序即显示顺序；不认得的市场（含 null）给空表，<b>不回落大陆</b> */
    public static List<Company> forMarket(String market) {
        return market == null ? List.of() : BY_MARKET.getOrDefault(market.trim().toUpperCase(Locale.ROOT), List.of());
    }

    /**
     * 这个码在这个市场认不认得。
     *
     * <p><b>认不得就要拒</b>，不要放行到上报那一步 ——
     * 微信那边回的是一个编码错误，而那时错误已经离「商家填错了」很远了：
     * 台账上是一条失败、界面上什么都没发生、而商家以为自己发过货了。
     */
    public static boolean isValidFor(String market, String code) {
        return code != null && forMarket(market).stream().anyMatch(c -> c.code().equals(code));
    }

    /**
     * 码或本市场名称 → 码。码原样返回；名称去首尾空白后精确匹配本市场的表；都不是返回 empty。
     *
     * <p>名称归一是给 App 改版前兜底的（那时发货页是自由文本框、默认「黑貓宅急便」）。
     * <b>只在本市场的表里找</b>：台湾门店写「顺丰速运」不能被归成大陆的 {@code SF}、再被当成能报微信的单。
     */
    public static Optional<String> resolve(String market, String codeOrName) {
        if (codeOrName == null || codeOrName.isBlank()) {
            return Optional.empty();
        }
        String v = codeOrName.trim();
        return forMarket(market).stream()
                .filter(c -> c.code().equals(v) || c.name().equals(v))
                .map(Company::code)
                .findFirst();
    }

    /** 展示名，跨市场查（码全局唯一）；认不得的码返回 {@code null}（调用方据此判断，不要拿它当兜底展示） */
    public static String nameOf(String code) {
        return code == null ? null : NAME_BY_CODE.get(code);
    }

    /**
     * 大陆表认不认得。
     *
     * @deprecated 过渡期保留，等于 {@code isValidFor("CN", code)}；新代码按订单 / 门店所属市场用 {@link #isValidFor}
     */
    @Deprecated
    public static boolean isValid(String code) {
        return isValidFor("CN", code);
    }
}
