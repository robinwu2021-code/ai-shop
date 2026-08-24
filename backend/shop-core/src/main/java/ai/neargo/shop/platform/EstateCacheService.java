package ai.neargo.shop.platform;

import java.util.List;
import java.util.Map;

/**
 * 某一片（街道 / 村·社区）的小区清单缓存。
 *
 * <p>小区这一级 {@code sys_region} 里没有，只能问地图。这层缓存让**第二次进同一片是本地读**，
 * 并且在上一级的列表上能预告「12 个小区 / 暂无小区」—— 没有这个预告，
 * 每一行的 › 都是一句没有依据的承诺，点进去才知道是不是空的。
 */
public interface EstateCacheService {

    /** 缓存多久算旧。旧了仍然先返回（stale-while-revalidate），由调用方决定要不要刷 */
    int TTL_DAYS = 30;

    /**
     * @param items 已归一的小区清单
     * @param stale 是否已过 TTL —— 端上据此决定要不要再问一次地图
     * @param cached 是否命中缓存（false = 这一片从没被抓过）
     */
    record Estates(String scopeCode, List<Estate> items, boolean cached, boolean stale) {
    }

    record Estate(String name, String address, Integer latE6, Integer lngE6, String poiId) {
    }

    /** 读一片。没抓过时返回 cached=false 的空清单，**不代抓** —— 抓不抓由调用方按有没有 key 决定 */
    Estates get(String scopeCode);

    /** 上一级下辖各片的条数，一次取回。key = scopeCode，只含抓过的那些 */
    Map<String, Integer> counts(String parentCode);

    /** 写一片。同一个 scopeCode 覆盖式更新（这是缓存，不是流水） */
    void put(String scopeCode, String parentCode, List<Estate> items);

    /**
     * **读穿透**：缓存新鲜就直接返回；缓存缺失或过期，且给得出圆心（直接坐标，
     * 或一条能被地理编码的地址），就现问地图、写回缓存、再返回。
     *
     * <p>这是给 {@code /biz/geo/estates} 用的唯一入口 —— App 不再自己调原生 SDK 拼结果、
     * 也不再把结果回传给后端写缓存：**服务端自己问地图、自己写缓存**，读写在同一侧，
     * 不会再出现「端上写的键和服务端读的键对不上」这类偏差（V206 那次真出过）。
     *
     * @param latE6      圆心（可选）。已知坐标时优先给，省一次地理编码
     * @param lngE6      同上
     * @param addressPath 圆心的地址描述（如「浙江省 / 杭州市 / 西湖区 / 北山街道 / 宝石社区」），
     *                   latE6/lngE6 都没给时用它地理编码
     * @param city       高德的 city 偏好（可选，缩小候选范围）
     */
    /**
     * @param rural 这一片是村委会（农村）还是社区/居委会（城区）。两边搜法不一样：
     *              城区搜「住宅小区」，农村搜「村」——沿用城区那套在农村会一条都搜不到，
     *              高德对住宅小区的分类标签根本不覆盖自然村。
     */
    Estates resolve(String scopeCode, String parentCode, Integer latE6, Integer lngE6,
                    String addressPath, String city, boolean rural);
}
