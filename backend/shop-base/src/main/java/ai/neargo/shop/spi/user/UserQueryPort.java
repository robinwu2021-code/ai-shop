package ai.neargo.shop.spi.user;

import java.util.Optional;

/**
 * trade/fulfillment → user：买家的**展示信息**。
 *
 * <p>只给昵称与手机号后四位。不给完整手机号是刻意的：调用方（核销台、分拣单）
 * 需要的是「认出这个人」，而不是联系他 —— 需要联系时走平台的客服通道，
 * 而不是把号码散到每个自提点的手机上（M11/B12）。
 */
public interface UserQueryPort {

    Optional<UserBrief> find(String userNo);

    record UserBrief(String userNo, String nickname, String phoneTail) {
    }
}
