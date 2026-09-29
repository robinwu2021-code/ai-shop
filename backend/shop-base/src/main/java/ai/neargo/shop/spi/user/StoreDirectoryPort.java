package ai.neargo.shop.spi.user;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 任意域 → merchant：C 端「以门店为单位」要用到的门店信息（TDD-C端门店化与门店门户）。
 *
 * <p>C 端此前以主体为单位：{@code /mp} 的主键全是 merchantNo，一个主体只能出一行，
 * 同一主体下四家店在小程序上都叫主体名。这个端口只回答「门店长什么样、在哪、能不能卖」，
 * 不回答主体的事 —— 那些仍在 {@link MerchantQueryPort}。
 *
 * <p><b>不并进 {@link MerchantQueryPort}</b>：那一个已经五十多个方法，
 * 且几乎都是「按主体问」。把门店目录塞进去，下一个人找「按门店问」的能力时要翻完整个接口。
 */
public interface StoreDirectoryPort {

    /** 门店状态：营业中 */
    String STORE_ACTIVE = "ACTIVE";

    /** 门店状态：商家自助停用。买家侧可见但不可下单（直达链接显示暂停营业） */
    String STORE_READONLY = "READONLY";

    /**
     * 按门店号取卡片。<b>包含停用（READONLY）的门店</b> —— 「我的店」里它要压淡显示，
     * 藏起来用户会以为自己常去的店没了。
     *
     * <p>主体不是 ACTIVE（被封、待补证照）或门店已删除的，<b>不返回</b>：
     * 调用方拿不到就当它不存在，不用各自再判一遍主体状态。
     */
    Map<String, StoreCard> cards(Collection<String> storeNos);

    /**
     * 能卖到某个社区的门店：门店 ACTIVE 且主体 ACTIVE，主体的服务范围覆盖这个社区
     * （与商家列表同一条三档规则）。{@code communityNo} 为空 = 不按社区过滤。
     *
     * @param keyword 按门店名模糊匹配；空 = 不过滤
     */
    List<StoreCard> reachableActive(String communityNo, String keyword);

    /**
     * 把 C 端链接上的编号解析成门店（TDD §2.1）。
     *
     * <p>{@code /mp} 的路径约定是单数，新旧门户只能共用 {@code /mp/store/{no}} 这一条路径，
     * 所以按<b>编号前缀</b>分派：
     * <ul>
     *   <li>门店号（{@code ST…}）—— 直接取；</li>
     *   <li>主体号（{@code M…}）—— 老分享、老店码、旧版小程序。取该主体的默认门店（要 ACTIVE），
     *       没有就取任一 ACTIVE 门店，都没有就取任一门店（门户会显示暂停营业）。</li>
     * </ul>
     * 编号前缀由 {@code BizKey} 生成，是稳定契约。未知前缀、查无此店 → 空。
     */
    Optional<StoreCard> resolve(String no);

    /**
     * 同主体下离这家店最近的另一家 ACTIVE 门店。暂停营业页用它给出路（TDD AC7）。
     * 两家店都要有坐标才算得出距离；算不出时退回同主体的默认门店。
     */
    Optional<StoreCard> nearestSibling(String storeNo);

    /**
     * 这家店的门面文案（公告、营业时间、地址、状态、坐标）。与
     * {@link MerchantQueryPort#storeFront} 同一个形状 —— 那一条按主体取「默认店」，这条按门店号取。
     * 过期的公告给空串（口径在 {@code MchStore.effectiveAnnouncement} 一处）。
     *
     * @return 门店不存在时为空；<b>不看状态</b>（暂停营业页也要显示地址与营业时间）
     */
    Optional<MerchantQueryPort.StoreFront> front(String storeNo);

    /**
     * 门店号 → {@code mch_store.status}，<b>不看主体状态</b>（下单落店要的是门店本身开没开）。
     * 查不到的门店不在结果里；空串状态是历史数据，调用方按营业处理（与门户的 closed 判断同一口径）。
     */
    Map<String, String> statuses(Collection<String> storeNos);

    /**
     * @param status    {@link #STORE_ACTIVE} / {@link #STORE_READONLY}
     * @param rating    门店评分 0–5（库里存 ×10）；{@code ratingCount = 0} 表示暂无评价，不是 0 分
     * @param latE6     可空：还没在地图上选过点的门店
     * @param logo      取主体的品牌标；门店没有自己的标
     */
    record StoreCard(String storeNo, String storeName, String entityNo, String logo,
                     String status, boolean isDefault, String openHours, String address,
                     Integer latE6, Integer lngE6, double rating, int ratingCount) {

        public boolean active() {
            return STORE_ACTIVE.equals(status);
        }
    }
}
