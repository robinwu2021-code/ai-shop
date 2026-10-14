package ai.neargo.shop.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import jakarta.annotation.PostConstruct;
import java.util.Arrays;

/**
 * 预设验证码（{@code shop.auth.otp.fixed}）的**最后一道闸**：prod 下直接拒绝启动。
 *
 * <p>那个配置是测试环境的必需品 —— 自动化 e2e 收不到真短信，也不该为了跑测试
 * 去接一条真实短信通道。但它同时是一把**能登进任何人账号的万能钥匙**：
 * 知道这个码 + 知道手机号 = 拿到那个人的会话。
 *
 * <p>危险的从来不是「有人故意在生产开它」，而是**配置继承**：
 * 测试环境的 env 文件被拷成生产的模板、CI 的变量组被复用、
 * 或者干脆是 `SHOP_OTP_FIXED` 留在了某台机器的 shell profile 里。
 * 这类事故没有任何症状 —— 系统工作得完全正常，只是所有人的账号都是公开的。
 *
 * <p>所以这里不是「警告」而是**拒绝启动**：起不来会有人立刻处理，
 * 而一条启动日志里的红字，没有人会在半年后回头看。
 *
 * <p>与 {@code DevSeeder} 同一手法（默认关 + 显式开关），区别是这条还带了
 * 环境判据：种子数据灌错了可以删，会话被别人拿走删不掉。
 *
 * <h2>为什么不能只看 profile</h2>
 *
 * <p>这道闸原本只认 {@code prod/production/prd} 三个 profile 名，注释里还写着
 * 「多写几个别名比漏一个安全」—— 而**本项目的生产跑的是 {@code api,ops}**，
 * 三个名字一个都不沾。更要命的是**本地开发也跑 {@code api,ops}**：
 * 判据两边取值相同，它区分不了任何东西。
 * 于是这道「最后一道闸」从上线第一天起就是空的，且**空得毫无症状** ——
 * 单测全绿（测试自己造了个叫 prod 的 profile），线上照常启动。
 *
 * <p>换成 {@code shop.sms.stub}：这是**能力上的真实差别**，不是命名约定。
 * 而且两者在语义上本来就互斥 —— 预设码是用来**替代短信通道**的，
 * 短信通道是真的还留着一把万能钥匙，那正是生产的形状。
 *
 * <p>它也扛得住「配置继承」那条主线：把测试环境的 env 拷到生产，
 * 想让预设码生效就得连 {@code SHOP_SMS_STUB=true} 一起拷过去，
 * 而那会让**所有真实用户都收不到验证码** —— 一个当天就会被发现的响亮故障，
 * 而不是「系统一切正常，只是所有人的账号都是公开的」。
 */
@Configuration
public class FixedOtpGuard {

    /**
     * 认这几个 profile 为「生产」。**留着它只是多一层**，不再是主判据 ——
     * 见类注释：本项目生产与本地都跑 api,ops，靠名字分不开。
     */
    private static final String[] PROD_PROFILES = {"prod", "production", "prd"};

    private final String fixedOtp;
    private final boolean smsStub;
    private final Environment env;

    public FixedOtpGuard(@Value("${shop.auth.otp.fixed:}") String fixedOtp,
                         @Value("${shop.sms.stub:true}") boolean smsStub,
                         Environment env) {
        this.fixedOtp = fixedOtp;
        this.smsStub = smsStub;
        this.env = env;
    }

    @PostConstruct
    void check() {
        if (fixedOtp == null || fixedOtp.isBlank()) {
            return;
        }
        /*
         * **主判据**：短信通道是真的，就不许有万能钥匙。
         * 预设码是用来顶替短信的，两者同时成立只有一种解释 ——
         * 这是一台在给真实用户发码的机器。
         */
        if (!smsStub) {
            throw new IllegalStateException(
                    "shop.auth.otp.fixed 被设置了（值：" + fixedOtp + "），"
                            + "而短信通道是真的（shop.sms.stub=false）。"
                            + "这个码能登进**任意手机号**的账号 —— 拒绝启动。"
                            + "生产上清掉 SHOP_OTP_FIXED；"
                            + "要在本机用它，把 SHOP_SMS_STUB 留成默认的 true。");
        }

        // 兜底：即便有人把短信也切成了桩，profile 明写生产照样不许
        boolean prod = Arrays.stream(env.getActiveProfiles())
                .anyMatch(p -> Arrays.stream(PROD_PROFILES).anyMatch(x -> x.equalsIgnoreCase(p)));
        if (prod) {
            throw new IllegalStateException(
                    "shop.auth.otp.fixed 在生产 profile 下被设置了（值：" + fixedOtp + "）。"
                            + "它让任何人都能用这个码登录任意手机号 —— 拒绝启动。"
                            + "清掉 SHOP_OTP_FIXED 这个环境变量再起。");
        }
    }
}
