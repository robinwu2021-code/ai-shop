package ai.neargo.shop.merchant.reach;

import ai.neargo.shop.spi.reach.ConsumerProfile;

/**
 * 「这个消费者在不在那条多边形范围项里」—— 边界 cell 命中后的精判（ADR-034）。
 *
 * <p>抽成接口是为了让 {@link ReachRule} 保持纯静态、可不起 Spring 单测：
 * 测试传 lambda，生产传 {@link PolygonCache}（按 area_no 读 geometry 并缓存解析结果）。
 */
public interface PolygonProbe {

    /** 几何缺失或解析失败时返回 false —— 判不出来就不放行 */
    boolean covers(String areaNo, ConsumerProfile profile);
}
