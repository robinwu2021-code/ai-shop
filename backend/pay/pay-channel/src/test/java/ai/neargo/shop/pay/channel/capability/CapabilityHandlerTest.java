package ai.neargo.shop.pay.channel.capability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.pay.channel.api.AccessMode;
import ai.neargo.shop.pay.channel.api.ChannelAccountContext;
import ai.neargo.shop.pay.channel.api.ChannelKey;
import ai.neargo.shop.pay.channel.api.ChannelOperation;
import ai.neargo.shop.pay.channel.api.ChannelResult;
import ai.neargo.shop.pay.channel.api.InvocationMeta;
import ai.neargo.shop.pay.channel.api.Money;
import ai.neargo.shop.pay.channel.api.PayErrorCode;
import ai.neargo.shop.pay.channel.api.PayScene;
import ai.neargo.shop.pay.channel.api.SourceInfo;
import ai.neargo.shop.pay.channel.api.command.CloseCommand;
import ai.neargo.shop.pay.channel.api.command.PayCommand;
import ai.neargo.shop.pay.channel.api.command.QueryCommand;
import ai.neargo.shop.pay.channel.api.command.RefundCommand;
import ai.neargo.shop.pay.channel.api.command.RefundQueryCommand;
import ai.neargo.shop.pay.channel.api.command.SplitCommand;
import ai.neargo.shop.pay.channel.handler.ChannelHandlerRegistry;
import ai.neargo.shop.pay.channel.handler.ChannelProductCatalog;
import ai.neargo.shop.pay.channel.invoke.Invocation;
import ai.neargo.shop.spi.pay.PayInstruction;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 能力接口 → 处理器（TDD-支付服务-W2渠道层补完 S1 · AC1 / AC2）。
 *
 * <p>守两件事：① 处理器声明的键<b>只由它实现了哪些能力接口派生</b>，不靠手写清单 —— 手写的清单会和实现走岔，
 * 走岔的表现是「声明了退款、调到了却说不支持」或反过来；② 注册表回答「这个渠道装了没有、装的是哪种接入模式」，
 * 替代按渠道名一张表的 {@code PayGatewayRouter}。
 */
class CapabilityHandlerTest {

    private static final InvocationMeta META = new InvocationMeta(null, null, null, null, null,
            SourceInfo.system("TEST", "B1"));

    /** 线上收款（SDK）+ 查 / 退，不会关单、不查退款 */
    static final class OnlineAndTx implements OnlinePayCapability, TransactionCapability {
        final List<String> calls = new ArrayList<>();

        @Override
        public String payChannel() {
            return "X";
        }

        @Override
        public AccessMode accessMode() {
            return AccessMode.MERCHANT_OWN;
        }

        @Override
        public Set<PayScene> onlineScenes() {
            return EnumSet.of(PayScene.ONLINE_SDK);
        }

        @Override
        public ChannelResult payOnline(Invocation inv, PayCommand command) {
            calls.add("pay");
            return new ChannelResult.Pending(new PayInstruction.JsapiParams(Map.of("package", "prepay_id=1")), null);
        }

        @Override
        public Set<ChannelOperation> transactionOperations() {
            return EnumSet.of(ChannelOperation.QUERY, ChannelOperation.REFUND);
        }

        @Override
        public ChannelResult query(Invocation inv, QueryCommand command) {
            calls.add("query");
            return new ChannelResult.Succeeded("T1", new Money(100, "CNY"), null, Map.of());
        }

        @Override
        public ChannelResult close(Invocation inv, CloseCommand command) {
            calls.add("close");
            return ChannelCapability.unsupported(ChannelOperation.CLOSE);
        }

        @Override
        public ChannelResult refund(Invocation inv, RefundCommand command) {
            calls.add("refund");
            return new ChannelResult.Pending(PayInstruction.none(), "R1");
        }

        @Override
        public ChannelResult queryRefund(Invocation inv, RefundQueryCommand command) {
            calls.add("queryRefund");
            return ChannelCapability.unsupported(ChannelOperation.REFUND_QUERY);
        }
    }

    static final class Splitter implements SplitCapability {
        final AccessMode mode;

        Splitter(AccessMode mode) {
            this.mode = mode;
        }

        @Override
        public String payChannel() {
            return "X";
        }

        @Override
        public AccessMode accessMode() {
            return mode;
        }

        @Override
        public Set<SplitCommand.Kind> splitKinds() {
            return EnumSet.allOf(SplitCommand.Kind.class);
        }

        @Override
        public ChannelResult split(Invocation inv, SplitCommand command) {
            return new ChannelResult.Succeeded("S1", command.amount(), null, Map.of());
        }
    }

    @Test
    @DisplayName("★★★ AC1 处理器的键只由实现了哪些能力接口派生：声明了什么就是什么，没实现的操作不出现")
    void patternsDeriveFromCapabilities() {
        CapabilityHandler h = new CapabilityHandler(new OnlineAndTx());

        assertThat(h.supports()).containsExactlyInAnyOrder(
                new ChannelKey.Pattern(null, "X", AccessMode.MERCHANT_OWN, PayScene.ONLINE_SDK, ChannelOperation.PAY),
                new ChannelKey.Pattern(null, "X", AccessMode.MERCHANT_OWN, PayScene.ANY, ChannelOperation.QUERY),
                new ChannelKey.Pattern(null, "X", AccessMode.MERCHANT_OWN, PayScene.ANY, ChannelOperation.REFUND));
    }

