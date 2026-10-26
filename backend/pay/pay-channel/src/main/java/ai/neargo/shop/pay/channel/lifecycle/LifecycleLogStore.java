package ai.neargo.shop.pay.channel.lifecycle;

/** {@code pay_channel_lifecycle_log} 的写口 */
public interface LifecycleLogStore {

    void append(String payChannel, String fromState, String toState, String reason);
}
