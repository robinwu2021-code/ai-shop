package ai.neargo.shop.user.dto;

import ai.neargo.shop.user.entity.UsrUser;

/**
 * C 端「我」。字段与 {@code packages/shared/src/types/index.ts} 的 {@code User} 逐字对齐 ——
 * 这份镜像关系是 c-app 从 mock 翻真后端的全部依据，改名等于让端上解析失败。
 *
 * <p><b>{@code userNo} 与 {@code cUserNo} 双写</b>（变更单 C1 / 决议 Q1）：命名统一到 {@code userNo}，
 * 但 c-app 现有 45 处仍读 {@code cUserNo}。双写让两端不必互相等待 —— 前端改完（C2）即可删掉后者。
 *
 * <p>不含 {@code leaderNo}/{@code leaderStatus}：团长角色已按 ADR-004 删除。
 * c-app 的类型里还留着可选字段（E10 未完成），少两个可选字段不影响端上解析。
 */
public record UserVO(String userNo,
                     String cUserNo,
                     String nickname,
                     String avatar,
                     String phone,
                     String communityNo,
                     String pickupNo,
                     String merchantNo) {

    public static UserVO of(UsrUser u) {
        // C1 过渡期双写：两个字段同值。前端改完 C2 后删 cUserNo，删的时候只动这一行
        return new UserVO(u.getUserNo(), u.getUserNo(),
                u.getNickname(), u.getAvatar(), maskPhone(u.getPhone()),
                u.getCommunityNo(), u.getPickupNo(), u.getMerchantNo());
    }

    /** 自己的手机号也脱敏：端上只用来展示「已绑定 138****8000」，没有场景需要完整号。 */
    private static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}
