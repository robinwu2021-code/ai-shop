package ai.neargo.shop.merchant.auth;

import ai.neargo.auth.store.IdentityLoader;
import ai.neargo.auth.store.SubjectKind;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.merchant.entity.MchAccount;
import ai.neargo.shop.merchant.mapper.MerchantMappers;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * B 端身份：{@code mch_account_no} → {@link LoginUser}。**只读 {@code mch_account}。**
 *
 * <p><b>刻意不解析实体与门店。</b>那两样由每个请求的 {@code X-Store-No} 决定 ——
 * 一个店长可以在多个门店之间切换，{@code BizIdentityResolver.resolve(userNo, storeNo)}
 * 每次现算并校验归属。把它们放进身份就出现第二个真源，
 * 而过期的那一份会让「切了门店但权限还是上一个店的」：
 * 界面上看是「数据串了」，排查方向会完全跑偏。
 *
 * <p>身份只回答「你是谁、还能不能用」，归属由归属那一层回答。
 */
@Component
public class MerchantIdentityLoader implements IdentityLoader<LoginUser> {

    /** 可以操作的状态。停用的一律当作「加载不到」→ 401。 */
    private static final String ACTIVE = "ACTIVE";

    private final MerchantMappers.MchAccountMapper accounts;

    public MerchantIdentityLoader(MerchantMappers.MchAccountMapper accounts) {
        this.accounts = accounts;
    }

    @Override
    public Optional<LoginUser> load(String mchAccountNo) {
        MchAccount account = accounts.selectOne(Wrappers.<MchAccount>lambdaQuery()
                .eq(MchAccount::getMchAccountNo, mchAccountNo)
                .last("LIMIT 1"));
        if (account == null || !ACTIVE.equals(account.getStatus())) {
            // 员工被停用、被移出实体 → 下一个请求就 401，不必等会话过期。
            // 「停用后立即无法操作」这条契约，在这里多了一道保险
            return Optional.empty();
        }
        return Optional.of(LoginUser.merchant(account.getMchAccountNo(), account.getDisplayName()));
    }

    /** 这个加载器认 MCH 类主体。**显式写出来** —— 组合加载器按它分发。 */
    @Override
    public SubjectKind kind() {
        return SubjectKind.MCH;
    }
}
