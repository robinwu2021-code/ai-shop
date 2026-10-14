package ai.neargo.shop.trade.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.platform.SettingPort;
import ai.neargo.shop.trade.service.ProxyLimitService;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** 代客下单限额（{@link ProxyLimitService}）。存法与关单时限同一套：`sys_setting` 一行 JSON。 */
@Service
public class ProxyLimitServiceImpl implements ProxyLimitService {

    static final String KEY = "trade.proxy-limit";

    private static final String DEFAULT_JSON =
            "{\"maxAmountMinor\":" + DEFAULT_MAX_AMOUNT_MINOR
                    + ",\"maxPerDay\":" + DEFAULT_MAX_PER_DAY + "}";

    private final SettingPort settingPort;
    private final ObjectMapper json;

    public ProxyLimitServiceImpl(SettingPort settingPort, ObjectMapper json) {
        this.settingPort = settingPort;
        this.json = json;
    }

    @Override
    public ProxyLimitVO get() {
        Map<String, Object> m = readMap();
        return new ProxyLimitVO(
                longOf(m, "maxAmountMinor", DEFAULT_MAX_AMOUNT_MINOR),
                intOf(m, "maxPerDay", DEFAULT_MAX_PER_DAY),
                str(m, "updatedAt"), str(m, "updatedBy"));
    }

    @Override
    public ProxyLimitVO save(long maxAmountMinor, int maxPerDay, String operatorNo) {
        /*
         * 两个数都必须为正。**0 不是「不限」而是把整条路关死** ——
         * 想关掉代客下单该去收权限码，不该把限额设成 0：
         * 那样客服看到的是「金额超限」，而他下的是一单 8 块钱的菜。
         */
        if (maxAmountMinor <= 0 || maxPerDay <= 0) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("maxAmountMinor", maxAmountMinor);
        m.put("maxPerDay", maxPerDay);
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

    private static long longOf(Map<String, Object> m, String k, long fallback) {
        return m.get(k) instanceof Number n ? n.longValue() : fallback;
    }

    private static int intOf(Map<String, Object> m, String k, int fallback) {
        return m.get(k) instanceof Number n ? n.intValue() : fallback;
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : String.valueOf(v);
    }
}
