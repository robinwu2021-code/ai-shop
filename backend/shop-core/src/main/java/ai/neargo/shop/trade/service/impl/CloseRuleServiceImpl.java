package ai.neargo.shop.trade.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.platform.SettingPort;
import ai.neargo.shop.trade.service.CloseRuleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 关单策略的读写。存在 {@code SettingPort} 里，与快速退款阈值同一条路子。
 *
 * <p>走 {@code SettingPort} 而不是直接用 platform 的 {@code SettingService}：
 * trade 与 platform 都是业务域，域间只能走 spi 的 Port —— ArchUnit 第 1 条拦的正是这个。
 */
@Service
public class CloseRuleServiceImpl implements CloseRuleService {

    private static final Logger log = LoggerFactory.getLogger(CloseRuleServiceImpl.class);

    static final String KEY = "trade.close-rule";

    /**
     * 没配过时的整份默认值。
     *
     * <p>{@code autoRefundOnLateCallback} 默认 <b>false</b>：自动退款的开关默认开着是件危险的事 ——
     * 关掉它意味着迟到回调那笔钱要人工处理，但至少不会静默退掉一笔本可以补单的钱。
     * 与 {@code aftersale.fast-refund-rule} 的 {@code enabled:false} 同一个取舍。
     */
    private static final String DEFAULT_JSON =
            "{\"unpaidMinutes\":" + DEFAULT_UNPAID_MINUTES
                    + ",\"remindBeforeMinutes\":0,\"autoRefundOnLateCallback\":false}";

    private final SettingPort settingPort;
    private final ObjectMapper json;

    public CloseRuleServiceImpl(SettingPort settingPort, ObjectMapper json) {
        this.settingPort = settingPort;
        this.json = json;
    }

    @Override
    public CloseRuleVO get() {
        Map<String, Object> m = readMap();
        return new CloseRuleVO(
                intOf(m, "unpaidMinutes", DEFAULT_UNPAID_MINUTES),
                intOf(m, "remindBeforeMinutes", 0),
                Boolean.TRUE.equals(m.get("autoRefundOnLateCallback")),
                str(m, "updatedAt"), str(m, "updatedBy"));
    }

    @Override
    public int unpaidMinutes() {
        int n = intOf(readMap(), "unpaidMinutes", DEFAULT_UNPAID_MINUTES);
        /*
         * **读的时候也夹一次**，尽管 save 已经校验过。
         *
         * 理由不是不信任 save，是这份配置存在一张通用的参数表里 ——
         * 有人直接改库、或者将来多一条写入路径，都能绕过 save。
         * 而这个数被绕过的后果是下单链路直接受害：改成 0 意味着**每一单下单即过期**，
         * 用户永远付不了款，且没有任何报错。
         *
         * 夹住而不是抛异常：下单不该因为一个配置写错而整体不可用。
         */
        if (n < MIN_UNPAID_MINUTES || n > MAX_UNPAID_MINUTES) {
            log.warn("[close-rule] 库里的 unpaidMinutes={} 越界（{}~{}），本次下单按默认值 {} 处理 —— "
                            + "去查是谁绕过了 save 写进来的",
                    n, MIN_UNPAID_MINUTES, MAX_UNPAID_MINUTES, DEFAULT_UNPAID_MINUTES);
            return DEFAULT_UNPAID_MINUTES;
        }
        return n;
    }

    @Override
    @Transactional
    public CloseRuleVO save(int unpaidMinutes, int remindBeforeMinutes,
                            boolean autoRefundOnLateCallback, String operatorNo) {
        if (unpaidMinutes < MIN_UNPAID_MINUTES || unpaidMinutes > MAX_UNPAID_MINUTES) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        if (remindBeforeMinutes < 0) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        // 提醒提前量必须严格小于关单时限：相等意味着提醒发在下单那一刻，
        // 大于则发在下单之前 —— 两种都是「配置看着生效了、提醒永远不来」
        if (remindBeforeMinutes >= unpaidMinutes) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("unpaidMinutes", unpaidMinutes);
        m.put("remindBeforeMinutes", remindBeforeMinutes);
        m.put("autoRefundOnLateCallback", autoRefundOnLateCallback);
        m.put("updatedAt", Instant.now().toString());
        m.put("updatedBy", operatorNo);
        settingPort.put(KEY, json.writeValueAsString(m), operatorNo);
        return get();
    }

    private Map<String, Object> readMap() {
        return json.readValue(settingPort.get(KEY, DEFAULT_JSON),
                new tools.jackson.core.type.TypeReference<Map<String, Object>>() {
                });
    }

    private static int intOf(Map<String, Object> m, String k, int fallback) {
        return m.get(k) instanceof Number n ? n.intValue() : fallback;
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : String.valueOf(v);
    }
}
