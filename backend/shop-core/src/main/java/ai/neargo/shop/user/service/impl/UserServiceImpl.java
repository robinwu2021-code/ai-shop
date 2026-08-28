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
            throw BizException.of(ErrorCode.BAD_REQUEST);
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
