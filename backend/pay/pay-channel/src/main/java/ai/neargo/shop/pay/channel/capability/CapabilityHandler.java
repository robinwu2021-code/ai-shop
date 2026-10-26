package ai.neargo.shop.pay.channel.capability;

import ai.neargo.shop.pay.channel.api.ChannelKey;
import ai.neargo.shop.pay.channel.api.ChannelOperation;
import ai.neargo.shop.pay.channel.api.ChannelResult;
import ai.neargo.shop.pay.channel.api.PayScene;
import ai.neargo.shop.pay.channel.api.command.ChannelCommand;
import ai.neargo.shop.pay.channel.api.command.CloseCommand;
import ai.neargo.shop.pay.channel.api.command.OnboardingQueryCommand;
import ai.neargo.shop.pay.channel.api.command.OnboardingSubmitCommand;
import ai.neargo.shop.pay.channel.api.command.PayCommand;
import ai.neargo.shop.pay.channel.api.command.PayoutCommand;
import ai.neargo.shop.pay.channel.api.command.QueryCommand;
import ai.neargo.shop.pay.channel.api.command.RefundCommand;
import ai.neargo.shop.pay.channel.api.command.RefundQueryCommand;
import ai.neargo.shop.pay.channel.api.command.SplitCommand;
import ai.neargo.shop.pay.channel.api.command.StatementFetchCommand;
import ai.neargo.shop.pay.channel.api.command.VoidCommand;
import ai.neargo.shop.pay.channel.handler.ChannelHandler;
import ai.neargo.shop.pay.channel.invoke.Invocation;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 把一个能力实现挂成管线认的处理器（Adapter，TDD-支付服务-W2渠道层补完 S1）。
 *
 * <p>{@link #supports()} <b>由实现了哪些能力接口派生</b>：线上收款 × 声明的场景、交易操作 × 声明的操作、刷卡、撤销、分账、
 * 进件、对账单、出款各一组键。{@link #handle} 按命令类型分派到对应的能力方法；命令落到它没声明的场景
 * （比如只做 SDK 的渠道收到二维码收款）明确失败、不调实现。
 *
 * <p>这里不出现任何渠道码 —— 渠道差异在能力实现里（{@code NoChannelBranchOutsideHandlersTest}）。
 */
public final class CapabilityHandler implements ChannelHandler {

    private final ChannelCapability capability;
    private final Set<ChannelKey.Pattern> patterns;

    public CapabilityHandler(ChannelCapability capability) {
        this.capability = capability;
        this.patterns = Set.copyOf(derive(capability));
        if (patterns.isEmpty()) {
            throw new IllegalStateException(capability.getClass().getSimpleName() + "（" + capability.payChannel() + " · "
                    + capability.accessMode() + "）没有声明任何操作 —— 它没实现任何能力接口，或声明的场景 / 操作是空集");
        }
    }

    public ChannelCapability capability() {
        return capability;
    }

    @Override
    public Set<ChannelKey.Pattern> supports() {
        return patterns;
    }

    @Override
    public ChannelResult handle(Invocation inv, ChannelCommand command) {
        return switch (command) {
            case PayCommand c -> pay(inv, c);
            case QueryCommand c when tx(ChannelOperation.QUERY) -> ((TransactionCapability) capability).query(inv, c);
            case CloseCommand c when tx(ChannelOperation.CLOSE) -> ((TransactionCapability) capability).close(inv, c);
            case RefundCommand c when tx(ChannelOperation.REFUND) -> ((TransactionCapability) capability).refund(inv, c);
            case RefundQueryCommand c when tx(ChannelOperation.REFUND_QUERY) ->
                    ((TransactionCapability) capability).queryRefund(inv, c);
            case VoidCommand c when capability instanceof CardPayCapability card -> card.voidCard(inv, c);
            case SplitCommand c when capability instanceof SplitCapability split && split.splitKinds().contains(c.kind()) ->
                    split.split(inv, c);
            case OnboardingSubmitCommand c when capability instanceof OnboardingCapability o -> o.submitOnboarding(inv, c);
            case OnboardingQueryCommand c when capability instanceof OnboardingCapability o -> o.queryOnboarding(inv, c);
            case StatementFetchCommand c when capability instanceof StatementCapability s -> s.fetchStatement(inv, c);
            case PayoutCommand c when capability instanceof PayoutCapability p -> p.payout(inv, c);
            default -> ChannelCapability.unsupported(command.operation());
        };
    }

    private ChannelResult pay(Invocation inv, PayCommand c) {
        if (c.scene() == PayScene.IN_STORE_CARD && capability instanceof CardPayCapability card) {
            return card.payByCard(inv, c);
        }
        if (capability instanceof OfflinePayCapability off && off.offlineScenes().contains(c.scene())) {
            return off.payOffline(inv, c);
        }
        if (capability instanceof OnlinePayCapability on && on.onlineScenes().contains(c.scene())) {
            return on.payOnline(inv, c);
        }
        return ChannelCapability.unsupported(ChannelOperation.PAY);
    }

    private boolean tx(ChannelOperation op) {
        return capability instanceof TransactionCapability t && t.transactionOperations().contains(op);
    }

    private static Set<ChannelKey.Pattern> derive(ChannelCapability c) {
        Set<ChannelKey.Pattern> out = new LinkedHashSet<>();
        if (c instanceof OnlinePayCapability on) {
            on.onlineScenes().forEach(s -> out.add(pattern(c, s, ChannelOperation.PAY)));
        }
        if (c instanceof OfflinePayCapability off) {
            off.offlineScenes().forEach(s -> out.add(pattern(c, s, ChannelOperation.PAY)));
        }
        if (c instanceof CardPayCapability) {
            out.add(pattern(c, PayScene.IN_STORE_CARD, ChannelOperation.PAY));
            out.add(pattern(c, PayScene.ANY, ChannelOperation.VOID));
        }
        if (c instanceof TransactionCapability t) {
            t.transactionOperations().forEach(op -> out.add(pattern(c, PayScene.ANY, op)));
        }
        if (c instanceof SplitCapability s && !s.splitKinds().isEmpty()) {
            out.add(pattern(c, PayScene.ANY, ChannelOperation.SPLIT));
        }
        if (c instanceof OnboardingCapability) {
            out.add(pattern(c, PayScene.ANY, ChannelOperation.ONBOARDING_SUBMIT));
            out.add(pattern(c, PayScene.ANY, ChannelOperation.ONBOARDING_QUERY));
        }
        if (c instanceof StatementCapability) {
            out.add(pattern(c, PayScene.ANY, ChannelOperation.STATEMENT_FETCH));
        }
        if (c instanceof PayoutCapability) {
            out.add(pattern(c, PayScene.ANY, ChannelOperation.PAYOUT));
        }
        return out;
    }

    private static ChannelKey.Pattern pattern(ChannelCapability c, PayScene scene, ChannelOperation op) {
        return new ChannelKey.Pattern(c.market(), c.payChannel(), c.accessMode(), scene, op);
    }
}
