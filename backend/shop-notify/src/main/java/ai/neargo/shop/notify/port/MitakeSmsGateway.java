package ai.neargo.shop.notify.port;

import ai.neargo.shop.common.Phones;
import ai.neargo.shop.common.otp.OtpScenes;
import ai.neargo.shop.spi.notify.SendResult;
import ai.neargo.shop.spi.notify.SmsPort;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** 三竹单条短信。受理成功并不等于手机已送达；不记录请求/响应原文，不自动重发。 */
@Component("smsGateway")
@ConditionalOnProperty(name = "shop.sms.stub", havingValue = "false")
@ConditionalOnExpression("'${shop.sms.provider:ali}' == 'mitake'")
public class MitakeSmsGateway implements SmsPort {
    private static final Set<String> ACCEPTED = Set.of("0", "1", "2", "4");
    private static final Set<String> TEMPORARY_REJECTION = Set.of("a", "b", "l", "r");
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final URI endpoint;
    private final String username;
    private final String password;
    private final Map<String, String> otpTemplates;

    public MitakeSmsGateway(@Value("${shop.sms.mitake.config-file:}") String configFile,
            @Value("${shop.sms.mitake.endpoint:https://smsapi.mitake.com.tw/api/mtk/SmSend?CharsetURL=UTF-8}") String endpoint,
            Environment environment) {
        this.endpoint = URI.create(endpoint);
        boolean local = Set.of("127.0.0.1", "localhost", "[::1]").contains(this.endpoint.getHost() == null ? "" : this.endpoint.getHost());
        if (!("https".equals(this.endpoint.getScheme()) || (local && "http".equals(this.endpoint.getScheme())))
                || this.endpoint.getUserInfo() != null || this.endpoint.getFragment() != null
                || !"CharsetURL=UTF-8".equals(this.endpoint.getRawQuery())) {
            throw new IllegalStateException("Invalid Mitake endpoint: require HTTPS (or loopback HTTP) and CharsetURL=UTF-8");
        }
        if (configFile == null || configFile.isBlank())
            throw new IllegalStateException("Missing SHOP_SMS_MITAKE_CONFIG_FILE");
        try {
            var config = JsonMapper.builder().build().readTree(Files.readString(Path.of(configFile)));
            username = config.path("username").asText("");
            password = config.path("password").asText("");
        } catch (IOException | RuntimeException e) {
            // JSON/文件异常可能带凭据片段，不透出原异常或 cause。
            throw new IllegalStateException("Unable to read Mitake private credentials");
        }
        if (username.isBlank() || password.isBlank())
            throw new IllegalStateException("Missing Mitake username/password in private configuration");
        var defaults = Map.of(
                OtpScenes.LOGIN, "【NearShop】登入驗證碼：{code}，請勿提供給他人。",
                OtpScenes.PASSWORD_RESET, "【NearShop】重設密碼驗證碼：{code}，請勿提供給他人。",
                OtpScenes.PHONE_CHANGE, "【NearShop】變更手機驗證碼：{code}，請勿提供給他人。",
                OtpScenes.POS_PIN_RECOVERY, "【NearShop】收銀 PIN 重設驗證碼：{code}，請勿提供給他人。",
                OtpScenes.ORDER_PICKUP_CONFIRM, "【NearShop】取貨確認驗證碼：{code}，請於確認取貨時使用。");
        var templates = new HashMap<String, String>();
        defaults.forEach((scene, text) -> {
            String template = environment.getProperty("shop.sms.mitake.otp-scenes." + scene, text);
            if (!template.contains("{code}")) throw new IllegalStateException("Mitake OTP template requires {code}: " + scene);
            templates.put(scene, template);
        });
        otpTemplates = Map.copyOf(templates);
    }

    @Override public SendResult sendOtp(String phone, String code) {
        return sendOtpForScene(phone, code, OtpScenes.LOGIN, null, null);
    }

    @Override public void requireOtpScene(String scene) {
        if (!otpTemplates.containsKey(scene)) throw new SmsException("otp_scene_unconfigured", false);
    }

    @Override public SendResult sendOtpForScene(String phone, String code, String scene, String bizType, String operatorNo) {
        requireOtpScene(scene);
        String mobile = Phones.taiwanMobile(phone);
        if (mobile == null) throw new SmsException("mitake_invalid_tw_mobile", false);
        String content = otpTemplates.get(scene).replace("{code}", code);
        var params = Map.of("username", username, "password", password, "dstaddr", mobile,
                "smbody", content);
        String form = params.entrySet().stream().map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(Collectors.joining("&"));
        HttpResponse<String> response;
        try {
            response = http.send(HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8)).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new SmsException("mitake_transport_outcome_unknown", false, content);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SmsException("mitake_transport_interrupted", false, content);
        }
        if (response.statusCode() != 200) throw new SmsException("mitake_http_failure", false, content);
        try {
            SendResult result = receipt(response.body(), scene);
            return new SendResult(result.providerMsgId(), result.templateCode(), content);
        } catch (SmsException e) {
            throw new SmsException(e.getMessage(), e.retryable(), content);
        }
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    private static SendResult receipt(String body, String scene) {
        var fields = new HashMap<String, String>();
        int sections = 0;
        for (String line : body.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("[") && ++sections > 1) throw new SmsException("mitake_invalid_response", false);
            String[] pair = trimmed.split("=", 2);
            if (pair.length == 2 && Set.of("statuscode", "msgid").contains(pair[0])) {
                if (fields.putIfAbsent(pair[0], pair[1].trim()) != null) throw new SmsException("mitake_invalid_response", false);
            }
        }
        String status = fields.getOrDefault("statuscode", "");
        if (!status.matches("[0-9a-z*?]")) throw new SmsException("mitake_invalid_response", false);
        if (!ACCEPTED.contains(status))
            throw new SmsException("mitake_rejected:" + status, TEMPORARY_REJECTION.contains(status));
        String msgId = fields.getOrDefault("msgid", "");
        if (!msgId.matches("#?[0-9]{1,20}")) throw new SmsException("mitake_invalid_response", false);
        return SendResult.of(msgId, "MITAKE_" + scene);
    }

    @Override public SendResult sendOrderPaid(String phone, String subOrderNo, String amountYuan) {
        throw new SmsException("tpl_unconfigured", false);
    }
    @Override public SendResult sendAfterSaleApplied(String phone, String afterSaleNo) {
        throw new SmsException("tpl_unconfigured", false);
    }
    @Override public SendResult sendShipToRecipient(String phone, String receiverName, String trackUrl) {
        throw new SmsException("tpl_unconfigured", false);
    }
}
