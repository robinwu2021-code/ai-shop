package ai.neargo.shop.user.service;

import java.util.Optional;

/**
 * 会话内的可信小程序操作者，独立于手机号账号的永久渠道凭证。
 *
 * <p>openid 是登录时服务端从微信换来的，签发会话时放进会话数据（{@code TokenStore.SessionData#openid}），
 * 随会话轮换、随会话撤销失效；这里只负责按令牌取回。原先另存在 {@code usr_mp_session}，新库并进了
 * {@code usr_session.openid}（TDD-平台基础库换表 第 2 步 H）。
 */
public interface MpSessionService {
    /** 令牌对应的会话里的 openid；令牌失效、不是 C 端会话、或会话主体不是 {@code userNo} 时为空 */
    Optional<String> openid(String token, String userNo);
}
