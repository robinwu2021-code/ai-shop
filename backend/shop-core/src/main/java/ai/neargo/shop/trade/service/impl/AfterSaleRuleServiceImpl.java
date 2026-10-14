package ai.neargo.shop.trade.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.platform.SettingPort;
import ai.neargo.shop.trade.entity.OrdAfterSale;
import ai.neargo.shop.trade.service.AfterSaleRuleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 售后规则的读写。存在 {@code SettingPort} 里，与关单策略同一条路子。 */
@Service
public class AfterSaleRuleServiceImpl implements AfterSaleRuleService {

    private static final Logger log = LoggerFactory.getLogger(AfterSaleRuleServiceImpl.class);

    /**
     * <b>沿用运营端已经在写的那个键</b>，不另起一个。
     *
     * <p>换键等于把运营已经配好的值悄悄作废：页面还显示着他配的数，生效的却是新键的默认值。
     */
    static final String KEY = "aftersale.fast-refund-rule";

    /**
     * 没配过时读到的是<b>空对象</b>，每个字段各自退回自己的默认值。
     *
     * <p>不写成一份完整的默认 JSON，是为了让「运营存过一份老形状」与「从没存过」走同一条路：
     * 新增字段在老记录里缺着，缺一个就退一个，而不是整份被当成有效值使用 ——
     * 那会让新加的 {@code replyHours} 读出 0，也就是每笔申请下一分钟自动同意。
     *
     * <p>{@code enabled} 因此默认 <b>true</b>：极速退此前是无条件生效的（代码里没有开关），
     * 默认成 false 会让一个纯粹的「接线」改动把线上已有的行为关掉。
     */
    private static final String DEFAULT_JSON = "{}";

    /**
     * 极速退上限的**默认值**（不是第二份真源）：真正生效的仍是参数表里那一行，
     * 这个数只在它缺着的时候顶上。
     *
     * <p>保留这条属性是因为测试环境用它把阈值压到 ¥50
     * （{@code application-testcfg.yml}）—— 删掉它，那些按「6980 高于阈值」写的用例
     * 会在阈值变成 ¥100 之后静默变成「低于阈值」，而它们断言的正是不该秒退的那条分支。
     */
    @org.springframework.beans.factory.annotation.Value(
            "${shop.after-sale.instant-threshold-minor:" + DEFAULT_MAX_AMOUNT + "}")
    private long defaultMaxAmount = DEFAULT_MAX_AMOUNT;

    private final SettingPort settingPort;
    private final ObjectMapper json;

    public AfterSaleRuleServiceImpl(SettingPort settingPort, ObjectMapper json) {
        this.settingPort = settingPort;
        this.json = json;
    }

    @Override
    public AfterSaleRuleVO get() {
        Map<String, Object> m = readMap();
        return new AfterSaleRuleVO(
                !Boolean.FALSE.equals(m.get("enabled")),
                longOf(m, "maxAmount", defaultMaxAmount),
                hours(m, "withinHours", DEFAULT_WITHIN_HOURS),
                categoriesOf(m),
                hours(m, "replyHours", DEFAULT_REPLY_HOURS),
                days(m, "shipBackDays", DEFAULT_SHIP_BACK_DAYS),
                hours(m, "confirmHours", DEFAULT_CONFIRM_HOURS),
                days(m, "interveneWorkDays", DEFAULT_INTERVENE_WORK_DAYS),
                str(m, "updatedAt"), str(m, "updatedBy"));
    }

