package ai.neargo.shop.elec.svc;

import ai.neargo.shop.common.ApiResponseWrapper;
import ai.neargo.shop.common.GlobalExceptionHandler;
import ai.neargo.shop.common.Messages;
import ai.neargo.shop.common.ratelimit.InMemoryRateLimiter;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * 电子元器件 · 独立进程（:8085）。
 *
 * <h2>它和 pay-svc、job-worker 不一样的地方</h2>
 * 那两个只收内部调用；<b>这是第一个直接接用户流量的独立进程</b> —— 小程序的 C 端（{@code /elec/c/**}）、
 * B 端（{@code /elec/b/**}）与运营端（{@code /elec/ops/**}），经 nginx 同域转发过来。
 *
 * <h2>它向主系统借的三样东西</h2>
 * 认令牌、取手机号、通知买家 —— 全部走 {@code /internal/elec/**}（契约在 elec-api），
 * <b>主系统的表一张都不读</b>。主系统停了：查料号照常（匿名可用），要登录的接口 503。
 *
 * <h2>扫描范围</h2>
 * 只扫本域与 {@code ai.neargo.shop.svc}（服务间调用的传输件）。主系统地基里本进程要用的几个件
 * （全局信封、异常处理、文案、限流）显式 {@link Import} —— <b>装配所需的每一样都在这里点名</b>，
 * 不靠扫到什么算什么。
 */
@SpringBootApplication(
        scanBasePackages = {"ai.neargo.shop.elec", "ai.neargo.shop.svc"},
        exclude = {
            // 私有安全组件的三条自动配置（经 shop-base-auth 传递进来）不在这个进程里生效：
            // 本进程的令牌由主系统认，安全链见 ElecSecurityConfig。照 pay-svc 的排法
            ai.neargo.common.security.SecurityAutoConfiguration.class,
            ai.neargo.common.security.SessionStoreAutoConfiguration.class,
            ai.neargo.common.security.rbac.RbacAutoConfiguration.class,
        })
@Import({GlobalExceptionHandler.class, ApiResponseWrapper.class, Messages.class, InMemoryRateLimiter.class})
// 只有一个定时任务：库存到期提醒（ElecExpiryReminder）
@org.springframework.scheduling.annotation.EnableScheduling
public class ElecApplication {

    public static void main(String[] args) {
        SpringApplication.run(ElecApplication.class, args);
    }
}
