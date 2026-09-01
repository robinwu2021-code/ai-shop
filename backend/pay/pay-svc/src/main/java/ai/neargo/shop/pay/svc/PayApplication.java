package ai.neargo.shop.pay.svc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 支付域 · 独立部署形态（形态 B）的进程入口。
 *
 * <h2>它今天能做什么、不能做什么</h2>
 * <b>能</b>：起一个只含支付域的进程，验证「支付域能不能在没有任何业务模块的
 * 上下文里装配起来」—— 这个问题的答案此前只存在于设计文档里。
 *
 * <p><b>不能</b>：接流量。支付域依赖 <b>11 个业务侧 Port</b>
 * （MerchantQueryPort 6 处、SettleSourcePort 3 处、SettingPort 3 处 …），
 * 它们的实现都在业务模块里，而这个进程刻意不引业务模块。
 * 在这些 Port 有 HTTP 实现之前，这个产物<b>装得起来但用不了</b>。
 *
 * <p>这不是缺陷，是这一步的定义（TDD-支付域-实施方案 §C4：
 * 「两种形态都装得起来，<b>不接流量</b>」）。先让装配路径跑通，
 * 缺什么由 Spring 自己报出来 —— 那份缺失清单比任何设计文档都准。
 *
 * <h2>照的是 shop-job 的先例</h2>
 * 这个仓库已经独立部署过一个进程（{@code shop-job}）：独立 jar、独立库、
 * 通过 {@code /internal} 与主应用对话，四条硬要求写在
 * {@code JobHandlerEndpoint} 的类注释里。支付域抄的是那个形状。
 *
 * <p><b>但有一处抄不了</b>：job 是无状态调度器，它的库只存任务记录；
 * 而支付域存的是钱的账，与订单、结算强关联。所以「独立进程」可以照抄，
 * 「独立库」（D2）是另一回事，也是整条路径上唯一不可轻易回退的一步。
 */
/*
 * MapperScan 要在这里显式写一份：主应用的那份在 shop-app 的配置类上，
 * 而这个进程刻意不引 shop-app。**装配所需的每一样都要在这边重新声明一次** ——
 * 这正是「独立形态」的实际含义，也是它比设计文档更能说明问题的地方。
 */
@org.mybatis.spring.annotation.MapperScan("ai.neargo.shop.pay.mapper")
/*
 * **排掉鉴权的自动配置。**
 *
 * 支付域没有 controller、不认用户身份，这个进程只暴露 /internal（共享密钥）
 * 与 /callback（通道验签）—— 两者都不该走用户鉴权链。
 *
 * 但 `neargo-common-security` **还是进了 classpath**，路径是
 * `pay-domain → shop-store-mybatis → shop-base-auth → neargo-common-security`：
 * 存储层要依赖 auth，因为 AuditMetaObjectHandler 得知道「当前是谁」
 * 才能填 created_by（层序是 内核 ← auth ← store，那是有意的设计）。
 *
 * <b>2026-09-01 第一次启动这个进程时，任何请求都是 401 且响应体为空</b> ——
 * 就是这条自动配置装上了 OpaqueTokenAuthFilter。
 * 而今天早些时候我摘掉了 pay-domain 对 shop-base-auth 的**直接**依赖，
 * 还验过「依赖树里 auth 归零」—— <b>那次验的是 pay-domain 自己的树，
 * 而这条是从存储层传递进来的</b>。摘直接依赖不等于树干净。
 *
 * 排除是**权宜**：真正的解法是 C2c 换持久层时，把 store → auth 这条
 * 改成 store → 一个「当前操作者」的小抽象，独立形态下注入系统账号。
 * 在那之前，这里显式排掉，并让它在闸门上留下痕迹（见 PayHasNoControllerTest）。
 */
@SpringBootApplication(
        scanBasePackages = "ai.neargo.shop.pay",
        exclude = {
            /*
             * **Spring Boot 自带的那套**才是 401 的来源：classpath 上有
             * spring-security-config，Boot 就自动配一套「所有请求都要认证」，
             * 启动日志里那行 `Using generated security password` 就是它。
             * 排私有的三条自动配置没用 —— 我第一次排错了地方。
             */
            org.springframework.boot.security.autoconfigure.web.servlet
                    .ServletWebSecurityAutoConfiguration.class,
            org.springframework.boot.security.autoconfigure.web.servlet
                    .SecurityFilterAutoConfiguration.class,
            // 私有的这三条同样不该在这个进程里生效：它不认用户身份
            ai.neargo.common.security.SecurityAutoConfiguration.class,
            ai.neargo.common.security.SessionStoreAutoConfiguration.class,
            ai.neargo.common.security.rbac.RbacAutoConfiguration.class,
        })
public class PayApplication {

    public static void main(String[] args) {
        SpringApplication.run(PayApplication.class, args);
    }
}
