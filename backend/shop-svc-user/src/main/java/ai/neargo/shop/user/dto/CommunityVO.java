package ai.neargo.shop.user.dto;

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
                          int distance,
                          List<PickupVO> pickups) {

    /**
     * 自提点（对齐 c-app {@code Pickup}）。
     *
     * <p>{@code leaderNo/leaderName/leaderAvatar} 是 ADR-004 之前的字段名，端上仍在用（E10 未完成）。
     * 后端填的是**承接商家**的信息，语义已经是「谁在这儿帮你收货」，只是名字还没改。
     * E10 完成后同步改成 {@code ownerNo/ownerName/ownerAvatar}。
     */
    public record PickupVO(String pickupNo,
                           String name,
                           String address,
                           int distance,
                           String leaderNo,
                           String leaderName,
                           String leaderAvatar,
                           String openHours,
                           String arrivalDesc) {
    }
}
