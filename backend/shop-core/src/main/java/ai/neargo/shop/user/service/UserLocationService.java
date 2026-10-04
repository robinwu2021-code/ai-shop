package ai.neargo.shop.user.service;

/**
 * 记用户「最后已知位置」（L2，TDD-虹选鲜果运营落地 §1）。
 *
 * <p>被动定位探得的**弱信号**，异步 + 节流写进 {@code usr_account.last_*}：
 * 移动超阈值**或**距上次写超时限才写，否则跳过 —— 每次定位一次写，频繁且无意义。
 *
 * <p><b>不覆盖 {@code community_no}</b>：那是用户主动绑的聚落（强），下单取货仍以它为准。
 * 这里写的 {@code last_community_no} 是「这次探到落进了哪个开放聚落」，另一列，各管各的。
 */
public interface UserLocationService {

    /**
     * @param communityNo 这次落进的开放聚落；没落进传 null（只到区县）
     */
    void recordLastLocation(String userNo, int latE6, int lngE6,
                            String regionCode, String place, String communityNo);
}
