package ai.neargo.shop.config;

import ai.neargo.auth.store.IdentityLoader;
import ai.neargo.shop.auth.LoginUser;

import java.util.Optional;

/**
 * ⚠️ <b>过渡期专用：C 端池里现在还装着 B 端的会话。</b>
 *
 * <p>商家员工登录签发的是 {@code LoginUser.consumer(mch_account_no, ...)} ——
 * C 端令牌，主体却是**商家账号号**。会话外置之前这没有症状，
 * 因为整份身份都存在会话里；外置之后身份改为从用户表现读，
 * 而 {@code mch_account_no} <b>不在 {@code usr_account} 里</b> ——
 * 直接切过去的话，**所有店员下一次请求就是 401**。
 *
 * <p>所以过渡期先在 C 端池上串一个回落：先查 {@code usr_account}，
 * 查不到再查 {@code mch_account}。这**不是**长久设计 ——
 * 它恰恰保留了「一列装两种 id」这个要被消灭的状态。
 *
 * <p><b>A7（B 端改发 {@code btk_} + {@code /biz/**} 切链）落地那天，这个类整个删掉。</b>
 * 判据很明确：{@code usr_session} 里不再出现 {@code mch_account_no} 形状的主体。
 */
public class TransitionalConsumerIdentityLoader implements IdentityLoader<LoginUser> {

    private final IdentityLoader<LoginUser> consumers;
    private final IdentityLoader<LoginUser> merchants;

    public TransitionalConsumerIdentityLoader(IdentityLoader<LoginUser> consumers,
                                              IdentityLoader<LoginUser> merchants) {
        this.consumers = consumers;
        this.merchants = merchants;
    }

    @Override
    public Optional<LoginUser> load(String userNo) {
        Optional<LoginUser> asConsumer = consumers.load(userNo);
        if (asConsumer.isPresent()) {
            return asConsumer;
        }
        /*
         * 回落成商家身份，但**降级成 consumer 形态**返回 —— 因为它此刻装在 C 端池里，
         * 而 DbTokenStore 会校验「会话的 realm 与本 store 一致」。
         * 返回 MERCHANT 身份会让那道校验在每个店员请求上炸，
         * 而那道校验本身是对的，不该为过渡期放宽。
         */
        return merchants.load(userNo)
                .map(m -> LoginUser.consumer(m.userNo(), m.nickname()));
    }
}
