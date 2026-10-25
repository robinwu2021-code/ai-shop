package ai.neargo.shop.pay.channel.api;

import java.util.Objects;

/**
 * 选择处理器的键：地区 × 渠道 × 接入模式 × 场景 × 操作（渠道接入统一封装设计 §2.1）。
 * 渠道差异全由它在注册表里分派，不散在各处的 {@code if (channel == …)} 里。
 */
public record ChannelKey(String market, String payChannel, AccessMode accessMode, PayScene scene,
                         ChannelOperation operation) {

    public ChannelKey {
        Objects.requireNonNull(payChannel, "payChannel");
        Objects.requireNonNull(accessMode, "accessMode");
        Objects.requireNonNull(scene, "scene");
        Objects.requireNonNull(operation, "operation");
    }

    /**
     * 处理器声明自己支持什么。{@code market == null} 表示任意地区；{@code scene == ANY} 表示任意场景。
     * 渠道、接入模式、操作必须写死 —— 一个处理器对应一个渠道的一种接入方式下的一种操作。
     */
    public record Pattern(String market, String payChannel, AccessMode accessMode, PayScene scene,
                          ChannelOperation operation) {

        public Pattern {
            Objects.requireNonNull(payChannel, "payChannel");
            Objects.requireNonNull(accessMode, "accessMode");
            Objects.requireNonNull(scene, "scene");
            Objects.requireNonNull(operation, "operation");
        }

        public boolean matches(ChannelKey key) {
            return payChannel.equals(key.payChannel()) && accessMode == key.accessMode()
                    && operation == key.operation()
                    && (market == null || market.equals(key.market()))
                    && (scene == PayScene.ANY || scene == key.scene());
        }

        /** 具体度：精确地区 +2，精确场景 +1。最具体者优先，同等具体度两个 → 启动失败 */
        public int specificity() {
            return (market == null ? 0 : 2) + (scene == PayScene.ANY ? 0 : 1);
        }
    }
}
