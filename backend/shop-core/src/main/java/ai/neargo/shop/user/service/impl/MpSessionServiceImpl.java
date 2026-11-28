package ai.neargo.shop.user.service.impl;

import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.user.service.MpSessionService;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** openid 跟着会话走：从会话存储取，存储换成哪种都一样。 */
@Service
public class MpSessionServiceImpl implements MpSessionService {
    private final TokenStore tokenStore;

    public MpSessionServiceImpl(TokenStore tokenStore) {
        this.tokenStore = tokenStore;
    }

    /** Never infer another account's payer identity; the token and subject must both match. */
    @Override
    public Optional<String> openid(String token, String userNo) {
        if (token == null || userNo == null) {
            return Optional.empty();
        }
        return tokenStore.get(token)
                .filter(s -> s.user().isConsumer() && userNo.equals(s.user().userNo()))
                .map(TokenStore.SessionData::openid);
    }
}
