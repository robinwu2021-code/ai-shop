package ai.neargo.shop.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

/**
 * B 端认证过滤器（{@code /biz/**}）：只认 {@code realm=MERCHANT}。
 *
 * <p><b>这条链此前不存在</b>：商家员工拿的是 C 端的 {@code ctk_} 令牌，
 * 而 {@code LoginUser.userNo} 这一个字段里，C 端塞 {@code usr_account.user_no}、
 * B 端塞 {@code mch_account.mch_account_no} —— 生产上号段恰好不撞，
 * 但那是约定不是结构保证。有了这条链，跨端令牌在**第一道**就被拒。
 *
 * <p><b>经营侧的归属（实体/门店）不在这里解析</b>：那由 {@code BizContextFilter}
 * 在本过滤器**之后**按每请求的 {@code X-Store-No} 现算并校验 ——
 * 一个店长可以在多个门店之间切换，把它固化进身份就会出现
 * 「切了门店但权限还是上一个店的」。
 */
public class MerchantTokenAuthFilter extends AbstractTokenAuthFilter {

    public MerchantTokenAuthFilter(TokenStore tokenStore) {
        super(tokenStore, Realm.MERCHANT);
    }

    @Override
    protected String clientCode(HttpServletRequest req) {
        return "APP_BIZ";
    }

    @Override
    protected Authenticated authenticate(LoginUser sessionUser) {
        // B 端不是 RBAC：能做什么由 BizContext（实体/门店归属）+ BizPerms 判，
        // 这里只给一个「你是商家」的权威
        return new Authenticated(sessionUser,
                List.of(new SimpleGrantedAuthority("ROLE_MERCHANT")));
    }
}
