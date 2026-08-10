package ai.neargo.shop.user.port;

import ai.neargo.shop.spi.user.UserQueryPort;
import ai.neargo.shop.user.entity.UsrAccount;
import ai.neargo.shop.user.mapper.UserMappers.UserMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * {@link UserQueryPort} 实现：别的域拿用户的展示信息。
 *
 * <p>从 {@code UserServiceImpl} 里抽出来的 —— Service 兼任 Port 时，
 * 两拨受众（本域 Controller / 别的域）共用一个类，改本域逻辑会不知不觉改掉跨域契约的行为。
 *
 * <p><b>只给手机号后四位</b>：别的域要么是展示（订单里显示买家）、要么是核对身份，
 * 两者都不需要完整号码。Port 的原则是「给调用方需要的最小事实」，
 * 手机号是最不该顺手多给的那一类。
 */
@Component
public class UserQueryPortImpl implements UserQueryPort {

    private static final int TAIL = 4;

    private final UserMapper userMapper;

    public UserQueryPortImpl(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    public Optional<UserBrief> find(String userNo) {
        if (userNo == null || userNo.isBlank()) {
            return Optional.empty();
        }
        UsrAccount u = userMapper.selectOne(Wrappers.<UsrAccount>lambdaQuery()
                .eq(UsrAccount::getUserNo, userNo).last("limit 1"));
        if (u == null) {
            return Optional.empty();
        }
        return Optional.of(new UserBrief(u.getUserNo(), u.getNickname(),
                phoneTail(u.getPhone()), u.getAvatar()));
    }

    private static String phoneTail(String phone) {
        return phone == null || phone.length() < TAIL ? ""
                : phone.substring(phone.length() - TAIL);
    }

    @Override
    public java.util.Optional<String> communityOf(String userNo) {
        if (userNo == null || userNo.isBlank()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.ofNullable(
                        ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() ->
                                userMapper.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers
                                        .<ai.neargo.shop.user.entity.UsrAccount>lambdaQuery()
                                        .eq(ai.neargo.shop.user.entity.UsrAccount::getUserNo, userNo)
                                        .last("limit 1"))))
                .map(ai.neargo.shop.user.entity.UsrAccount::getCommunityNo)
                .filter(c -> c != null && !c.isBlank());
    }
}
