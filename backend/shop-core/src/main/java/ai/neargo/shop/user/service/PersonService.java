package ai.neargo.shop.user.service;

import ai.neargo.shop.user.entity.UsrPerson;

import java.util.Optional;

/**
 * 平台人档：这个自然人。
 *
 * <p>三条入口对应真实世界的三件事：<b>商家录了一个号</b>（{@link #resolveOrCreateByPhone}）、
 * <b>他登录了</b>（{@link #bindOnLogin}）、<b>两份人档指的是同一个人</b>（{@link #merge}）。
 */
public interface PersonService {

    /** 按手机号解析，没有就建（{@code userNo} 留空）。商家录入会员走它 */
    UsrPerson resolveOrCreateByPhone(String phone);

    Optional<UsrPerson> findByUser(String userNo);

    Optional<UsrPerson> find(String personNo);

    /**
     * 按手机号后四位找人档（运营端 P8）。
     *
     * <p>后四位会撞，这是有意的：运营看到几个候选再按别的线索确认，
     * 而不是输四位就直接拿到一个人。
     */
    java.util.List<String> findByPhoneTail(String phoneTail);

    /**
     * 登录成功之后把账号绑到人档上。<b>三种情况，两种一步到位</b>：
     *
     * <ul>
     *   <li>A 这个号还没有人档 → 建一份、绑上</li>
     *   <li>B 有人档、没绑过账号（商家早就录过他）→ 直接绑。<b>不需要合并任何会员关系</b>，
     *       那些关系本来就挂在这份人档上</li>
     *   <li>C 有人档、绑着<b>另一个</b>账号 → <b>跳过并告警，不抛</b>。
     *       允许自动改绑等于「知道你手机号就能把你的账号并过来」，所以不能绑；
     *       但也不能因此把人挡在登录之外 —— 见下</li>
     * </ul>
     *
     * <p><b>登录时的绑定是隐式的副作用，所以它永不阻塞登录。</b>
     * 用户此刻要的是进门，不是绑手机号；为一个他没主动发起的动作把他关在外面，
     * 是拿会员关系的完整性去换可用性 —— 而人档补不上明天再补，登录挡住是事故。
     *
     * <p>需要让用户知道冲突的是<b>显式</b>绑定（他自己点「绑定手机号」），
     * 那条路用 {@link #bindPhone}，会抛 {@code PERSON_PHONE_TAKEN}。
     *
     * <p>⚠️ 这不是理论情况：{@code uk_identity} 的唯一键是
     * <b>(identity_type, identity_value)</b> 而不是手机号单列 ——
     * 同一个号落在两种 type 下（例如 B 端账号日后走另一种凭证类型）就会撞上这里。
     *
     * @return 绑好的人档；该账号没有手机号（微信登录未授权）时返回空
     */
    Optional<UsrPerson> bindOnLogin(String userNo, String phone);

    /**
     * 显式绑定手机号（用户自己点「绑定」）。与 {@link #bindOnLogin} 的差别只有一处：
     * <b>冲突时抛 {@code PERSON_PHONE_TAKEN}</b> —— 他主动发起的动作，就该告诉他为什么没成。
     */
    Optional<UsrPerson> bindPhone(String userNo, String phone);

    /**
     * 账号注销时：人档**解绑账号并让出手机号**。
     *
     * <p>与「同一个微信注销后拿到一个全新账号」这条既有语义保持一致 ——
     * 他日后用同一个号回来，会拿到**一份新的人档**，而不是回到这个已注销的壳里。
     * 让出手机号哈希是这一步的实质：不让的话唯一键会挡住他重新注册，
     * 而报错是「系统开小差」，跟注销一点关系都看不出来（这条被用例抓出来过）。
     *
     * <p>会员关系仍指向这份旧人档，商家侧看到的是「已注销」，且不可触达 ——
     * 商家的历史成交记录不该因为对方注销而凭空消失，但也不该还能被找上门。
     */
    void deregister(String userNo);

    /**
     * 把一份人档并进另一份。<b>不可逆</b>，落 {@code usr_person_merge_log}。
     *
     * <p>只在两种情况下发生：换号撞上别人的线索档、运营在申诉里人工处置。
     * 会员关系的改指由会员域订阅事件处理 —— user 域不认识会员表。
     *
     * @param affectedMembers 调用方算好的受影响会员数，只为留痕
     */
    void merge(String fromPersonNo, String toPersonNo, String reason,
               int affectedMembers, String operatorNo);
}
