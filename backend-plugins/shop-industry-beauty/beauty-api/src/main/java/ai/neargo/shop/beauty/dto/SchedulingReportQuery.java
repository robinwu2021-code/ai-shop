package ai.neargo.shop.beauty.dto;

import java.time.OffsetDateTime;

/**
 * 服务业报表窗口（BAPI-119）：左闭右开、带偏移；App 按门店时区算好「今天 / 近 7 天 / 本月」再传。
 * 门店取当前 X-Store-No，不从参数里收。
 */
public record SchedulingReportQuery(OffsetDateTime from, OffsetDateTime to) { }
