package ai.neargo.shop.user.auth;

import ai.neargo.auth.store.IdentityLoader;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.user.entity.UsrAccount;
import ai.neargo.shop.user.mapper.UserMappers;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * C 端身份：{@code user_no} → {@link LoginUser}。**只读 {@code usr_account}。**
 *
 * <p>「只读本端用户表」不只是整洁：它是「将来能拆库」的第三条约束。
 * 跨端读一次，那条约束就破了 —— 而破的那天没有任何东西会报错，
 * 直到真的去拆库时才发现搬不动。
 *
 * <p>身份**每个请求现读**（带一层短 TTL 缓存），不再从会话里取快照。
 * 于是改昵称下一个请求就生效，而封禁不必等会话过期。
 */
@Component
public class ConsumerIdentityLoader implements IdentityLoader<LoginUser> {

    /** 可以登录的状态。其余（封禁、注销）一律当作「加载不到」。 */
    private static final String NORMAL = "NORMAL";

    private final UserMappers.UserMapper users;

    public ConsumerIdentityLoader(UserMappers.UserMapper users) {
        this.users = users;
    }

    @Override
    public Optional<LoginUser> load(String userNo) {
        UsrAccount user = users.selectOne(Wrappers.<UsrAccount>lambdaQuery()
                .eq(UsrAccount::getUserNo, userNo)
                .last("LIMIT 1"));
        if (user == null || !NORMAL.equals(user.getStatus())) {
            // 账号不存在或不可用 → 调用方 401。
            // **不要返回一个空身份放行** —— 那是没有任何权限的幽灵身份在系统里游走：
            // 多数接口会把它挡住所以不报错，直到碰上一个只判「登录了没」的接口
            return Optional.empty();
        }
        return Optional.of(LoginUser.consumer(user.getUserNo(), user.getNickname()));
    }
}
