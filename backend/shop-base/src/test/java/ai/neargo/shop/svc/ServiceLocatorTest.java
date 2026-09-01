package ai.neargo.shop.svc;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 访问层的两条判据。
 *
 * <p>这一层没有业务逻辑，能出错的地方只有两处：
 * <b>「没配」有没有与「调不通」分开</b>，以及<b>拼路径会不会拼出 {@code //}</b>。
 * 两个都是那种不报错、只是行为不对的错。
 */
class ServiceLocatorTest {

    private static ConfigServiceLocator locatorWith(String service, String url) {
        var l = new ConfigServiceLocator();
        l.getTargets().put(service, url);
        return l;
    }

    @Test
    @DisplayName("★★ 没配的服务返回空，不是抛异常也不是 null —— 「没配」与「调不通」是两种故障")
    void unknownServiceReturnsEmpty() {
        var locator = locatorWith(ServiceName.PLATFORM, "http://127.0.0.1:8081");

        assertThat(locator.baseUrlOf(ServiceName.PAY))
                .as("没配 PAY 就该是空 —— 混成「连不上」的话，"
                        + "运维会守着一个永远不来的恢复")
                .isEmpty();
        assertThat(locator.baseUrlOf(ServiceName.PLATFORM))
                .as("对照量：配了的必须拿得到，否则上面那条空只是因为什么都读不到")
                .contains("http://127.0.0.1:8081");
    }

    @Test
    @DisplayName("★★★ 环境变量注入的键是小写的 —— 按大写常量查必须也能找到")
    void lookupIsCaseInsensitiveBecauseEnvVarsArriveLowercased() {
        /*
         * 环境变量 SHOP_SERVICES_TARGETS_PAY 经 Spring 的 relaxed binding
         * 进到 Map 里，键是**小写的 pay**，而调用方按 ServiceName.PAY 查。
         *
         * 2026-09-01 生产上就是这么失败的：本地用命令行参数
         * --shop.services.targets.PAY（保留大小写）一路绿灯，
         * 换成环境变量的生产环境第一次调用就 NOT_CONFIGURED。
         * **本地与生产的配置注入方式不同，而这个差异只在 Map 类型上暴露。**
         */
        var locator = locatorWith("pay", "http://pay.svc.internal:8083");

        assertThat(locator.baseUrlOf(ServiceName.PAY))
                .as("键是小写 pay、按大写 PAY 查 —— 这正是生产环境的形状")
                .contains("http://pay.svc.internal:8083");
        assertThat(locator.baseUrlOf("PLATFORM"))
                .as("对照量：确实没配的还是要返回空，不能因为放宽了大小写就什么都找得到")
                .isEmpty();
    }

    @Test
    @DisplayName("★★ 尾斜杠统一去掉 —— 两边都留斜杠会拼出 //internal，在有些反代下 404 且很难看出来")
    void trailingSlashIsStripped() {
        assertThat(locatorWith(ServiceName.PAY, "http://127.0.0.1:8083/").baseUrlOf(ServiceName.PAY))
                .contains("http://127.0.0.1:8083");
        assertThat(locatorWith(ServiceName.PAY, "http://127.0.0.1:8083").baseUrlOf(ServiceName.PAY))
                .as("本来就没有尾斜杠的不能被削掉一个字符")
                .contains("http://127.0.0.1:8083");
    }

    @Test
    @DisplayName("★★★ 密钥没配时一律拒绝 —— 「没配就不校验」等于内部口对任何人开放，且没有症状")
    void missingTokenRefusesInsteadOfSkippingTheCheck() {
        var client = new InternalClient(locatorWith(ServiceName.PAY, "http://127.0.0.1:1"));
        // token 字段默认空串（@Value 在这里不生效，正好是「没配」的那种状态）

        var r = client.post(ServiceName.PAY, "/internal/ping", "{}", 1);

        assertThat(r.outcome())
                .as("密钥没配必须是 NOT_CONFIGURED（改配置），"
                        + "不能是 UNREACHABLE（等对方起来）—— 后者永远等不到")
                .isEqualTo(InternalClient.Outcome.NOT_CONFIGURED);
        assertThat(r.ok()).isFalse();
    }

    @Test
    @DisplayName("★★ 地址没配与连不上要分开 —— 前者改配置，后者等对方起来")
    void notConfiguredIsNotTheSameAsUnreachable() {
        var client = new InternalClient(new ConfigServiceLocator());   // 什么都没配

        var r = client.post(ServiceName.PAY, "/internal/ping", "{}", 1);

        assertThat(r.outcome()).isEqualTo(InternalClient.Outcome.NOT_CONFIGURED);
        assertThat(r.message())
                .as("报错要说清是哪个配置项 —— 只说「调用失败」的话，"
                        + "读的人得先去猜是网络还是配置")
                .contains("shop.services.targets");
    }
}
