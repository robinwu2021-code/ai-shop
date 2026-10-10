package ai.neargo.shop.common;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 快递公司 —— <b>存的、发的都是微信的 {@code delivery_id}</b>。
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
 * <p>代价是这张表会<b>锈</b>：微信改了码我们不知道。所以
 * {@link ExpressCompaniesFreshnessTest} 拿微信的全量表逐个核对，
 * 平时跳过，接支付/换号时手动跑一次。
 *
 * <h2>每一个码都是核验过的，不是凭印象写的</h2>
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
     * @param code 微信 {@code delivery_id}，原样上报
     * @param name 展示名，与微信的 {@code delivery_name} 一致 ——
     *             不一致的话，商家在我们这儿选的和微信那边显示的对不上
     */
    public record Company(String code, String name) {
    }

    /** 顺序就是 b 端发货界面的显示顺序：按国内件实际用量从高到低 */
    private static final List<Company> LIST = List.of(
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

    private static final Map<String, String> BY_CODE =
            LIST.stream().collect(Collectors.toMap(Company::code, Company::name));

    public static List<Company> all() {
        return LIST;
    }

    /**
     * 这个码认不认得。
     *
     * <p><b>认不得就要拒</b>，不要放行到上报那一步 ——
     * 微信那边回的是一个编码错误，而那时错误已经离「商家填错了」很远了：
     * 台账上是一条失败、界面上什么都没发生、而商家以为自己发过货了。
     */
    public static boolean isValid(String code) {
        return code != null && BY_CODE.containsKey(code);
    }

    /** 展示名；认不得的码返回 {@code null}（调用方据此判断，不要拿它当兜底展示） */
    public static String nameOf(String code) {
        return code == null ? null : BY_CODE.get(code);
    }
}
