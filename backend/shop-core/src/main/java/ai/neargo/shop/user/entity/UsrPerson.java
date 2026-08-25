package ai.neargo.shop.user.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 平台人档：<b>这个自然人</b>，不要求他注册过。
 *
 * <p><b>为什么不用 {@code UsrAccount} 或 {@code UsrIdentity}</b>：那两张表都要先有账号，
 * 而商家在 B 端录进来的手机号，本人可能还没在平台出现过。会员是「某个自然人 × 某家商家」
 * 的关系 —— 关系要挂在人身上，人却还不存在，这就是缺口。
 *
 * <p><b>以已验证的手机号为准</b>（{@code phone_hash} 非空）。会员必须有手机号这条准入规则，
 * 把「线索转正要合并两行」从常规路径降级成了罕见异常：商家先录了号、他后来才注册时，
 * 两边指向的从头到尾是同一份人档，转正只是给这一份补一个 {@code userNo}。
 *
 * <p><b>手机号只在这里存一份</b>：匹配用不可逆的 {@code phoneHash}，原文加密进 {@code phoneEnc}，
 * 展示用 {@code phoneTail}。商家侧永远只拿得到后四位 —— 散在各商家表里的手机号，
 * 是最容易出事的那种数据。
 */
@Getter
@Setter
@TableName("usr_person")
public class UsrPerson extends BaseEntity {

    public static final String ACTIVE = "ACTIVE";
    /** 已并入别的人档。<b>保留不删</b> —— 会员关系与合并日志还引用着它 */
    public static final String MERGED = "MERGED";

    private String personNo;

    /** 手机号哈希。唯一键，用来判断「是不是同一个人」 */
    private String phoneHash;

    /** 手机号密文。只有平台在申诉处置时解密查看，且要二次确认 + 审计日志 */
    private String phoneEnc;

    /** 后四位。商家侧展示用 —— 每次都去解密只为显示四个字符，不值当 */
    private String phoneTail;

    /**
     * 绑定的账号。<b>可空</b>：人先于账号存在。
     *
     * <p>他注册/登录那一刻按 {@link #phoneHash} 找到这份人档、把账号写上来，
     * 名下所有线索会员随之转正 —— 一次绑定，几家商家的会员同时生效。
     */
    private String userNo;

    private String mergedInto;

    private String status;
}
