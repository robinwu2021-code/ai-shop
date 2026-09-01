package ai.neargo.shop.payclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.payclient.impl.RemoteOpsFeeRuleAppService;
import ai.neargo.shop.svc.ConfigServiceLocator;
import ai.neargo.shop.svc.InternalClient;
import ai.neargo.shop.svc.ServiceName;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * 远程调用失败时<b>必须抛，不能返回空集合</b>。
 *
 * <h2>为什么这是这一层最危险的一行</h2>
 * 空集合在费率这件事上是一句谎话：
 * <ul>
 *   <li>{@code rules()} 返回空 → 运营看到「没有配置任何费率」，
 *       而实际是支付域没应答。他会照着那个页面<b>新增一版费率</b>，
 *       而库里其实已经有了；</li>
 *   <li>{@code effectiveRates()} 返回空 Map 更严重 —— 下游的语义是
 *       「这一格查不到，按 0 算」，也就是<b>零佣金</b>。
 *       一次网络抖动变成一批单少收佣金，<b>而且不报错</b>。</li>
 * </ul>
 *
 * <p>「调不通返回空」是分布式里最容易写、也最难发现的一种错：
 * 它不抛异常、不进日志的 ERROR、页面照常渲染。
 */
class RemoteFeeRuleFailureTest {

    /** 造一个必然调不通的远程：地址指向一个没人监听的端口 */
    private static RemoteOpsFeeRuleAppService unreachableRemote() {
        var locator = new ConfigServiceLocator();
        locator.getTargets().put(ServiceName.PAY, "http://127.0.0.1:1");
        var client = new InternalClient(locator);
        // 密钥用反射塞进去：@Value 在单测里不生效，而空密钥会走到「没配」那条分支，
        // 测不到「调不通」——**两种失败要分开测，不能靠一条撞上哪个算哪个**
        try {
            var f = InternalClient.class.getDeclaredField("token");
            f.setAccessible(true);
            f.set(client, "test-token");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return new RemoteOpsFeeRuleAppService(client, new ObjectMapper());
    }

    @Test
    @DisplayName("★★★ 支付域调不通时 rules() 要抛 —— 返回空列表会让运营以为「还没配过费率」")
    void unreachableRulesThrowsInsteadOfEmptyList() {
        assertThatThrownBy(() -> unreachableRemote().rules())
                .as("调不通必须抛。返回空列表的话，运营会照着空页面再建一版费率，"
                        + "而库里已经有了")
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("★★★ effectiveRates 调不通要抛 —— 空 Map 的下游语义是「按 0 算」，即零佣金")
    void unreachableEffectiveRatesThrowsInsteadOfEmptyMap() {
        assertThatThrownBy(() -> unreachableRemote().effectiveRates(1788000000000L))
                .as("空 Map 会让每一格费率都查不到，而查不到按 0 算 —— "
                        + "一次网络抖动变成一批单零佣金，且不报错")
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("★★ 写操作在切过去之前要明确拒绝 —— 悄悄走一条没有幂等保护的远程写更糟")
    void addIsRefusedUntilItHasAnIdempotencyKey() {
        assertThatThrownBy(() -> unreachableRemote()
                .add("SELF_OPERATED", "PLATFORM", 500, null, "试"))
                .as("远程写超时之后不知道成没成，而费率是只增不改 —— "
                        + "重试会多出一版，意味着一段时间的账按错的费率算")
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("★★ 地址没配与调不通要分开 —— 前者改配置，后者等对方起来")
    void notConfiguredIsDistinctFromUnreachable() {
        var client = new InternalClient(new ConfigServiceLocator());   // 什么都没配
        var r = client.get(ServiceName.PAY, "/internal/pay/fee-rules", 1);

        assertThat(r.outcome())
                .as("没配地址要报 NOT_CONFIGURED（改配置），不能报 UNREACHABLE（等对方）—— "
                        + "后者永远等不到")
                .isEqualTo(InternalClient.Outcome.NOT_CONFIGURED);
    }
}
