package ai.neargo.shop.user.service.impl;

import ai.neargo.shop.user.service.AuthService;
import ai.neargo.shop.common.OtpStore;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.user.dto.UserVO;
import ai.neargo.shop.user.entity.UsrAccount;
import ai.neargo.shop.user.mapper.UserMappers.UserMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;

/**
 * 登录建户实现。
 *
 * <p><b>S1 的边界</b>：微信 {@code code2Session} 与 Apple 的 identityToken 校验都还没接
 * （需要小程序 appid/secret 与真机联调），当前按「principal 即稳定标识」建户。
 * 接真渠道时改的是 {@link #resolveIdentity} 一个方法，建户主干与会话发放不动。
 * 这一点在此显式写明，避免有人误以为微信登录已经通了。
 */
@Service
public class AuthServiceImpl implements AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthServiceImpl.class);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserMapper userMapper;
    private final TokenStore tokenStore;
    private final OtpStore otpStore;

    public AuthServiceImpl(UserMapper userMapper, TokenStore tokenStore, OtpStore otpStore) {
        this.userMapper = userMapper;
        this.tokenStore = tokenStore;
        this.otpStore = otpStore;
    }

    @Override
    public void sendOtp(String phone) {
        String code = "%06d".formatted(RANDOM.nextInt(1_000_000));
        otpStore.save(phone, code);
        // S1 没接短信通道：打日志代替发送，方便本地联调。生产环境接通道前这条日志必须去掉
        log.info("[DEV-ONLY] otp for {} = {}", phone, code);
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
        Identity identity = resolveIdentity(cmd);
        UsrAccount user = findOrCreate(identity, cmd);

        if ("BANNED".equals(user.getStatus())) {
            throw BizException.of(ErrorCode.RISK_BLOCKED);
        }

        String token = tokenStore.issue(TokenStore.SessionData.of(
                LoginUser.consumer(user.getUserNo(), user.getNickname())));
        return new LoginResult(token, UserVO.of(user));
    }

    /** 授权凭据 → 稳定标识。三种方式的差异**只在这一个方法里**。 */
    private Identity resolveIdentity(LoginCommand cmd) {
        return switch (cmd.grantType() == null ? "" : cmd.grantType()) {
            case GRANT_WECHAT_MP ->
                // TODO(S4) 接 code2Session：cmd.principal() 是 wx.login 的 code，换 openid/unionid
                    new Identity(UsrAccount::getOpenid, "openid", cmd.principal());
            case GRANT_PHONE_OTP -> {
                verifyOtp(cmd.principal(), cmd.credential());
                yield new Identity(UsrAccount::getPhone, "phone", cmd.principal());
            }
            case GRANT_APPLE ->
                // TODO(S4) 校验 identityToken 签名后取 sub
                    new Identity(UsrAccount::getAppleSub, "apple_sub", cmd.principal());
            default -> throw BizException.of(ErrorCode.BAD_REQUEST);
        };
    }

    private void verifyOtp(String phone, String code) {
        if (!otpStore.verifyAndConsume(phone, code)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }

    private UsrAccount findOrCreate(Identity identity, LoginCommand cmd) {
        UsrAccount existing = userMapper.selectOne(Wrappers.<UsrAccount>lambdaQuery()
                .eq(true, identity.column(), identity.value())
                .last("limit 1"));
        if (existing != null) {
            // 从店铺码进来的老用户：刷新常去店，归因由 marketing 域按优先级裁决（S5）
            if (cmd.merchantNo() != null && !cmd.merchantNo().isBlank()) {
                existing.setEntityNo(cmd.merchantNo());
                userMapper.updateById(existing);
            }
            return existing;
        }

        UsrAccount user = new UsrAccount();
        user.setUserNo(BizKey.next(BizKey.USER));
        user.setNickname("邻居" + user.getUserNo().substring(user.getUserNo().length() - 4));
        user.setAvatar("");
        user.setStatus("NORMAL");
        user.setEntityNo(cmd.merchantNo());
        switch (identity.field()) {
            case "openid" -> user.setOpenid(identity.value());
            case "phone" -> user.setPhone(identity.value());
            default -> user.setAppleSub(identity.value());
        }
        userMapper.insert(user);
        return user;
    }

    /** 标识列的三元组：查用的 lambda、写用的字段名、值。 */
    private record Identity(com.baomidou.mybatisplus.core.toolkit.support.SFunction<UsrAccount, ?> column,
                            String field, String value) {
    }
}
