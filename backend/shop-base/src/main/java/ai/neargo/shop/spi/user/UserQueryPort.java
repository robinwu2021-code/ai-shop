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

    /**
     * @param phoneTail 手机号后四位。**完整号码永远不出这个 Port**（B12）——
     *                  商家侧的顾客列表、履约台都只需要「认得出是谁」，不需要能打过去
     * @param avatar    头像 URL。顾客列表要用；它不是敏感信息，但也只在这里出现一次
     */
    record UserBrief(String userNo, String nickname, String phoneTail, String avatar) {
    }
}
