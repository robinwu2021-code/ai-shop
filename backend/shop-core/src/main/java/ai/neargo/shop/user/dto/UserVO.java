package ai.neargo.shop.user.dto;

import ai.neargo.shop.user.entity.UsrAccount;

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
                     String merchantNo,
                     boolean nicknameSet) {

    /*
     * **phone 是完整号码，不脱敏** —— 这个 VO 只返回给号码的主人自己（资料 / 登录 / 绑定）。
     *
     * 此前连本人也脱敏成 138****8000，端上就拿不到自己的号：新增地址、开店申请的联系电话
     * 只能让他再输一遍，而这个号几分钟前刚验证过。别人的号仍按 Masks 脱敏
     * （AddressVO.forFulfillment、会员、核销），那是另一件事。
     */
    public static UserVO of(UsrAccount u) {
        // C1 过渡期双写：两个字段同值。前端改完 C2 后删 cUserNo，删的时候只动这一行
        return new UserVO(u.getUserNo(), u.getUserNo(),
                u.getNickname(), u.getAvatar(), u.getPhone(),
                u.getCommunityNo(), u.getPickupNo(), u.getEntityNo(),
                nicknameSet(u.getNickname()));
    }

    /**
     * 这个昵称是用户自己设的吗。
     *
     * <p>判据是「与占位名不同」，而占位名来自 {@link UsrAccount#DEFAULT_NICKNAME} ——
     * 与建户时写进去的那个值是**同一个常量**。分成两处写的话，改了默认值之后
     * 判据恒为 true，所有人的「设置昵称」入口一起消失，而没有任何报错。
     *
     * <p>代价：用户真把自己命名为「微信用户」时会被判成没设过
     * （见 TDD-C端个人资料与密码 §7）。接受它是因为这个名字本身就是占位名，
     * 而另一条路（加一列 nickname_set）要动迁移 + 实体 + schema-test.sql 三处。
     */
    private static boolean nicknameSet(String nickname) {
        return nickname != null
                && !nickname.isBlank()
                && !UsrAccount.DEFAULT_NICKNAME.equals(nickname);
    }
}
