package ai.neargo.shop.community.service;

import ai.neargo.shop.community.dto.CommunityVO;
import ai.neargo.shop.community.dto.RegionOptionVO;

import java.util.List;

/** 社区与自提点（[API 清单 §2.2]）。游客可访问 —— 选社区发生在登录之前。 */
public interface CommunityService {

    /**
     * 附近社区（含其下自提点）。
     *
     * @param latE6 纬度 ×1e6，可空（未授权定位时按名称序返回）
     * @param lngE6 经度 ×1e6，可空
     */
    List<CommunityVO> nearby(Integer latE6, Integer lngE6);

    /**
     * 一个坐标解析出「我在哪」以及归属链。**这是 C 端匹配的唯一入口。**
     *
     * <p>为什么不让端上拿 {@link #nearby} 自己挑第一条：**「最内层」的判据是业务规则**
     * （层级优先于距离 —— 站在楼门口时，隔壁小区的中心可能比本楼中心更近）。
     * 放端上就会有三份实现（c-app / b-app / 将来的 H5），而它们迟早不一样。
     *
     * @param coarse 坐标是不是**模糊定位**给的（区级，误差约 5 公里）。
     *               是的话<b>不做聚落匹配</b>：围栏是 1000 米（小区）到 150 米（楼栋）量级，
     *               用 5 公里误差的坐标去匹配，出来的是噪音不是结果。
     *               此时返回空的 innermost，但**仍然给出所在区县**（{@code regionCode}）——
     *               端上据此按区看货，而不是回落到「全平台商品」。
     */
    LocationVO resolve(Integer latE6, Integer lngE6, boolean coarse);

    /**
     * 一个坐标的位置上下文。
     *
     * @param innermostNo   最内层聚落；**null 不是异常** —— 一个围栏都没落进（新城区）
     *                      或坐标是模糊的，都会是 null，端上照常要有东西看
     * @param innermostName 顶栏直接显示它，省端上再查一次
     * @param chainNos      归属链上的全部聚落（含 innermost，从内到外）。
     *                      商品池按「链上任一命中」取并集
     * @param coarse        原样回传，端上据此决定要不要显示距离
     * @param regionCode    所在**区县**码（6 位）。模糊定位这一级唯一能给出的结论 ——
     *                      5 公里误差落不准小区，但落得准区。端上把它当商品池的筛选条件，
     *                      于是「位置不明」不再等于「看全平台的货」：后者是过滤被跳过的副作用，
     *                      用户看到的是一屏买不到的东西。<b>推不出来时为 null</b>，
     *                      那一级才是空态要位置
     * @param regionName    「西湖区」。顶栏要把它说出来 —— 只有说明白「当前按 XX 区在看」，
     *                      用户才知道这一屏为什么不精确，以及该去点哪儿
     * @param nearestNo     **没落进任何围栏时**，最近的那个已开通聚落（M6）。
     *                      端上拿它当默认归属 —— 冷启动期全市只有一两个聚落，
     *                      「不在围栏里」是常态而不是异常，而按区筛在那时几乎总是空的。
     *                      <b>超出上限时为 null</b>（配置 {@code shop.community.default-bind-radius-m}）：
     *                      够不着的地方给一个默认归属，等于让人看一屏送不到的货。
     *                      落进围栏时也是 null —— 那时 {@code innermostNo} 就是答案，
     *                      再给一个「最近的」只会让端上有两个主语
     * @param nearestName   顶栏直接显示
     * @param nearestDistanceM 到最近那个聚落的米数。<b>超上限时仍然给</b> ——
     *                      端上才说得出「最近的也有 80 公里」；<b>算不出时是 -1</b>，
     *                      不是 0（0 会被显示成「0 米」，那是一句假话）
     */
    /**
     * @param place **端上唯一要读的那个「我在哪」**。四个页面各拼一份地名，
     *              迟早给出四个答案，而它们不同时界面上没有任何提示。
     *              取不到时为 null，端上退回 {@code regionName}，<b>不编地名</b>。
     */
    record LocationVO(String innermostNo, String innermostName,
                      java.util.List<String> chainNos, boolean coarse,
                      String regionCode, String regionName,
                      String nearestNo, String nearestName, int nearestDistanceM,
                      PlaceVO place) {
    }

    /**
     * 一个解析出来的地点。
     *
     * @param kind   名字有多具体：COMMUNITY（我们自己的聚落）/ POI（建筑）/
     *               AOI（小区楼盘）/ STREET（街道门牌）
     * @param source 名字**从哪儿来**：COMMUNITY / PLACE_DB / MAP / PLACE_DB_STALE。
     *               与 {@code kind} 是两件事，必须都给 —— 合成一个字段的话，
     *               「库里拿到的建筑名」与「现问的街道名」就分不开了
     * @param stale  端上据此标「位置可能不是最新的」
     */
    record PlaceVO(String name, String address, String kind, String source, boolean stale) {
    }

    /** 社区详情（含其下常驻自提点）。 */
    CommunityVO detail(String communityNo);

    /**
     * 全部社区。B 端选覆盖范围用（ADR-009）—— 商家选的是「我送得到哪些小区」，
     * 那是他自己知道的经营半径，与他此刻站在哪儿无关，所以不按定位排序。
     */
    List<CommunityVO> all();

    /**
     * 全部已开通社区，可按行政区划筛。
     *
     * @param regionCode 区划码前缀。国标码本身是层级前缀（省 2 / 市 4 / 区县 6 / 街道 9），
     *                   所以传「3301」能捞出整个杭州市，传「330106」只捞西湖区 ——
     *                   不用先查一遍子区划再 IN 一大串
     */
    List<CommunityVO> all(String regionCode);

    /**
     * <b>有已开通社区的</b>区域清单，按「市 → 区」两级聚合。
     *
     * <p>为什么不直接给区划全表：库里有 2978 个区县、41352 个街道，
     * 让用户在里面挑一个，十有八九挑到一个**一家店都没有**的区 ——
     * 那不是「选区域」，那是抽奖。这里只列真的有货可买的地方，并带上社区数。
     */
    List<RegionOptionVO> openRegions();

    /** 自提点详情（C-CM-02）：地址、营业时间、到货时间。 */
    CommunityVO.PickupVO pickupDetail(String pickupNo);

    /**
     * 门店可引用的取货点候选（P1）：在这些社区里、ACTIVE、常驻的 STORE/PLATFORM 点，
     * 再并上本店自己建的点（含 PENDING/REJECTED，商家才知道自己建的点去哪了）。
     * 不给距离：门店本身没有坐标可算；本店的排最前，其余按社区归组、同组按名字。
     */
    List<PickupCandidate> pickupCandidates(java.util.Collection<String> communityNos, String ownerStoreNo);

    /**
     * 商家自建自提点（P1）：落 STORE 型、owner 为本店、<b>PENDING</b> 待运营核实。
     * 坐标必填 —— 没坐标的点买家用定位永远找不到；社区不传时按坐标就近归到已开通社区。
     */
    PickupCandidate selfBuildPickup(SelfBuildCmd cmd);

    record PickupCandidate(String pickupNo, String name, String address, String type, String status,
                           String communityNo, String communityName, String ownerStoreNo,
                           String rejectReason) {
    }

    /**
     * @param fallbackCommunityNo 按坐标就近归不到社区时（存量社区大多没坐标）退到这个社区——
     *                            调用方给主体经营范围里的第一个；都没有才拒
     */
    record SelfBuildCmd(String storeNo, String name, String address, Integer latE6, Integer lngE6,
                        String openHours, String communityNo, String fallbackCommunityNo) {
    }
}