    @Test
    @DisplayName("★★★ AC1 命令按类型分派到对应能力方法；场景不在声明里的收款不调实现")
    void dispatchByCommandType() {
        OnlineAndTx cap = new OnlineAndTx();
        CapabilityHandler h = new CapabilityHandler(cap);
        ChannelAccountContext ctx = new ChannelAccountContext(null, "X", AccessMode.MERCHANT_OWN, null, null, null,
                "CNY", 2);

        ChannelResult paid = h.handle(inv(ctx, pay(PayScene.ONLINE_SDK)), pay(PayScene.ONLINE_SDK));
        h.handle(inv(ctx, new QueryCommand(META, "O1")), new QueryCommand(META, "O1"));
        RefundCommand refund = new RefundCommand(META, "O1", "T1", new Money(100, "CNY"), new Money(30, "CNY"), "R1", "r");
        h.handle(inv(ctx, refund), refund);
        ChannelResult qr = h.handle(inv(ctx, pay(PayScene.ONLINE_QR)), pay(PayScene.ONLINE_QR));

        assertThat(cap.calls).containsExactly("pay", "query", "refund");
        assertThat(((ChannelResult.Pending) paid).instruction()).isInstanceOf(PayInstruction.JsapiParams.class);
        assertThat(qr).isInstanceOf(ChannelResult.Failed.class);
        assertThat(((ChannelResult.Failed) qr).code()).isEqualTo(PayErrorCode.CHANNEL_UNSUPPORTED);
    }

    @Test
    @DisplayName("★★ AC1 分账能力：SPLIT 键；同一渠道装了两种接入模式时，按渠道名问「哪一种」答不出来")
    void registryAnswersWiringQuestions() {
        ChannelCapabilityRegistry one = new ChannelCapabilityRegistry(List.of(new OnlineAndTx(),
                new Splitter(AccessMode.MERCHANT_OWN)));

        assertThat(one.supports("X")).isTrue();
        assertThat(one.supports("Y")).isFalse();
        assertThat(one.defaultAccessMode("X")).contains(AccessMode.MERCHANT_OWN);
        assertThat(one.find("X", SplitCapability.class)).isPresent();
        assertThat(one.find("X", OfflinePayCapability.class)).isEmpty();
        assertThat(one.handlers()).hasSize(2);
        assertThat(new ChannelHandlerRegistry(one.handlers(), emptyCatalog()).has(new ChannelKey(null, "X",
                AccessMode.MERCHANT_OWN, PayScene.ANY, ChannelOperation.SPLIT))).isTrue();

        ChannelCapabilityRegistry two = new ChannelCapabilityRegistry(List.of(new OnlineAndTx(),
                new Splitter(AccessMode.SERVICE_PROVIDER)));
        assertThat(two.accessModes("X")).containsExactlyInAnyOrder(AccessMode.MERCHANT_OWN, AccessMode.SERVICE_PROVIDER);
        assertThat(two.defaultAccessMode("X")).isEmpty();
        assertThat(two.find("X", AccessMode.SERVICE_PROVIDER, SplitCapability.class)).isPresent();
    }

    @Test
    @DisplayName("★★ 一个能力类不实现任何能力接口 = 装配错误，启动即炸")
    void capabilityWithoutAnyOperationFails() {
        ChannelCapability bare = new ChannelCapability() {
            @Override
            public String payChannel() {
                return "X";
            }

            @Override
            public AccessMode accessMode() {
                return AccessMode.MERCHANT_OWN;
            }
        };
        assertThatThrownBy(() -> new CapabilityHandler(bare)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("没有声明任何操作");
    }

    @Test
    @DisplayName("★★★ AC2 PayInstruction 给端上的扁平参数与今天逐字相同（键名、顺序、值）")
    void instructionFlattensToTodaysParams() {
        Map<String, String> six = new LinkedHashMap<>();
        six.put("appId", "wx1");
        six.put("timeStamp", "1");
        six.put("nonceStr", "n");
        six.put("package", "prepay_id=p");
        six.put("signType", "RSA");
        six.put("paySign", "s");

        assertThat(new PayInstruction.JsapiParams(six).asParams()).containsExactlyEntriesOf(six);
        assertThat(new PayInstruction.RedirectUrl("https://pay.example/x").asParams())
                .containsExactly(Map.entry("redirectUrl", "https://pay.example/x"));
        assertThat(new PayInstruction.QrContent("weixin://wxpay/1").asParams())
                .containsExactly(Map.entry("qrContent", "weixin://wxpay/1"));
        assertThat(new PayInstruction.FormPost("https://core.newebpay.com/MPG/mpg_gateway", Map.of("TradeInfo", "x"))
                .asParams()).containsEntry("TradeInfo", "x").containsEntry("formAction",
                "https://core.newebpay.com/MPG/mpg_gateway");
        assertThat(PayInstruction.none().asParams()).isEmpty();
        assertThat(new ChannelResult.Pending(null, null).instruction()).isEqualTo(PayInstruction.none());
    }

    private static PayCommand pay(PayScene scene) {
        return new PayCommand(META, scene, new Money(100, "CNY"), "s", "O1", null, "openid", null);
    }

    private static Invocation inv(ChannelAccountContext ctx, ai.neargo.shop.pay.channel.api.command.ChannelCommand c) {
        return Invocation.start(new ChannelKey(null, ctx.payChannel(), ctx.accessMode(), c.scene(), c.operation()),
                ctx, c);
    }

    private static ChannelProductCatalog emptyCatalog() {
        return new ChannelProductCatalog() {
            @Override
            public Set<ChannelKey> enabledPayKeys() {
                return Set.of();
            }

            @Override
            public boolean payEnabled(ChannelKey key) {
                return true;
            }
        };
    }
}
