package ai.neargo.shop.spi.user;

import java.util.Optional;

/**
 * 其它域 → user：平台人档。
 *
 * <p><b>会员域拿不到手机号，只拿得到 {@code personNo} 与后四位</b> ——
 * 这是刻意的：手机号只在 {@code usr_person} 存一份密文，散到各域各表里的号码
 * 是最容易出事的那种数据。
 *
 * <p>「按手机号找人、找不到就建」写在这里而不是各域自己实现：
 * 两个域各建一份人档的话，同一个人在库里会有两个身份，而两边都不报错。
 */
public interface PersonPort {

    /**
     * 按手机号解析人档，没有就建一份（{@code userNo} 留空）。
     *
     * <p>商家在 B 端录入会员走的就是这条 —— 那时本人可能还没注册。
     *
     * @param phone 完整手机号
     */
    PersonView resolveOrCreateByPhone(String phone);

    /** 按账号找人档。微信登录且没授权手机号的人没有人档，返回空 */
    Optional<PersonView> findByUser(String userNo);

    Optional<PersonView> find(String personNo);

    /**
     * 人档的对外视图。
     *
     * @param phoneTail 后四位。<b>没有完整号</b> —— 需要完整号的场景只有平台申诉处置，
     *                  那条路要二次确认与审计日志，不走这个 Port
     * @param userNo    没绑账号时为空。会员据此判断这是不是「线索」
     */
    /**
     * 按手机号后四位找人档（运营端 P8）。
     *
     * <p><b>只接受恰好四位</b>，调用方负责校验。给前缀的话，运营端就成了
     * 一本可翻的全平台通讯录 —— 而运营的读权限比商家宽得多。
     *
     * <p>后四位当然会撞：这正是要的效果 —— 运营看到几个候选，
     * 再按别的线索（商家、下单时间）确认是哪一个，而不是直接拿到一个人。
     */
    java.util.List<String> findByPhoneTail(String phoneTail);

    /**
     * 解出这个人的<b>完整手机号</b>。解不开（记录建于密钥配置之前）返回 empty。
     *
     * <p>为什么这条要在 Port 上：调用方是 member 域的「查看完整号码」，
     * 而**解密密钥属于 user 域** —— 上一版 member 域直接注入了
     * {@code user.service.PhoneCrypto} 与 {@code UserMappers.PersonMapper}，
     * 两个域就此长在一起，而拦它的架构规则常年红着、没给任何信号。
     *
     * <p><b>本方法只负责解号码，不负责判「该不该看」</b>：理由校验与审计留在调用方 ——
     * 那是业务决定，不同调用方的门槛不一样。这里少写一句「顺手记审计」是有意的，
     * 否则两处都记就会在审计表里出现成对的重复记录。
     */
    java.util.Optional<String> revealPhone(String personNo);

    record PersonView(String personNo, String phoneTail, String userNo) {
    }
}
