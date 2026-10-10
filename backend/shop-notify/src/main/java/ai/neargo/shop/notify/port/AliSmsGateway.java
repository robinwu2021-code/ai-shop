package ai.neargo.shop.notify.port;

import ai.neargo.shop.spi.notify.SendResult;
import ai.neargo.shop.spi.notify.SmsPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 阿里云短信（dysmsapi 2017-05-25）。
 *
 * <p><b>为什么不引官方 SDK</b>：本项目一律 {@code mvn -o} 离线构建，而那套 SDK
 * 及其依赖树不在本地仓里 —— 引进来会让每个人的构建当场挂掉。
 * 它的签名是 HMAC-SHA1 + 参数排序，用 JDK 自带的东西写完不到三十行，
 * 且已对着真实接口验证发通过（2026-08-13，模板 {@code SMS_474945291}）。
 *
 * <p><b>启动即校验凭据</b>：缺 AK/SK/模板号时**直接起不来**，不静默退回桩。
 * 静默退回的表现是「短信发送成功」的日志照常出现，而没有一条真的发出去 ——
 * 要等用户投诉才发现，那时已经过了几天。
 *
 * <p><b>三个不写进日志的东西</b>：AccessKeySecret、验证码明文、完整手机号。
 */
@Component("smsGateway")
@ConditionalOnProperty(name = "shop.sms.stub", havingValue = "false")
public class AliSmsGateway implements SmsPort {

    private static final Logger log = LoggerFactory.getLogger(AliSmsGateway.class);

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    /** 从 JSON 里取字段。**不引 JSON 库**：响应只有四个平铺字段，正则够且不会有嵌套 */
    private static final Pattern FIELD = Pattern.compile("\"%s\"\\s*:\\s*\"([^\"]*)\"");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    private final String endpoint;
    private final String accessKeyId;
    private final String accessKeySecret;
    private final String signName;
    private final String otpTemplate;
    /** 来单提醒模板。**可以为空**（阿里云审批没下来），见 requireConfigured 的注释 */
    private final String orderPaidTemplate;
    /** 发货触达模板。同样**可以为空**（审批没下来），缺它时 sendShipToRecipient 直接失败留痕 */
    private final String shipTemplate;

    public AliSmsGateway(@Value("${shop.sms.ali.endpoint:dysmsapi.aliyuncs.com}") String endpoint,
                         @Value("${shop.sms.ali.access-key-id:}") String accessKeyId,
                         @Value("${shop.sms.ali.access-key-secret:}") String accessKeySecret,
                         @Value("${shop.sms.ali.sign:}") String signName,
                         @Value("${shop.sms.ali.templates.otp:}") String otpTemplate,
                         @Value("${shop.sms.ali.templates.order-paid:}") String orderPaidTemplate,
                         @Value("${shop.sms.ali.templates.ship:}") String shipTemplate) {
        this.endpoint = endpoint;
        this.accessKeyId = accessKeyId;
        this.accessKeySecret = accessKeySecret;
        this.signName = signName;
        this.otpTemplate = otpTemplate;
        this.orderPaidTemplate = orderPaidTemplate == null ? "" : orderPaidTemplate.trim();
        this.shipTemplate = shipTemplate == null ? "" : shipTemplate.trim();
        requireConfigured();
        log.info("[sms] 阿里云短信已启用 endpoint={} sign={} otpTemplate={} orderPaidTemplate={}",
                endpoint, signName, otpTemplate,
                this.orderPaidTemplate.isBlank() ? "(未报备，来单短信发不出去)" : this.orderPaidTemplate);
        log.info("[sms] 发货触达模板 shipTemplate={}",
                this.shipTemplate.isBlank() ? "(未报备，发货短信发不出去)" : this.shipTemplate);
    }

    /**
     * **缺一项就起不来**，且消息里点名缺的是哪一个环境变量。
     *
     * <p>只说「配置不全」的话，运维要去翻代码才知道该设哪个变量 ——
     * 而这类问题总是发生在发布窗口里。
     */
    private void requireConfigured() {
        require(accessKeyId, "ALI_SMS_AK");
        require(accessKeySecret, "ALI_SMS_SK");
        require(signName, "ALI_SMS_SIGN");
        require(otpTemplate, "ALI_SMS_TPL_OTP（阿里云后台报备通过的验证码模板号，形如 SMS_1234567）");
        /*
         * **ALI_SMS_TPL_ORDER_PAID 刻意不在这里**（TDD-来单四渠道与商家通知设置 §2.3）。
         *
         * 它与验证码那个的区别不是重要程度，是**可得性**：阿里云的模板报备是人工审批，
         * 几小时到一天。把它列成必需的后果是「审批还没下来，而生产一重启就起不来」——
         * 一条锦上添花的通知把整个服务拖下线，这个代价和它的价值完全不成比例。
         *
         * 缺它的代价限制在一条通道内：sendOrderPaid 返回失败并写一行
         * SMS/FAILED/tpl_unconfigured，其余三条出口照发。
         */
    }

