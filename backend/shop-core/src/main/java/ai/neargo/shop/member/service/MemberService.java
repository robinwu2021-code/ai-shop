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

    /**
     * 商家手工录入一个手机号（P2）。
     *
     * <p>本人还没在平台出现过时，这条记为 <b>{@code LEAD} 线索</b>：
     * <b>不可触达、不进任何受众</b> —— 录入手机号不等于拿到推送许可。
     *
     * <p><b>已存在就返回那一条并把备注与标签并进去，不报错</b>：
     * 店员重复录入是常态，报错只会让他再录一次。
     */
    MbrMember enroll(String entityNo, String phone, String remark, java.util.List<String> tagNos,
                     String storeNo, String operatorNo);

    /**
     * 这份人档绑定账号了 —— 把它名下所有线索会员转正。
     *
     * <p><b>一次绑定，几家商家的会员同时生效</b>。这正是「会员必须有已验证手机号」
     * 那条准入规则换来的：不需要合并任何东西，只是把 status 从 LEAD 改成 ACTIVE。
     *
     * @return 转正了几条
     */
    int claimByPerson(String personNo);

    /** 改备注 / 拉黑与恢复。拉黑的人仍在名单里（历史成交是事实），但不再进可发放的人群 */
    MbrMember patch(String entityNo, String memberNo, String remark, String status);

    PageData<MemberVO> list(String entityNo, MemberQuery q);

    MemberStatsVO stats(String entityNo, String storeNo);

    Optional<MemberDetailVO> detail(String entityNo, String memberNo);

    /** 这个人在这家店是不是会员（C 端店铺页那张卡、营销的受众判断都读它） */
    Optional<MbrMember> find(String entityNo, String personNo);

    /** 这个主体的会员经营口径。没配过就是按主体 —— 多数商家只有一家店，那也是对的默认 */
    ai.neargo.shop.member.dto.MemberVOs.MemberSettingVO settings(String entityNo);

    /**
     * 改经营口径。
     *
     * <p><b>只改展示与分层口径，不改存储</b>：主体级与门店级两份指标一直都在算，
     * 所以商家随时可以切、切回来也不丢。界面上必须写这句，否则没人敢点。
     */
    ai.neargo.shop.member.dto.MemberVOs.MemberSettingVO saveSettings(
            String entityNo, String memberScope, Boolean autoJoinOnOrder);

    /**
     * 我是哪几家店的会员，以及每家的消息开关（C 端，P7）。
     *
     * <p>顾客要能一眼看到「谁在给我发消息」——**这是退订入口的前提**：
     * 不知道自己是谁的会员，就无从关掉它。
     */
    java.util.List<ai.neargo.shop.member.dto.MemberVOs.MyMembershipVO> myMemberships(String userNo);

    /**
     * 买家自己关掉/打开某一家店的消息（C 端）。
     *
     * <p><b>按 userNo + entityNo 定位，不接受端上传来的 memberNo</b> ——
     * 会员号可猜，收下就等于「谁都能替别人退订」。
     */
    void setReachOptOutByUser(String userNo, String entityNo, boolean optOut);

    /**
     * 买家自己关掉/打开这家店的消息（C 端会员卡上的开关，P7）。
     *
     * <p><b>只有本人能改</b> —— 商家不能替顾客「重新订阅」。
     * 退订这件事一旦可以被别人撤销，它就不再是承诺。
     */
    void setReachOptOut(String entityNo, String memberNo, boolean optOut);

    /** 按条件筛出会员号。人群与发放共用它 —— 三处各写一遍会算出三个数 */
    java.util.List<String> match(String entityNo,
                                 ai.neargo.shop.member.dto.MemberVOs.MemberQuery q);

    /**
     * 同 {@link #match}，但只留<b>能真正收到东西</b>的人。
     *
     * <p>发券与触达一律走这条：线索会员（商家手录的号，本人还没在平台出现）
     * 与退订的人进不了受众 —— <b>录入手机号不等于拿到推送许可</b>。
     */
    java.util.List<String> matchReachable(String entityNo,
                                          ai.neargo.shop.member.dto.MemberVOs.MemberQuery q);
}
