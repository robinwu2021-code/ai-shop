package ai.neargo.shop.spi.pay;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 下单之后要端上做的下一步（渠道接入统一封装设计 §2.3；TDD-支付服务-W2渠道层补完 S1）。
 *
 * <p><b>为什么是 sealed 而不是一个 {@code Map<String, String>}</b>：今天只有微信 JSAPI 一种形状，一张扁平 map 够用；
 * 蓝新要的是「自动提交的表单」、LINE Pay 是「跳转地址」、微信 NATIVE 是「二维码内容」—— 都塞进一张 map 的话，
 * 收银台只能靠「有没有 {@code formAction} 这个键」猜该怎么做，猜错的表现是用户点了没反应。
 * 封闭的六种让「新渠道要端上做一件新事」在编译期就逼着端上一起改。
 *
 * <p>{@link #asParams()} 是今天端上收到的那张扁平参数表，<b>逐字不变</b>（键名、顺序、值）：
 * 端上（{@code requestPayment(init.payParams)}）拿到什么就原样传给渠道 SDK，服务端不翻译。
 */
public sealed interface PayInstruction {

    /** 今天端上收到的扁平参数（下单接口的 {@code payParams}） */
    Map<String, String> asParams();

    /** 不需要端上做什么（免单、退款受理、同步扣款） */
    static PayInstruction none() {
        return NoAction.INSTANCE;
    }

    /** 微信小程序 / 公众号 {@code requestPayment} 六件套：{@code appId timeStamp nonceStr package signType paySign} */
    record JsapiParams(Map<String, String> params) implements PayInstruction {
        public JsapiParams {
            params = ordered(params);
        }

        @Override
        public Map<String, String> asParams() {
            return params;
        }
    }

    /** App / 进程内模拟渠道的 SDK 参数（支付宝 {@code orderStr}、测试渠道的模拟单）：端上原样交给 SDK */
    record AppSdkParams(Map<String, String> params) implements PayInstruction {
        public AppSdkParams {
            params = ordered(params);
        }

        @Override
        public Map<String, String> asParams() {
            return params;
        }
    }

    /** 跳转渠道收银台（LINE Pay {@code paymentUrl.web}、PayBy 收银台） */
    record RedirectUrl(String url) implements PayInstruction {
        public RedirectUrl {
            Objects.requireNonNull(url, "url");
        }

        @Override
        public Map<String, String> asParams() {
            return Map.of("redirectUrl", url);
        }
    }

    /** 自动提交的表单（蓝新 MPG：{@code MerchantID / TradeInfo / TradeSha / Version} POST 到 {@code action}） */
    record FormPost(String action, Map<String, String> fields) implements PayInstruction {
        public FormPost {
            Objects.requireNonNull(action, "action");
            fields = ordered(fields);
        }

        @Override
        public Map<String, String> asParams() {
            Map<String, String> out = new LinkedHashMap<>(fields);
            out.put("formAction", action);
            return Collections.unmodifiableMap(out);
        }
    }

    /** 端上把内容画成二维码（微信 NATIVE {@code code_url}、终端屏显码） */
    record QrContent(String content) implements PayInstruction {
        public QrContent {
            Objects.requireNonNull(content, "content");
        }

        @Override
        public Map<String, String> asParams() {
            return Map.of("qrContent", content);
        }
    }

    /** 没有下一步。用 {@link PayInstruction#none()} 取 */
    record NoAction() implements PayInstruction {
        static final NoAction INSTANCE = new NoAction();

        @Override
        public Map<String, String> asParams() {
            return Map.of();
        }
    }

    /** 保序（端上与日志按插入顺序读）、只读；允许 null 值 —— 渠道偶尔给空字段，原样透传 */
    private static Map<String, String> ordered(Map<String, String> src) {
        return src == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(src));
    }
}