    @Override
    public boolean instantEligible(String type, long refundMinor, Long paidAt) {
        // 退货退款不能自动：货还没回来就退钱等于白送
        if (!OrdAfterSale.REFUND_ONLY.equals(type)) {
            return false;
        }
        AfterSaleRuleVO r = get();
        if (!r.enabled() || refundMinor > r.maxAmount()) {
            return false;
        }
        /*
         * `withinHours` 此前**从来没被判过** —— 运营端能配、库里存着，而生效的是
         * 「任何时候都能极速退」。买家可以在下单半年后申请仅退款，照样秒退。
         *
         * paidAt 为空时不判：拿不到时间就按「符合」处理，比按「不符合」安全 ——
         * 后者会让所有取不到支付时间的单静默退出极速退，而那是个看不见的降级。
         */
        if (paidAt != null && paidAt > 0) {
            long deadline = paidAt + r.withinHours() * 3_600_000L;
            if (System.currentTimeMillis() > deadline) {
                return false;
            }
        }
        /*
         * `categories` 只存不判，与它在运营端的样子一致（那一屏也一直只是写进去）。
         * 判它需要把子单里每一件商品的类目取回来，而那是 product 域的读 ——
         * 要么给 GoodsQueryPort 加一个方法，要么在这里跨域直查。两者都不该
         * 夹在这次「把悬空配置接上」的改动里做，于是留成一条记账：
         * 空 = 全品类，非空时**当前实现同样按全品类处理**。
         */
        return true;
    }

    @Override
    @Transactional
    public AfterSaleRuleVO save(AfterSaleRuleVO req, String operatorNo) {
        if (req == null || req.maxAmount() <= 0) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        // 0 小时等于把功能关掉，但开关看起来还是开着的 —— 运营会以为极速退在跑
        checkHours(req.withinHours());
        checkHours(req.replyHours());
        checkHours(req.confirmHours());
        checkDays(req.shipBackDays());
        checkDays(req.interveneWorkDays());

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", req.enabled());
        m.put("maxAmount", req.maxAmount());
        m.put("withinHours", req.withinHours());
        m.put("categories", req.categories() == null ? List.of() : req.categories());
        m.put("replyHours", req.replyHours());
        m.put("shipBackDays", req.shipBackDays());
        m.put("confirmHours", req.confirmHours());
        m.put("interveneWorkDays", req.interveneWorkDays());
        m.put("updatedAt", Instant.now().toString());
        m.put("updatedBy", operatorNo);
        settingPort.put(KEY, json.writeValueAsString(m), operatorNo);
        return get();
    }

    private static void checkHours(int n) {
        if (n < MIN_HOURS || n > MAX_HOURS) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }

    private static void checkDays(int n) {
        if (n < MIN_DAYS || n > MAX_DAYS) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }

    private Map<String, Object> readMap() {
        return json.readValue(settingPort.get(KEY, DEFAULT_JSON),
                new tools.jackson.core.type.TypeReference<Map<String, Object>>() {
                });
    }

    /**
     * 读的时候也夹一次，尽管 save 已经校验过 —— 这份配置存在一张通用参数表里，
     * 有人直接改库或将来多一条写入路径都能绕过 save。而被绕过的后果不会报错：
     * {@code replyHours=0} 意味着**每一笔售后申请下一分钟就被系统自动同意**。
     */
    private static int hours(Map<String, Object> m, String k, int fallback) {
        return clamp(intOf(m, k, fallback), MIN_HOURS, MAX_HOURS, fallback, k);
    }

    private static int days(Map<String, Object> m, String k, int fallback) {
        return clamp(intOf(m, k, fallback), MIN_DAYS, MAX_DAYS, fallback, k);
    }

    private static int clamp(int n, int min, int max, int fallback, String k) {
        if (n < min || n > max) {
            log.warn("[after-sale] 库里的 {}={} 越界（{}~{}），本次按默认值 {} 处理 —— "
                    + "去查是谁绕过了 save 写进来的", k, n, min, max, fallback);
            return fallback;
        }
        return n;
    }

    private static int intOf(Map<String, Object> m, String k, int fallback) {
        return m.get(k) instanceof Number n ? n.intValue() : fallback;
    }

    private static long longOf(Map<String, Object> m, String k, long fallback) {
        return m.get(k) instanceof Number n ? n.longValue() : fallback;
    }

    @SuppressWarnings("unchecked")
    private static List<String> categoriesOf(Map<String, Object> m) {
        return m.get("categories") instanceof List<?> l ? (List<String>) l : List.of();
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : String.valueOf(v);
    }
}
