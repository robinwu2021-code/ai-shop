package ai.neargo.shop.pay.channel.capability;

import ai.neargo.shop.pay.channel.api.AccessMode;
import ai.neargo.shop.pay.channel.handler.ChannelHandler;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * 本部署装了哪些渠道能力（TDD-支付服务-W2渠道层补完 S1）。<b>替代 {@code PayGatewayRouter}</b>：那是按渠道名一张表，
 * 「渠道装了没有」与「装的是哪种接入模式」都藏在「装的是哪个类」里；这里两件事都能直接问。
 *
 * <p>同一渠道同时装两种接入模式是允许的（收付通与直连并存，设计 §6.2），这时<b>按渠道名问「用哪一种」答不出来</b>
 * （{@link #defaultAccessMode} 为空）—— 调用方必须带着收款商户的接入模式来（{@code ChannelContextFactory}），
 * 而不是让这里随便挑一个：挑错等于把钱发到另一种商户号的接口上。
 */
public final class ChannelCapabilityRegistry {

    private final List<ChannelCapability> capabilities;
    private final List<ChannelHandler> handlers;
    private final Map<String, Set<AccessMode>> wired = new TreeMap<>();

    public ChannelCapabilityRegistry(List<? extends ChannelCapability> capabilities) {
        this.capabilities = List.copyOf(capabilities);
        List<ChannelHandler> hs = new ArrayList<>();
        for (ChannelCapability c : this.capabilities) {
            hs.add(new CapabilityHandler(c));
            wired.computeIfAbsent(c.payChannel(), k -> EnumSet.noneOf(AccessMode.class)).add(c.accessMode());
        }
        this.handlers = List.copyOf(hs);
    }

    /** 给 {@code ChannelHandlerRegistry}：每个能力实现一个处理器 */
    public List<ChannelHandler> handlers() {
        return handlers;
    }

    /** 这个渠道在本部署装了没有（任一能力、任一接入模式） */
    public boolean supports(String payChannel) {
        return payChannel != null && wired.containsKey(payChannel);
    }

    public Set<AccessMode> accessModes(String payChannel) {
        return payChannel == null ? Set.of() : Set.copyOf(wired.getOrDefault(payChannel, Set.of()));
    }

    /** 渠道只装了一种接入模式时就是它；没装或装了不止一种为空 */
    public Optional<AccessMode> defaultAccessMode(String payChannel) {
        Set<AccessMode> modes = accessModes(payChannel);
        return modes.size() == 1 ? Optional.of(modes.iterator().next()) : Optional.empty();
    }

    /** 按渠道 × 接入模式取某种能力（也可以是能力实现顺带实现的其他接口，如 {@code PayFundsOperations}） */
    public <T> Optional<T> find(String payChannel, AccessMode accessMode, Class<T> type) {
        return capabilities.stream()
                .filter(c -> c.payChannel().equals(payChannel) && c.accessMode() == accessMode && type.isInstance(c))
                .map(type::cast)
                .findFirst();
    }

    /** 按渠道取某种能力；渠道装了不止一种接入模式时为空（见类注释） */
    public <T> Optional<T> find(String payChannel, Class<T> type) {
        return defaultAccessMode(payChannel).flatMap(mode -> find(payChannel, mode, type));
    }

    /** 渠道 → 装了的接入模式（排障与启动校验用） */
    public Map<String, Set<AccessMode>> wired() {
        Map<String, Set<AccessMode>> out = new TreeMap<>();
        wired.forEach((k, v) -> out.put(k, Set.copyOf(v)));
        return out;
    }
}
