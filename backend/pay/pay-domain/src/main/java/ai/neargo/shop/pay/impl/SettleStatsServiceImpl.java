package ai.neargo.shop.pay.impl;

import ai.neargo.shop.pay.SettleStatsService;
import ai.neargo.shop.pay.entity.StlBill;
import ai.neargo.shop.pay.mapper.SettleMappers;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** {@link SettleStatsService} 实现。口径见接口注释：**按快照列聚合，绝不 join 回主数据**。 */
@Service
public class SettleStatsServiceImpl implements SettleStatsService {

    private final SettleMappers.BillMapper billMapper;

    public SettleStatsServiceImpl(SettleMappers.BillMapper billMapper) {
        this.billMapper = billMapper;
    }

    /**
     * 维度 → 快照列。
     *
     * <p><b>列名写死在这里而不是让调用方传</b>：传字符串等于把列名拼接的口子开在
     * 服务边界上，而这是个带 {@code GROUP BY} 的查询。枚举进来、列名出去，
     * 中间没有任何来自外部的字符串进 SQL。
     */
    private static String columnOf(Dim dim) {
        return switch (dim) {
            case STORE -> "store_no";
            case ENTITY -> "entity_no";
            case PAY_MERCHANT -> "pay_merchant_no";
        };
    }

    @Override
    public List<StatRow> stats(Dim dim, long fromMillis, long toMillis, String businessMode) {
        String col = columnOf(dim);

        /*
         * **不绕过数据域。** stl_bill 登记了 MERCHANT 锚点，配了「只看某商家」的运营
         * 在这一页就该只看到那一家 —— 统计口径上的越权与列表上的越权是同一件事，
         * 而它更隐蔽：一个合计数看不出里面混进了别家的单。
         */
        QueryWrapper<StlBill> w = new QueryWrapper<>();
        w.select(col,
                "SUM(gross_minor) AS gross_minor",
                "SUM(commission_minor) AS commission_minor",
                "SUM(service_fee_minor) AS service_fee_minor",
                "SUM(channel_fee_minor) AS channel_fee_minor",
                "SUM(net_minor) AS net_minor",
                "COUNT(*) AS bill_count");
        /*
         * 按 accrued_at 也就是**成交日**取区间。
         *
         * 不用 created_at：那是「这行什么时候被写进来的」，补数或重算会让它漂移。
         * 也不用 settleable_at / due_at：那两个回答的是「什么时候能结 / 什么时候到账」，
         * 而这一页要答的是「那天卖了多少」。
         */
        w.ge("accrued_at", fromMillis).le("accrued_at", toMillis);
        if (businessMode != null && !businessMode.isBlank()) {
            w.eq("business_mode", businessMode);
        }
        w.groupBy(col);
        // 大额在前。同额时按维度值兜底排序 —— 否则两次查询的行序可能不同，
        // 而页面上「顺序变了」看起来像数据变了
        w.orderByDesc("net_minor").orderByAsc(col);

        return billMapper.selectMaps(w).stream().map(m -> new StatRow(
                keyOf(pick(m, col)),
                num(pick(m, "gross_minor")), num(pick(m, "commission_minor")),
                num(pick(m, "service_fee_minor")), num(pick(m, "channel_fee_minor")),
                num(pick(m, "net_minor")),
                (int) num(pick(m, "bill_count")))).toList();
    }

    /**
     * 空维度值 → {@link #UNASSIGNED}。
     *
     * <p>见接口注释规则 2：不能丢掉这一组。{@code GROUP BY} 本来就把 NULL 归成一组，
     * 丢它需要额外写一个 {@code WHERE ... IS NOT NULL} —— 而那正是错的方向。
     */
    private static String keyOf(Object v) {
        String s = v == null ? null : String.valueOf(v);
        return s == null || s.isBlank() ? UNASSIGNED : s;
    }

    /** SUM 在不同库上回来的可能是 Long / BigDecimal / BigInteger，统一收成 long。空组为 0 */
    private static long num(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }

    /** Map 的键大小写随库而异（H2 默认大写），所以取值时两种都试一次 */
    private static Object pick(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v != null ? v : m.get(key.toUpperCase());
    }
}
