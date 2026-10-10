package ai.neargo.shop.member.service;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.member.dto.MemberVOs.OpsMemberVO;
import ai.neargo.shop.member.dto.MemberVOs.OpsPersonVO;
import ai.neargo.shop.member.dto.MemberVOs.ReachStatVO;

import java.util.List;

/**
 * 运营侧的会员与人档（P8）。
 *
 * <p><b>与商家侧的差别只有一条：跨商家</b>。字段、脱敏口径完全一样 ——
 * 运营看得到「谁是谁家的会员」，但<b>看不到完整手机号</b>。
 * 需要完整号的只有申诉处置一条路，单独的权限码 + 每次留痕。
 */
public interface OpsMemberService {

    /**
     * 跨商家会员名单。
     *
     * @param entityNo 空 = 全平台
     * @param phoneTail 按后四位找人。<b>只接受四位</b> ——
     *                  给前缀就等于把全平台会员库变成一本可翻的通讯录
     */
    PageData<OpsMemberVO> members(String entityNo, String phoneTail, long page, long size);

    /** 人档详情：名下会员关系、绑没绑账号、合并历史 */
    OpsPersonVO person(String personNo);

    /**
     * 看完整手机号。<b>每次都写审计</b> ——
     * 这是唯一能把后四位还原成真实号码的地方，谁看了谁的号必须留得下来。
     *
     * @param reason 必填。看别人的手机号要说得出为什么
     */
    String revealPhone(String personNo, String reason, String operatorNo);

    /** 触达量与退订率，按商家排。<b>退订率是这条线唯一的健康指标</b> */
    List<ReachStatVO> reachStats(int days);
}
