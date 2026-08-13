package ai.neargo.shop.user.service.impl;

import ai.neargo.shop.user.service.AuthService;
import ai.neargo.shop.common.OtpStore;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.user.dto.UserVO;
import ai.neargo.shop.user.IdentityType;
import ai.neargo.shop.user.entity.UsrAccount;
import ai.neargo.shop.user.entity.UsrIdentity;
import ai.neargo.shop.user.mapper.UserMappers.IdentityMapper;
import ai.neargo.shop.user.mapper.UserMappers.UserMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 登录建户实现。
 *
 * <p><b>身份统一（S2）</b>：凭证不再平铺在 {@code usr_account} 的列上，而是
 * {@code usr_identity} 一人多条。登录时把本次能拿到的<b>全部</b>凭证按强度依次去找人
 * （手机号优先），命中后把新出现的凭证补登到同一个人名下——识别能力越用越强。
 *
 * <p>旧结构的问题不是不够优雅，是**存不下事实**：单列唯一键意味着一个账号只能有一个
 * openid，而微信 openid 按应用隔离，同一个人在小程序和 App 里是两个值。
 *
 * <p><b>S1 的边界</b>：微信 {@code code2Session} 与 Apple 的 identityToken 校验都还没接
 * （需要小程序 appid/secret 与真机联调），当前按「principal 即稳定标识」建户。
 * 接真渠道时改的是 {@link #resolveCredentials} 一个方法——届时小程序一次
 * {@code wx.login} + {@code getPhoneNumber} 会同时给出 openid、unionid 和手机号，
 * 三条凭证一起登记，建户主干与会话发放不动。
 * 这一点在此显式写明，避免有人误以为微信登录已经通了。
 */
@Service
public class AuthServiceImpl implements AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthServiceImpl.class);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserMapper userMapper;
    private final IdentityMapper identityMapper;
    private final TokenStore tokenStore;
    private final OtpStore otpStore;

    /**
     * 固定验证码（**只给本地联调与 E2E**）。
     *
     * <p>为什么需要它：进程外的测试拿不到随机码 —— 日志抓码不可靠（缓冲、轮转、并发混叠），
     * 而「登录」是每一条旅程的第一步，拿不到码等于一条都跑不了。
     *
     * <p><b>三条护栏</b>：
     * <ul>
     *   <li>默认空 = 真随机，什么都不打开</li>
     *   <li>一旦设了值，启动时打 WARN —— 它必须在日志里显眼到不可能被带上生产</li>
     *   <li>它不绕过校验，只是让码可预测：验证码该过期还是过期、该消费还是消费</li>
     * </ul>
     */
    private final String fixedOtp;

    private final ai.neargo.shop.common.ratelimit.OtpSendGuard sendGuard;
    private final ai.neargo.shop.spi.notify.SmsPort smsPort;

    public AuthServiceImpl(UserMapper userMapper, IdentityMapper identityMapper,
                           TokenStore tokenStore, OtpStore otpStore,
                           ai.neargo.shop.common.ratelimit.OtpSendGuard sendGuard,
                           ai.neargo.shop.spi.notify.SmsPort smsPort,
                           @org.springframework.beans.factory.annotation.Value(
                                   "${shop.auth.otp.fixed:}") String fixedOtp) {
        this.userMapper = userMapper;
        this.identityMapper = identityMapper;
        this.tokenStore = tokenStore;
        this.otpStore = otpStore;
        this.sendGuard = sendGuard;
        this.smsPort = smsPort;
        this.fixedOtp = fixedOtp;
        if (fixedOtp != null && !fixedOtp.isBlank()) {
            log.warn("[DANGEROUS] shop.auth.otp.fixed 已开启（{}）—— "
                    + "任何人都能用这个码登录任意手机号。**生产环境绝不能出现这条日志**", fixedOtp);
        }
    }

    @Override
    public void sendOtp(String phone) {
        /*
         * **闸在生成码之前**：放在之后的话，被拒的那次仍然会把上一条有效码冲掉 ——
         * 用户手里那条还没用的码突然失效，而他看到的是「操作太频繁」，
         * 两件事对不上，只会让他再点一次。
         */
        sendGuard.check(phone);

        String code = fixedOtp == null || fixedOtp.isBlank()
                ? "%06d".formatted(RANDOM.nextInt(1_000_000))
                : fixedOtp;
        otpStore.save(phone, code);
        smsPort.sendOtp(phone, code);
    }

    @Override
    public LoginResult refresh(String currentToken) {
        var session = tokenStore.get(currentToken)
                .orElseThrow(ai.neargo.shop.common.GlobalExceptionHandler.UnauthorizedException::new);
        UsrAccount user = userMapper.selectOne(Wrappers.<UsrAccount>lambdaQuery()
                .eq(UsrAccount::getUserNo, session.user().userNo()).last("limit 1"));
        if (user == null) {
            throw new ai.neargo.shop.common.GlobalExceptionHandler.UnauthorizedException();
        }
        String fresh = tokenStore.issue(TokenStore.SessionData.of(
                LoginUser.consumer(user.getUserNo(), user.getNickname())));
        tokenStore.revoke(currentToken);   // 轮换：旧的立即作废
        return new LoginResult(fresh, UserVO.of(user));
    }

    @Override
    public void logout(String currentToken) {
        tokenStore.revoke(currentToken);
    }

    @Override
    @Transactional
    public LoginResult login(LoginCommand cmd) {
        List<Credential> credentials = resolveCredentials(cmd);
        UsrAccount user = findOrCreate(credentials, cmd);

        if ("BANNED".equals(user.getStatus())) {
            throw BizException.of(ErrorCode.RISK_BLOCKED);
        }

        String token = tokenStore.issue(TokenStore.SessionData.of(
                LoginUser.consumer(user.getUserNo(), user.getNickname())));
        return new LoginResult(token, UserVO.of(user));
    }

    /**
     * 授权凭据 → <b>本次能拿到的全部凭证</b>。三种登录方式的差异只在这一个方法里。
     *
     * <p>返回列表而不是单个：这是与旧实现最本质的区别。旧版每次只拿一个凭证、
     * 只按那一个查，于是同一个人换个入口就变成新账号。
     */
    private List<Credential> resolveCredentials(LoginCommand cmd) {
        return switch (cmd.grantType() == null ? "" : cmd.grantType()) {
            case GRANT_WECHAT_MP ->
                /*
                 * TODO(S4) 接 code2Session：cmd.principal() 是 wx.login 的 code，
                 * 换回 openid 与 unionid；再配合 getPhoneNumber 拿到手机号。
                 * 届时这里返回三条凭证，一次授权就完成识别——这正是小程序侧被推荐为
                 * 「最优路径」的原因（安全整改方案 §6.5）。
                 */
                    List.of(new Credential(IdentityType.WX_OPENID_MP, cmd.principal(), "MP"));
            case GRANT_PHONE_OTP -> {
                verifyOtp(cmd.principal(), cmd.credential());
                yield List.of(new Credential(IdentityType.PHONE, cmd.principal(), null));
            }
            case GRANT_APPLE ->
                // TODO(S4) 校验 identityToken 签名后取 sub。
                // Apple 永远给不出手机号，所以真接通后这里之后要接一道「强制绑定手机号」
                    List.of(new Credential(IdentityType.APPLE_SUB, cmd.principal(), "APP"));
            default -> throw BizException.of(ErrorCode.BAD_REQUEST);
        };
    }

    private void verifyOtp(String phone, String code) {
        if (!otpStore.verifyAndConsume(phone, code)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }

    /**
     * 按识别强度依次找人；找不到才建户；无论哪种，都把本次新出现的凭证登记上去。
     */
    private UsrAccount findOrCreate(List<Credential> credentials, LoginCommand cmd) {
        String userNo = resolveUserNo(credentials);

        UsrAccount user;
        if (userNo != null) {
            user = userMapper.selectOne(Wrappers.<UsrAccount>lambdaQuery()
                    .eq(UsrAccount::getUserNo, userNo).last("limit 1"));
            if (user == null) {
                /*
                 * 凭证指向一个不存在的人。正常不会发生（外键关系由 user_no 维系），
                 * 但一旦发生，**不能静默建新号** —— 那会让一个人的历史订单彻底失联，
                 * 且没有任何报错。宁可让这次登录失败，让人来查。
                 */
                log.error("凭证 {} 指向不存在的用户 {}，登录中止", credentials.getFirst().type(), userNo);
                throw BizException.of(ErrorCode.INTERNAL_ERROR);
            }
            // 从店铺码进来的老用户：刷新常去店，归因由 marketing 域按优先级裁决（S5）
            if (cmd.merchantNo() != null && !cmd.merchantNo().isBlank()) {
                user.setEntityNo(cmd.merchantNo());
                userMapper.updateById(user);
            }
        } else {
            user = createAccount(cmd);
        }

        registerNewCredentials(user, credentials);
        return user;
    }

    /**
     * 按 {@link IdentityType#RESOLVE_ORDER} 依次查，命中即认定。
     *
     * <p><b>这段的多凭证分支目前没有测试覆盖，也覆盖不了</b>：三种 grant 各自只产出
     * 一条凭证（{@code code2Session} 未接），所以这个循环眼下与「取唯一那条」等价——
     * 把它改成 {@code credentials.getFirst()}，全部身份用例仍然全绿（已实测）。
     *
     * <p>不因此简化成单条，是因为一旦 S4 接通小程序，一次
     * {@code wx.login} + {@code getPhoneNumber} 会同时给出 openid、unionid、手机号，
     * 顺序立刻变成实质规则：手机号命中就是同一个人，openid 命中只是同一应用的回访。
     * 那时补上多凭证用例，这里不用改。
     *
     * @return 命中的 user_no；全部未命中返回 {@code null}
     */
    private String resolveUserNo(List<Credential> credentials) {
        for (String type : IdentityType.RESOLVE_ORDER) {
            for (Credential c : credentials) {
                if (!type.equals(c.type())) {
                    continue;
                }
                UsrIdentity hit = findIdentity(c);
                if (hit != null) {
                    return hit.getUserNo();
                }
            }
        }
        return null;
    }

    /**
     * 补登本次新出现的凭证。
     *
     * <p>典型场景：小程序老用户首次在 App 登录，命中手机号认出是同一个人，
     * 于是把 App 的 openid 补一行挂到他名下。下次他从 App 静默登录就能直接认出。
     *
     * <p><b>冲突检测</b>：若某条凭证已属于另一个人，<b>不自动合并</b>。合并要迁移订单、
     * 积分、卡包、优惠券、地址，横跨五个域，合错了难回滚（安全整改方案 §6.7）。
     * 一期只做「检测 + 阻止 + 留痕」，真正的合并流程等有实际需求再建——
     * 但检测必须有，否则冲突会以「手机号已被占用」这种让用户莫名其妙的形式暴露。
     */
    private void registerNewCredentials(UsrAccount user, List<Credential> credentials) {
        for (Credential c : credentials) {
            UsrIdentity existing = findIdentity(c);
            if (existing != null) {
                if (!existing.getUserNo().equals(user.getUserNo())) {
                    log.warn("凭证冲突：{} 已属于 {}，本次登录的是 {}。不自动合并",
                            c.type(), existing.getUserNo(), user.getUserNo());
                    throw BizException.of(ErrorCode.CONFLICT);
                }
                continue;
            }
            UsrIdentity row = new UsrIdentity();
            row.setUserNo(user.getUserNo());
            row.setIdentityType(c.type());
            row.setIdentityValue(c.value());
            row.setChannel(c.channel());
            row.setVerifiedAt(LocalDateTime.now());
            identityMapper.insert(row);

            syncLegacyColumn(user, c);
        }
    }

    /**
     * 过渡期双写 {@code usr_account} 的旧凭证列。
     *
     * <p>迁移只加不删（V3），旧列还在、旧的唯一键也还在。不双写的话，新建的账号在旧列上
     * 是空的，而任何还在读旧列的代码（以及人工排查时的 SQL）都会看到一个「没有手机号的用户」。
     *
     * <p>确认 {@code usr_identity} 与旧列一致后，删列与这个方法一起去掉。
     */
    private void syncLegacyColumn(UsrAccount user, Credential c) {
        switch (c.type()) {
            case IdentityType.PHONE -> user.setPhone(c.value());
            case IdentityType.WX_OPENID_MP -> user.setOpenid(c.value());
            case IdentityType.WX_UNIONID -> user.setUnionid(c.value());
            case IdentityType.APPLE_SUB -> user.setAppleSub(c.value());
            default -> {
                // WX_OPENID_APP / WX_OPENID_OA 在旧结构里**根本没有对应的列** ——
                // 这正是必须拆表的原因，不是遗漏
                return;
            }
        }
        userMapper.updateById(user);
    }

    private UsrIdentity findIdentity(Credential c) {
        return identityMapper.selectOne(Wrappers.<UsrIdentity>lambdaQuery()
                .eq(UsrIdentity::getIdentityType, c.type())
                .eq(UsrIdentity::getIdentityValue, c.value())
                .last("limit 1"));
    }

    private UsrAccount createAccount(LoginCommand cmd) {
        UsrAccount user = new UsrAccount();
        user.setUserNo(BizKey.next(BizKey.USER));
        user.setNickname("邻居" + user.getUserNo().substring(user.getUserNo().length() - 4));
        user.setAvatar("");
        user.setStatus("NORMAL");
        user.setEntityNo(cmd.merchantNo());
        userMapper.insert(user);
        return user;
    }

    /**
     * 一条登录凭证。
     *
     * @param type    见 {@link IdentityType}
     * @param value   凭证值
     * @param channel 来源留痕：MP / APP / H5。手机号 OTP 可能来自任意端，允许为空
     */
    private record Credential(String type, String value, String channel) {
    }
}
