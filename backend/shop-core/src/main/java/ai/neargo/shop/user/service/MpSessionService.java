package ai.neargo.shop.user.service;

import java.util.Optional;

/** 会话内的可信小程序操作者，独立于手机号账号的永久渠道凭证。 */
public interface MpSessionService {
    void record(String token, String userNo, String openid);
    Optional<String> openid(String token, String userNo);
    void remove(String token);
    void removeUser(String userNo);
}
