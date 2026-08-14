package ai.neargo.shop.user.port;

import ai.neargo.shop.spi.user.UserIdentityPort;
import ai.neargo.shop.user.IdentityType;
import ai.neargo.shop.user.entity.UsrIdentity;
import ai.neargo.shop.user.mapper.UserMappers.IdentityMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** {@link UserIdentityPort} 实现：从 {@code usr_identity} 取触达地址。 */
@Component
public class UserIdentityPortImpl implements UserIdentityPort {

    private final IdentityMapper identityMapper;

    public UserIdentityPortImpl(IdentityMapper identityMapper) {
        this.identityMapper = identityMapper;
    }

    @Override
    public Optional<String> wxOpenIdMp(String userNo) {
        if (userNo == null || userNo.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(identityMapper.selectOne(Wrappers.<UsrIdentity>lambdaQuery()
                        .eq(UsrIdentity::getUserNo, userNo)
                        .eq(UsrIdentity::getIdentityType, IdentityType.WX_OPENID_MP)
                        // 同类型多条时取最近登记的那条 —— 老的可能来自换绑前的微信号
                        .orderByDesc(UsrIdentity::getId).last("limit 1")))
                .map(UsrIdentity::getIdentityValue);
    }
}
