package ai.neargo.shop.community.dto;

import java.util.List;

/**
 * 社区 + 其下自提点（对齐 c-app {@code Community}）。选点页一次拿全，不做二次请求 ——
 * 选社区和选自提点是同一个动作的两步，拆两个接口只会让页面出现中间态。
 *
 * @param distance 米。未传定位时为 0，端上按 0 隐藏距离展示
 */
public record CommunityVO(String communityNo,
                          String name,
                          String address,
                          /**
                           * 所属城市码。**契约里一直是必填**，后端此前不下发 ——
                           * 端上判「全市范围的商家在这个小区可不可达」要用它，
                           * mock 里有、真机上是 undefined，于是那条判断在真机上恒为 false。
                           */
                          String cityCode,
                          /**
                           * 所属街道/镇（9 位区划码）。商家框范围时「按街道看聚落」靠它 ——
                           * 不下发的话端上只能拿到一锅平铺清单，街道视图无从分组。
                           */
                          String regionCode,
                          /**
                           * ESTATE 小区 / VILLAGE 村 / BUILDING 楼栋。
                           * <b>不再只是展示标签</b>：BUILDING 这一档参与匹配（层级优先于距离）。
                           */
                          String kind,
                          /**
                           * 所属聚落（楼栋 → 小区/园区）。<b>为空 = 顶层聚落</b>。
                           *
                           * <p>不下发的话 B 端选择器只能把楼栋和小区平铺成一锅 ——
                           * 商家看到「阳光花园」和「阳光花园 3 幢」并排两行，分不出后者在前者里面，
                           * 于是他把两条都勾上（其实第二条是多余的），或者只勾了楼、以为整个小区都做了。
                           * 「框了小区就盖住里面每栋楼」这件事在界面上必须看得见，它才成立。
                           */
                          String parentNo,
                          int distance,
                          List<PickupVO> pickups,
                          /**
                           * 官方村码（{@code sys_region} 第五级），只有 {@code kind=VILLAGE} 且经由
                           * 官方名录开通的才有。<b>{@link #regionCode} 是它挂的街道/镇，不是它自己</b>——
                           * B 端经营范围选择器再往下钻一层（看这个村底下的自然村）时，要下钻的是
                           * 这个村自己的码，不是父级街道的码，两者混用会把「牛杜村」下钻成「牛杜镇」。
                           */
                          String originCode,
                          /**
                           * origin_code 对应的**原始官方名**（「景滑村委会」，未经清理）。
                           * 只有它的后缀能分辨「这是城区社区/居委会」还是「农村村委会」——
                           * {@link #name} 是商家起的口语名，开通那一刻就把这个信息丢了。
                           */
                          String originName,
                          /**
                           * 是不是村委会（{@code sys_region.rural}，经 origin_code 反查）。
                           * 只对 kind=VILLAGE 有意义——选择器据此决定这一条还给不给下钻：
                           * 村委会到此为止，社区/居委会还能再挑具体小区。
                           */
                          boolean rural,
                          /** 官方村名录批量补录过的坐标，可能为空。有它才能省一次服务端地理编码 */
                          Integer latE6,
                          Integer lngE6) {

    /**
     * 自提点（对齐 c-app {@code Pickup}）。
     *
     * <p>{@code leaderNo/leaderName/leaderAvatar} 是 ADR-004 之前的字段名，端上仍在用（E10 未完成）。
     * 后端填的是**承接商家**的信息，语义已经是「谁在这儿帮你收货」，只是名字还没改。
     * E10 完成后同步改成 {@code ownerNo/ownerName/ownerAvatar}。
     */
    /**
     * @param latE6 取货点坐标（gcj02，E6）。<b>可能为 null</b>：存量点是手填地址建的，没有坐标。
     *              买家侧「导航到这里」按它决定显不显示 —— 没坐标就只展示地址文本
     */
    public record PickupVO(String pickupNo,
                           String name,
                           String address,
                           int distance,
                           String leaderNo,
                           String leaderName,
                           String leaderAvatar,
                           String openHours,
                           String arrivalDesc,
                           Integer latE6,
                           Integer lngE6) {
    }
}
