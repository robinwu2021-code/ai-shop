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
    private final ai.neargo.shop.user.mapper.UserMappers.AddressMapper addressMapper;

    public UserQueryPortImpl(UserMapper userMapper,
                             ai.neargo.shop.user.mapper.UserMappers.AddressMapper addressMapper) {
        this.userMapper = userMapper;
        this.addressMapper = addressMapper;
    }

    /**
     * 取收货地址用于**下单时快照**。这是本类「只给后四位」那条规则的唯一例外，
     * 理由见 {@link UserQueryPort#receiverOf}：快照存的是这张单的事实，不是这个人的资料。
     */
    @Override
    public Optional<Receiver> receiverOf(String userNo, String addressId) {
        if (userNo == null || userNo.isBlank() || addressId == null || addressId.isBlank()) {
            return Optional.empty();
        }
        /*
         * **要带 userNo 条件**：地址表按 SELF 收窄，而下单是买家自己的会话，
         * 本来就查得到自己的地址。加这个条件是第二道 —— 万一将来这个 Port
         * 被一个不带用户上下文的路径调用（比如运营补单），它不该能拿到别人的地址。
         */
        ai.neargo.shop.user.entity.UsrAddress a = addressMapper.selectOne(
                Wrappers.<ai.neargo.shop.user.entity.UsrAddress>lambdaQuery()
                        .eq(ai.neargo.shop.user.entity.UsrAddress::getAddressId, addressId)
                        .eq(ai.neargo.shop.user.entity.UsrAddress::getUserNo, userNo)
                        .last("limit 1"));
        if (a == null) {
            return Optional.empty();
        }
        /*
         * **门牌要拼进去。** V319 把它从 detail 里分出来单独一列之后，
         * 这里不补一句的话，订单快照里的地址就只到楼盘为止 ——
         * 骑手拿到的是「阳光里小区」，最后 50 米没了，而地址簿页面上一切正常。
         * 加列只改写入、不改读出，正是这类改动最容易漏的一半。
         */
        String full = nz(a.getProvince()) + nz(a.getCity()) + nz(a.getDistrict())
                + nz(a.getDetail()) + houseSuffix(a.getHouseNo());
        return Optional.of(new Receiver(a.getName(), a.getPhone(), full, a.getLatE6(), a.getLngE6()));
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** 门牌前加一个空格好读；空的时候**什么都不加**，别留一个孤零零的尾巴 */
    private static String houseSuffix(String houseNo) {
        return houseNo == null || houseNo.isBlank() ? "" : " " + houseNo.trim();
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