    private static void require(String v, String envName) {
        if (v == null || v.isBlank()) {
            throw new IllegalStateException(
                    "短信通道已开启（shop.sms.stub=false）但缺少配置：" + envName
                            + " —— 不配就发不出短信，这里直接失败而不是退回桩，"
                            + "退回桩会让「已发送」的日志照常出现而一条都没真的发出去");
        }
    }

    @Override
    public SendResult sendOtp(String phone, String code) {
        Map<String, String> p = new TreeMap<>();
        p.put("Action", "SendSms");
        p.put("Version", "2017-05-25");
        p.put("Format", "JSON");
        p.put("RegionId", "cn-hangzhou");
        p.put("AccessKeyId", accessKeyId);
        p.put("SignatureMethod", "HMAC-SHA1");
        p.put("SignatureVersion", "1.0");
        p.put("SignatureNonce", UUID.randomUUID().toString());
        p.put("Timestamp", TS.format(Instant.now()));
        p.put("PhoneNumbers", phone);
        p.put("SignName", signName);
        p.put("TemplateCode", otpTemplate);
        p.put("TemplateParam", "{\"code\":\"" + code + "\"}");

        String canonical = canonicalize(p);
        p.put("Signature", sign("POST&%2F&" + enc(canonical)));

        String body = p.entrySet().stream()
                .map(e -> enc(e.getKey()) + "=" + enc(e.getValue()))
                .reduce((a, b) -> a + "&" + b).orElseThrow();

        HttpResponse<String> resp;
        try {
            resp = http.send(HttpRequest.newBuilder(URI.create("https://" + endpoint + "/"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            // 网络问题**可重试**：调用方据此决定是提示「稍后再试」还是「号码有问题」
            throw new SmsException("短信通道网络失败：" + e.getMessage(), true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SmsException("短信发送被中断", true);
        }

        String respCode = field(resp.body(), "Code");
        if (!"OK".equals(respCode)) {
            String msg = field(resp.body(), "Message");
            /*
             * 业务码不重试：模板不存在、签名未报备、手机号格式错、余额不足 ——
             * 这些重试一万次也是同一个结果，重试只会把日志刷满而掩盖真正的原因。
             */
            throw new SmsException("阿里云拒绝：" + respCode + " " + msg, false);
        }
        return SendResult.of(field(resp.body(), "BizId"), otpTemplate);
    }

    /**
     * 来单提醒。**不抛** —— 理由见 {@link SmsPort#sendOrderPaid}：
     * 它只是四条出口之一，抛出去会连带让站内信被重投发第二遍。
     *
     * <p>模板没报备（空）时直接返回失败。<b>这一条不去调阿里云</b>：
     * 不带 TemplateCode 的请求会被拒成一个含糊的参数错误，
     * 而真正的原因「模板还没报备」就被埋在那条消息里了。
     */
    @Override
    public SendResult sendOrderPaid(String phone, String subOrderNo, String amountYuan) {
        if (orderPaidTemplate.isBlank()) {
            log.warn("[sms] 来单短信发不出去：ALI_SMS_TPL_ORDER_PAID 未配（阿里云模板还没报备）");
            throw new SmsException("tpl_unconfigured", false);
        }
        Map<String, String> p = common();
        p.put("PhoneNumbers", phone);
        p.put("SignName", signName);
        /*
         * **只发金额，不发订单号**（2026-10-10）。阿里云通知短信的变量只认语义类型
         * （money / name / time / address / phoneNumber…），而订单号 SUB2026… 不符合任何一种——
         * 探测过一圈类型码，没有「字符串/订单号」这种通用类型，所以它根本进不了模板。
         * 订单号在站内信 / 企微群 / 邮件里都有完整详情，短信只做「有新单 + 金额」的提醒。
         * subOrderNo 仍留作参数：日志（TPL_SMS_ORDER_PAID）按它记。
         * 模板 SMS_512580867：您有新订单，实付金额${amt}元，请登录虹选商家端查看订单并及时备货。
         */
        p.put("TemplateCode", orderPaidTemplate);
        p.put("TemplateParam", "{\"amt\":\"" + amountYuan + "\"}");
        p.put("Signature", sign("POST&%2F&" + enc(canonicalize(p))));
        try {
            HttpResponse<String> resp = post(p);
            String respCode = field(resp.body(), "Code");
            if (!"OK".equals(respCode)) {
                throw new SmsException("阿里云拒绝：" + respCode + " "
                        + field(resp.body(), "Message"), false);
            }
            return SendResult.of(field(resp.body(), "BizId"), orderPaidTemplate);
        } catch (java.io.IOException e) {
            throw new SmsException("短信通道网络失败：" + e.getMessage(), true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SmsException("短信发送被中断", true);
        }
    }

    /**
     * 发货触达收件人。形状同 {@link #sendOrderPaid}：**不抛给 outbox**（调用方吞）、
     * 模板没报备（空）时直接失败不调阿里云。
     *
     * <p>模板带一个 {@code ${url}} 变量，值是完整短链 {@code https://s.hxmall.top/<code>}。
     * <b>短链域名要在阿里云短信后台报白名单</b>，否则带链接的模板审核不过（部署步骤，见设计 §8 决策 D）。
     */
    @Override
    public SendResult sendShipToRecipient(String phone, String trackUrl) {
        if (shipTemplate.isBlank()) {
            log.warn("[sms] 发货短信发不出去：ALI_SMS_TPL_SHIP 未配（阿里云模板还没报备）");
            throw new SmsException("tpl_unconfigured", false);
        }
        Map<String, String> p = common();
        p.put("PhoneNumbers", phone);
        p.put("SignName", signName);
        p.put("TemplateCode", shipTemplate);
        p.put("TemplateParam", "{\"url\":\"" + trackUrl + "\"}");
        p.put("Signature", sign("POST&%2F&" + enc(canonicalize(p))));
        try {
            HttpResponse<String> resp = post(p);
            String respCode = field(resp.body(), "Code");
            if (!"OK".equals(respCode)) {
                throw new SmsException("阿里云拒绝：" + respCode + " "
                        + field(resp.body(), "Message"), false);
            }
            return SendResult.of(field(resp.body(), "BizId"), shipTemplate);
        } catch (java.io.IOException e) {
            throw new SmsException("短信通道网络失败：" + e.getMessage(), true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SmsException("短信发送被中断", true);
        }
    }

    /** 两条用途共用的公共参数（签名所需的那一套） */
    private Map<String, String> common() {
        Map<String, String> p = new TreeMap<>();
        p.put("Action", "SendSms");
        p.put("Version", "2017-05-25");
        p.put("Format", "JSON");
        p.put("RegionId", "cn-hangzhou");
        p.put("AccessKeyId", accessKeyId);
        p.put("SignatureMethod", "HMAC-SHA1");
        p.put("SignatureVersion", "1.0");
        p.put("SignatureNonce", UUID.randomUUID().toString());
        p.put("Timestamp", TS.format(Instant.now()));
        return p;
    }

    private HttpResponse<String> post(Map<String, String> p)
            throws java.io.IOException, InterruptedException {
        String body = p.entrySet().stream()
                .map(e -> enc(e.getKey()) + "=" + enc(e.getValue()))
                .reduce((a, b) -> a + "&" + b).orElseThrow();
        return http.send(HttpRequest.newBuilder(URI.create("https://" + endpoint + "/"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** 阿里云要求：按 key 字典序，key 与 value 各自百分号编码后用 = 与 & 连接。 */
    private static String canonicalize(Map<String, String> sorted) {
        return sorted.entrySet().stream()
                .map(e -> enc(e.getKey()) + "=" + enc(e.getValue()))
                .reduce((a, b) -> a + "&" + b).orElse("");
    }

    private String sign(String stringToSign) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec((accessKeySecret + "&").getBytes(StandardCharsets.UTF_8),
                    "HmacSHA1"));
            return Base64.getEncoder()
                    .encodeToString(mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("短信签名失败", e);
        }
    }

    /**
     * RFC 3986。{@code URLEncoder} 把空格编成 {@code +}、不编 {@code ~}、把 {@code *}
     * 留着不编 —— 三处都与阿里云的口径不同，**任何一处不改都会一直返回签名错误**，
     * 而错误信息不会告诉你是哪一个字符。
     */
    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~");
    }

    private static String field(String json, String name) {
        Matcher m = Pattern.compile(FIELD.pattern().formatted(name)).matcher(json);
        return m.find() ? m.group(1) : null;
    }
}
