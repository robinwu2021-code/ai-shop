package ai.neargo.shop.food.dto;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 门店基本信息（TDD-餐饮门店基本信息与时间约定）：POS 连通探测、营业日、币种、时区一次拿全。
 *
 * @param market         主体所在市场；没配置时为 null（平台不兜底成 CN）
 * @param currency       市场币种；同上可为 null
 * @param currencyScale  币种小数位；同上可为 null
 * @param businessDate   按门店时区当前时刻减切日分钟推算的营业日（与营业日是否已开无关）
 * @param businessDayStartsAt 该营业日起点（UTC）
 * @param businessDayEndsAt   该营业日终点（UTC，起点 + 24 小时）
 * @param serverTime     服务器当前时刻（UTC），供客户端估算时钟偏差
 */
public record StoreProfileVO(String storeNo, String storeName, String entityNo, String status,
        String market, String currency, Integer currencyScale,
        String timezone, int cutoverMinute, String payPolicy,
        LocalDate businessDate, Instant businessDayStartsAt, Instant businessDayEndsAt, Instant serverTime) {
}
