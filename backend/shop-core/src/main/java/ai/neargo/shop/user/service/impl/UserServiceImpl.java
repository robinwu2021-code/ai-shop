package ai.neargo.shop.user.service.impl;

import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.spi.trade.OpenOrderPort;
import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.user.service.UserService;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.user.dto.UserVO;
import ai.neargo.shop.user.IdentityType;
import ai.neargo.shop.user.entity.UsrAccount;
import ai.neargo.shop.user.entity.UsrIdentity;
import ai.neargo.shop.spi.user.PickupQueryPort;
import ai.neargo.shop.user.mapper.UserMappers.IdentityMapper;
import ai.neargo.shop.user.mapper.UserMappers.UserMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserServiceImpl implements UserService {


    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(UserServiceImpl.class);

    /** 已注销。与 BANNED 分开：那是平台封的，这是他自己注销的，客服口径完全不同 */
    private static final String STATUS_DEREGISTERED = "DEREGISTERED";

    private final UserMapper userMapper;
    private final IdentityMapper identityMapper;
    private final PickupQueryPort pickupQueryPort;
    private final OtpStore otpStore;

    private final OpenOrderPort openOrderPort;
    private final TokenStore tokenStore;
    /** 分池之后踢人必须指明是哪个池，见 TokenStores 的类注释 */
    private final ai.neargo.shop.auth.TokenStores tokenStores;
    /** 注销要连人档一起解绑（P0）—— 与删凭证是同一件事的两半 */
    private final ai.neargo.shop.user.service.PersonService personService;

    public UserServiceImpl(UserMapper userMapper, IdentityMapper identityMapper,
                           PickupQueryPort pickupQueryPort, OtpStore otpStore,
                           OpenOrderPort openOrderPort, TokenStore tokenStore, ai.neargo.shop.auth.TokenStores tokenStores, 
                           ai.neargo.shop.user.service.PersonService personService) {
        this.identityMapper = identityMapper;
        this.userMapper = userMapper;
        this.pickupQueryPort = pickupQueryPort;
        this.otpStore = otpStore;
        this.openOrderPort = openOrderPort;
        this.tokenStore = tokenStore;
        this.tokenStores = tokenStores;
        this.personService = personService;
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public void deregister() {
        UsrAccount user = currentUser();

        /*
         * **有没走完的单就不许注销。**
         *
         * 注销之后手机号与 openid 都解绑了，没有任何人能再联系到他 ——
         * 而这时候货可能正在路上、款可能还没退。放行的损失落在两边：
         * 他收不到货也找不回入口，客服连人都对不上。
         *
         * 拦在这里而不是让他注销完再来找：那时候已经没有「他」了。
         */
        if (openOrderPort.hasOpenOrders(user.getUserNo())) {
            throw BizException.of(ErrorCode.DEREGISTER_HAS_OPEN_ORDERS);
        }

        /*
         * **解绑全部凭证。** 这一步才是「注销」的实质：
         * openid 解绑之后，同一个微信再进小程序会走「登录即注册」建一个**全新账号**，
         * 而不是回到这个已注销的壳里。
         *
         * 用删行而不是打标记：登录链路是按 (type, value) 查 usr_identity 的，
         * 留着行就必须在每一处查询都记得排除已注销的 —— 少一处就是「注销了还能登回去」。
         */
        identityMapper.deleteAllByUserPhysically(user.getUserNo());

        /*
         * 人档同样要解绑并让出手机号（P0）。
         * 不做的话，他用同一个号回来会撞人档的唯一键 —— 而那个错误长得像「系统开小差」，
         * 跟注销一点关系都看不出来。与上面删凭证是同一件事的两半。
         */
        personService.deregister(user.getUserNo());

        /*
         * **匿名化，不删行。** 订单、结算、发票有留存义务，
         * 删掉既违规，也会让对账与售后凭空断掉（那些记录挂在 userNo 上）。
         */
        /*
         * **必须用 UpdateWrapper 显式 set null，不能 setXxx(null) + updateById。**
         *
         * MyBatis-Plus 的 `updateById` 默认跳过 null 字段（字段策略 NOT_NULL）——
         * 那几行 `setOpenid(null)` 一个都不会进 SQL，于是「注销」之后
         * openid 与手机号原样留在库里：**该抹掉的个人信息一个没抹**，
         * 而接口返回成功、日志也正常。这条是被 DeregisterFlowTest 抓出来的。
         */
        userMapper.update(null, Wrappers.<UsrAccount>lambdaUpdate()
                .eq(UsrAccount::getUserNo, user.getUserNo())
                .set(UsrAccount::getNickname, "已注销用户")
                .set(UsrAccount::getAvatar, "")
                .set(UsrAccount::getPhone, null)
                .set(UsrAccount::getOpenid, null)
                .set(UsrAccount::getUnionid, null)
                .set(UsrAccount::getAppleSub, null)
                .set(UsrAccount::getStatus, STATUS_DEREGISTERED));

        /*
         * 踢掉所有在线会话：注销之后那些 token 不该还能用。
         *
         * **两个池都要踢** —— A7 之后同一个 user_no 可能在 MERCHANT 池里也有会话
         * （店主走 /biz/auth/login，主体就是 user_no）。只踢 C 端的话，
         * 注销完账号还能从 B 端继续经营，而注销流程一路返回成功。
         */
        tokenStores.of(ai.neargo.shop.auth.Realm.CONSUMER).revokeUser(user.getUserNo());
        tokenStores.of(ai.neargo.shop.auth.Realm.MERCHANT).revokeUser(user.getUserNo());
        log.info("[用户] 账号已注销 userNo={}", user.getUserNo());
    }

    @Override
    public UserVO profile() {
        return UserVO.of(currentUser());
    }

    @Override
    public UserVO profileOrNull() {
        String userNo = SecurityUtils.currentUserNoOrNull();
        if (userNo == null || userNo.isBlank()) {
            return null;
        }
        UsrAccount user = userMapper.selectOne(Wrappers.<UsrAccount>lambdaQuery()
                .eq(UsrAccount::getUserNo, userNo).last("limit 1"));
        return user == null ? null : UserVO.of(user);
    }

    @Override
    @Transactional
    public UserVO bindCommunity(String communityNo, String pickupNo) {
        // 校验「自提点属于该社区」而不是只查存在性：端上可以随便传两个不相干的号，
        // 存进去之后商品池按社区取、履约按自提点走，用户会看到一个永远到不了货的组合
        if (pickupQueryPort.find(pickupNo)
                .filter(p -> communityNo.equals(p.communityNo())).isEmpty()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }

        UsrAccount user = currentUser();
        user.setCommunityNo(communityNo);
        user.setPickupNo(pickupNo);
        userMapper.updateById(user);
        return UserVO.of(user);
    }

    @Override
    @Transactional
    public UserVO updateProfile(String nickname, String avatar) {
        UsrAccount user = currentUser();
        // 传 null 表示「不改这个字段」，而不是「清空」——端上只提交改动的那个
        if (nickname != null && !nickname.isBlank()) {
            user.setNickname(nickname);
        }
        if (avatar != null) {
            user.setAvatar(avatar);
        }
        userMapper.updateById(user);
        return UserVO.of(user);
    }

    @Override
    @Transactional
    public UserVO bindPhone(String phone, String code) {
        if (!otpStore.verifyAndConsume(phone, code)) {
            /*
             * **OTP_INVALID，不是 BAD_REQUEST**（2026-09-04 修）。
             *
             * 这一处此前抛 BAD_REQUEST —— 端上看到的是「请求参数有误」，
             * 而真实原因是「验证码不对或已过期」。两句话把人带到完全不同的地方：
             * 一个去查表单字段，一个去重新获取验证码。
             * 实测有人卡在这里：短信确实发出去了（sys_notify_log 有 SENT），
             * 码过了 5 分钟 TTL，而屏幕上说的是「参数错误」。
             *
             * 登录（AuthServiceImpl）与商家员工（MerchantStaffServiceImpl）
             * 两条路一直用的就是 OTP_INVALID —— **只有绑定这一条漏了**。
             * 正是本仓库自己警告过的「少一个入口、少一条分支」
             * （见 bindPhoneTrusted 的注释）。
             */
            throw BizException.of(ErrorCode.OTP_INVALID);
        }
        return attachPhone(phone);
    }

    /**
     * 绑定一个<b>已经被验证过</b>的手机号（微信手机号快速验证给的）。
     *
     * <p>与 {@link #bindPhone} 的差别只有前半段：那条自己验验证码，这条信任调用方 ——
     * 号码是微信用 {@code phonenumber.getPhoneNumber} 换出来的，端上碰不到。
     * <b>后半段必须共用</b>：冲突检测、幂等、双写旧列三件事在两条路上一字不差，
     * 各写一遍的话迟早只在一条路上修 bug（这个仓库有前科：「少一个入口、少一条分支」）。
     */
    @Override
    public UserVO bindPhoneTrusted(String phone) {
        if (phone == null || phone.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        return attachPhone(phone);
    }

    /**
     * <b>发验证码之前问一句：这个号是不是已经绑在你自己账号上了。</b>
     *
     * <h2>为什么只拦「自己的号」</h2>
     * 已经绑好的号再发一次码，用户走完一整套流程只会得到「你已经绑过了」，
     * 那条短信是白发的，而中间那两分钟他不知道自己在等什么。
     *
     * <p><b>号码属于别人时必须照常发。</b>验证码是用户<b>自证这个号是他的</b>
     * 的唯一手段 —— 不发就等于「号一旦落在别的账号上，本人永远拿不回来」，
     * 而那正是今天要修的问题本身。
     *
     * <p>而且「发不发」这个差别会变成一个<b>枚举预言机</b>：
     * 任何人都能靠它免费问出「这个号在你们这儿注册过没有」。
     * 属于别人与全新号码在这一步必须<b>表现完全一致</b>。
     */
    @Override
    public void assertPhoneSendable(String phone) {
        if (phone == null || phone.isBlank()) {
            return;   // 格式由 @Valid 管，这里只管归属
        }
        String me = SecurityUtils.currentUserNoOrNull();
        if (me == null) {
            return;
        }
        UsrIdentity owner = identityMapper.selectOne(Wrappers.<UsrIdentity>lambdaQuery()
                .eq(UsrIdentity::getIdentityType, IdentityType.PHONE)
                .eq(UsrIdentity::getIdentityValue, phone).last("limit 1"));
        if (owner != null && owner.getUserNo().equals(me)) {
            throw BizException.of(ErrorCode.PHONE_ALREADY_BOUND);
        }
    }

    private UserVO attachPhone(String phone) {
        UsrAccount user = currentUser();

        /*
         * 该手机号已属于另一个账号：这是账号合并问题，一期不做自动合并 ——
         * 自动合并要迁移两边的订单、积分、卡包、优惠券、地址，横跨五个域，错一步就是资损
         * （安全整改方案 §6.7）。一期只做「检测 + 阻止 + 留痕」。
         *
         * 查的是 usr_identity 而不是 usr_account.phone：手机号是**唯一权威标识**（S2），
         * 权威表是凭证表。只查旧列的话，一个从 App 注册、手机号只登记在 usr_identity 上
         * 的账号会被漏掉，于是两个人拿到同一个手机号——正是这条检测要防的事。
         */
        UsrIdentity owner = identityMapper.selectOne(Wrappers.<UsrIdentity>lambdaQuery()
                .eq(UsrIdentity::getIdentityType, IdentityType.PHONE)
                .eq(UsrIdentity::getIdentityValue, phone).last("limit 1"));
        if (owner != null) {
            if (!owner.getUserNo().equals(user.getUserNo())) {
                /*
                 * **接管（把 openid 挂到已有账号上）已写好但未接线**，见下方
                 * adoptOrReject 的注释。不接线的理由有两条，都要留在这儿：
                 *
                 * ① 它与两条 ★★★ 守卫直接冲突
                 *    （PhoneBindFlowTest / IdentityUnificationFlowTest），
                 *    那两条钉的是「一期不自动合并」。推翻它们是产品决定，不是实现细节。
                 * ② 我自己的边界还不够严：只查了「未完成订单」——
                 *    一个有**已完成订单**的空壳仍会被并掉，而那是真的丢东西。
                 *    要接线，先把判据换成「一笔订单都没有」。
                 */
                throw BizException.of(ErrorCode.CONFLICT);
            }
            return UserVO.of(user);   // 已经绑过同一个号，幂等返回
        }

        UsrIdentity row = new UsrIdentity();
        row.setUserNo(user.getUserNo());
        row.setIdentityType(IdentityType.PHONE);
        row.setIdentityValue(phone);
        row.setVerifiedAt(java.time.LocalDateTime.now());
        identityMapper.insert(row);

        // 过渡期双写旧列（V3 只加不删）。确认两边一致后随删列一起去掉
        user.setPhone(phone);
        userMapper.updateById(user);
        return UserVO.of(user);
    }

    /**
     * 手机号属于另一个账号时怎么办。
     *
     * <h2>方向是反的：把 openid 挂过去，不是把手机号挂过来</h2>
     * 代码里早就写着「<b>手机号是唯一权威标识，权威表是凭证表</b>」。
     * 既然如此，认出「同一个人」之后该动的是**这次新建的那个壳**，
     * 而不是那个已经有历史的账号。
     *
     * <p>把手机号挂到当前账号上是<b>绝不能做</b>的：库里会出现两行同样的手机号凭证，
     * 而按手机号找账号的地方（B 端登录）是 {@code limit 1} 取一行 ——
     * 一个商家的登录会随机解析到一个没有 {@code mch_account} 的账号，
     * <b>他就登不进自己的后台了</b>。2026-09-04 实测遇到过：
     * 那个号正是一个商家账号的登录号。
     *
     * <h2>接管的边界：只接管「什么都还没有的壳」</h2>
     * 「跨五个域的账号合并」那条顾虑针对的是订单、积分、卡包、优惠券 ——
     * 那些确实不能自动搬。而<b>这里要搬的只有一行 openid 凭证</b>，
     * 前提是当前账号本来就什么都没有：
     * <ul>
     *   <li>只有 openid 类凭证（没有 PHONE、没有 PASSWORD）—— 说明它是静默登录建的壳；</li>
     *   <li>没有未完成订单 —— 有订单就意味着有钱的痕迹，那条路留给人工。</li>
     * </ul>
     * 任何一条不满足，仍然 {@code CONFLICT}，与此前的行为一致。
     *
     * <h2>会话怎么切</h2>
     * <b>不在这里发新令牌</b>，而是把当前账号作废，让端上重新走一次静默登录 ——
     * 那条路已经在跑、已经被测过。为了一个分支新开一条发令牌的口，
     * 是在鉴权链上多开一个入口，而这条链上多一个入口就多一处要证明的事。
     */
    private UserVO adoptOrReject(UsrAccount current, String targetUserNo) {
        List<UsrIdentity> mine = identityMapper.selectList(Wrappers.<UsrIdentity>lambdaQuery()
                .eq(UsrIdentity::getUserNo, current.getUserNo()));
        boolean onlyOpenid = !mine.isEmpty() && mine.stream()
                .allMatch(i -> i.getIdentityType() != null && i.getIdentityType().startsWith("WX_OPENID"));
        if (!onlyOpenid || openOrderPort.hasOpenOrders(current.getUserNo())) {
            /*
             * 当前账号已经是个「有内容的人」了 —— 这才是那条「不自动合并」真正针对的情形。
             * 留痕之后转人工，不猜。
             */
            log.warn("[bind-phone] 手机号属于 {}，而当前账号 {} 已有凭证或订单，不接管",
                    targetUserNo, current.getUserNo());
            throw BizException.of(ErrorCode.CONFLICT);
        }

        // ① openid 凭证挪到目标账号
        for (UsrIdentity i : mine) {
            i.setUserNo(targetUserNo);
            identityMapper.updateById(i);
        }
        // ② 过渡期双写的旧列：先腾位置再补，uk_openid 是唯一键，反过来会撞
        UsrAccount target = userMapper.selectOne(Wrappers.<UsrAccount>lambdaQuery()
                .eq(UsrAccount::getUserNo, targetUserNo).last("limit 1"));
        String openid = current.getOpenid();
        /*
         * **必须用显式 set(null) 清空，不能 setOpenid(null) + updateById。**
         * MyBatis-Plus 的 updateById 跳过 null 字段 —— 那句 set 根本不会生成，
         * 旧列还留着同一个 openid，紧接着给目标账号设同一个值就撞 uk_openid，
         * 整个绑定以 10500 结束。这个坑本仓库记过一次，我又踩了一次。
         */
        userMapper.update(null, Wrappers.<UsrAccount>lambdaUpdate()
                .set(UsrAccount::getOpenid, null)
                .set(UsrAccount::getDeleted, 1)
                .eq(UsrAccount::getUserNo, current.getUserNo()));
        if (target != null && openid != null && !openid.isBlank()) {
            target.setOpenid(openid);
            userMapper.updateById(target);
        }
        log.info("[bind-phone] 空壳账号 {} 已并入 {} —— 端上要重新登录一次", 
                current.getUserNo(), targetUserNo);
        /*
         * **返回目标账号。** 端上会看到 userNo 变了，据此重新静默登录 ——
         * 而且当前令牌指向的账号已经作废，下一次调用本来也会 401。
         */
        return target != null ? UserVO.of(target) : UserVO.of(current);
    }

    private UsrAccount currentUser() {
        String userNo = SecurityUtils.currentUserNo();
        UsrAccount user = userMapper.selectOne(Wrappers.<UsrAccount>lambdaQuery()
                .eq(UsrAccount::getUserNo, userNo).last("limit 1"));
        if (user == null) {
            // 会话有效但用户没了（被硬删或换库）：当作未登录，让端上清 token 重登
            throw new ai.neargo.shop.common.GlobalExceptionHandler.UnauthorizedException();
        }
        return user;
    }
}
