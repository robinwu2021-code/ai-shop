package ai.neargo.shop.member.service;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.member.dto.MemberVOs.MemberDetailVO;
import ai.neargo.shop.member.dto.MemberVOs.MemberQuery;
import ai.neargo.shop.member.dto.MemberVOs.MemberStatsVO;
import ai.neargo.shop.member.dto.MemberVOs.MemberVO;
import ai.neargo.shop.member.entity.MbrMember;

import java.util.Optional;

/**
 * 会员：一个人与一家商家的关系。
 *
 * <p>三条写入路径对应真实世界的三件事：<b>他买了东西</b>（{@link #onOrderPaid}）、
 * <b>商家把他录进来了</b>（P2 的 {@code enroll}）、<b>他自己点了加入</b>（P1 的 {@link #join}）。
 * 读出去的只有三样：名单、统计、一个人的详情。
 */
public interface MemberService {

    /**
     * 支付成功：入会并累加指标。<b>幂等</b> —— 支付回调会重发。
     *
     * <p>没有人档（微信登录未授权手机号）时**什么都不做**：
     * 会员必须有已验证手机号是准入规则，而交易不该为此被挡住 ——
     * 他照常买到东西，只是这一单不计进任何人的会员名单。
     */
    void onOrderPaid(String subOrderNo, String userNo, String personNo,
                     String entityNo, String storeNo, long amountMinor, long paidAt);

    /** 主动加入（C 端店铺页那个按钮）。没有人档时抛 {@code MEMBER_PHONE_REQUIRED} */
    MbrMember join(String entityNo, String personNo, String storeNo);

    PageData<MemberVO> list(String entityNo, MemberQuery q);

    MemberStatsVO stats(String entityNo, String storeNo);

    Optional<MemberDetailVO> detail(String entityNo, String memberNo);

    /** 这个人在这家店是不是会员（C 端店铺页那张卡、营销的受众判断都读它） */
    Optional<MbrMember> find(String entityNo, String personNo);
}
