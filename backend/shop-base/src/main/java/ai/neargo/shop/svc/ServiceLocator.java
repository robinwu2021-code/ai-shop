package ai.neargo.shop.svc;

import java.util.Optional;

/**
 * <b>「某个服务在哪里」的唯一出口。</b>
 *
 * <h2>它存在的理由是把两件事分开</h2>
 * 「地址从哪来」（这个接口）与「怎么调」（{@link InternalClient}）没有任何关系。
 * 合在一起的话，将来换服务发现方式会连带改掉重试与超时逻辑。
 *
 * <p>调用方<b>只知道服务名</b>，不知道地址、不知道地址是配置里的还是 DNS 解析的
 * 还是从 Consul 拉的。换实现时调用点一行不改 —— 这是这一层的全部价值。
 *
 * <h2>今天的实现读配置，这是有意的</h2>
 * 见 ADR-023：服务器是单机，三个进程都在 {@code 127.0.0.1}。
 * DNS 服务发现在这种形态下<b>不解决任何问题</b> ——
 * DNS 只解析 host→IP 不带端口，而端口无论如何都要落在配置里，
 * 端口既然在配置里，host 一起配的成本是零。
 *
 * <p>多机那天把配置里的 IP 换成域名即可，<b>代码一行不改</b>；
 * 需要多实例与健康检查时，才轮到 Consul，那时新增一个实现。
 *
 * <h2>找不到要能与「调不通」分开</h2>
 * 返回 {@link Optional} 而不是抛异常或返回 null：
 * <b>「没配这个服务」和「配了但连不上」是两种完全不同的故障</b> ——
 * 前者改配置，后者等对方起来。混成一种的话，运维会守着一个永远不来的恢复。
 * 这条是从 {@code HttpBusinessClient} 那边学来的，它注释里写着同一句话。
 */
public interface ServiceLocator {

    /**
     * @param service 服务名，用 {@link ServiceName} 里的常量
     * @return 形如 {@code http://127.0.0.1:8083} 的基址，<b>不带尾斜杠</b>；
     *         没有配置这个服务时返回 {@link Optional#empty()}
     */
    Optional<String> baseUrlOf(String service);
}
