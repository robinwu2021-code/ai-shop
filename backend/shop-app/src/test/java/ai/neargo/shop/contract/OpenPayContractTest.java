package ai.neargo.shop.contract;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.api.guard.crypto.SensitiveFieldDecryptor;
import ai.neargo.shop.pay.api.open.OpenPaymentController;
import ai.neargo.shop.pay.api.open.OpenRefundController;
import ai.neargo.shop.pay.api.open.OpenStoreController;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.yaml.snakeyaml.Yaml;

/**
 * 开放 API 对外契约 {@code docs/api/openapi-pay-open.yaml}（开发计划 M3.9）与代码对得上：手写的契约最容易和实现走散，
 * 而它是交给接入方的那一份。路由、敏感字段、鉴权与签名头、错误码、推送信封 —— 任一边改了另一边没改就红。
 */
class OpenPayContractTest {

    private static Map<String, Object> spec;
    private static String text;

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void load() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("docs/api/openapi-pay-open.yaml"))) {
            root = root.getParent();
        }
        assertThat(root).as("docs/api/openapi-pay-open.yaml 不在仓库里").isNotNull();
        text = Files.readString(root.resolve("docs/api/openapi-pay-open.yaml"));
        spec = new Yaml().load(text);
    }

    /** 代码里开放 API 的 METHOD + 路径（类级 + 方法级 @RequestMapping 拼起来） */
    private static Set<String> liveRoutes() {
        Set<String> out = new HashSet<>();
        for (Class<?> c : List.of(OpenPaymentController.class, OpenRefundController.class, OpenStoreController.class)) {
            RequestMapping base = AnnotatedElementUtils.findMergedAnnotation(c, RequestMapping.class);
            String prefix = base == null || base.path().length == 0 ? "" : base.path()[0];
            for (Method m : c.getDeclaredMethods()) {
                RequestMapping rm = AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class);
                if (rm == null) {
                    continue;
                }
                String sub = rm.path().length == 0 ? "" : rm.path()[0];
                out.add(rm.method()[0].name() + " " + prefix + sub);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> documentedRoutes() {
        Set<String> out = new HashSet<>();
        ((Map<String, Map<String, Object>>) spec.get("paths")).forEach((path, ops) -> ops.keySet().stream()
                .filter(k -> Set.of("get", "post", "put", "delete").contains(k))
                .forEach(k -> out.add(k.toUpperCase() + " " + path)));
        return out;
    }

    @Test
    @DisplayName("★★★ 路由一一对应：代码里有的契约里都有，契约里写的代码里都有")
    void routesMatch() {
        Set<String> live = liveRoutes();
        assertThat(live).as("别让断言恒真").hasSizeGreaterThanOrEqualTo(8);
        assertThat(documentedRoutes()).containsExactlyInAnyOrderElementsOf(live);
    }

    /** 契约里标了 x-sensitive 的属性路径（相对请求体根） */
    @SuppressWarnings("unchecked")
    private static Set<String> sensitiveInSpec() {
        Set<String> out = new HashSet<>();
        Map<String, Object> schemas = (Map<String, Object>) ((Map<String, Object>) spec.get("components")).get("schemas");
        walk(schemas, (Map<String, Object>) schemas.get("CreateOrderRequest"), "", out);
        walk(schemas, (Map<String, Object>) schemas.get("RefundRequest"), "", out);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void walk(Map<String, Object> schemas, Map<String, Object> schema, String prefix, Set<String> out) {
        if (schema == null) {
            return;
        }
        if (schema.containsKey("$ref")) {
            String ref = (String) schema.get("$ref");
            walk(schemas, (Map<String, Object>) schemas.get(ref.substring(ref.lastIndexOf('/') + 1)), prefix, out);
            return;
        }
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        if (props == null) {
            return;
        }
        props.forEach((name, p) -> {
            Map<String, Object> prop = (Map<String, Object>) p;
            String path = prefix.isEmpty() ? name : prefix + "." + name;
            if (Boolean.TRUE.equals(prop.get("x-sensitive"))) {
                out.add(path);
            }
            walk(schemas, prop, path, out);
        });
    }

    @Test
    @DisplayName("★★★ x-sensitive 与守卫解密的字段清单一致（漏标 = 接入方传明文被 400；多标 = 解不开）")
    void sensitiveFieldsMatchTheGuard() {
        Set<String> guard = new HashSet<>();
        SensitiveFieldDecryptor.FIELDS.values().forEach(guard::addAll);
        assertThat(sensitiveInSpec()).containsExactlyInAnyOrderElementsOf(guard);
    }

    @Test
    @DisplayName("★★★ 鉴权与签名：请求头、响应签名头、推送信封都写在契约里")
    void securityAndSigningDocumented() {
        for (String h : List.of("X-App-Id", "X-Timestamp", "X-Nonce", "X-Signature", "X-Key-Serial", "X-Encrypt-Serial")) {
            assertThat(text).as("请求头 %s", h).contains("name: " + h);
        }
        assertThat(text).contains("AEAD_AES_256_GCM").contains("associatedData").contains("x-signing");
        assertThat(spec).containsKey("webhooks");
    }

    @Test
    @DisplayName("★★ 细分错误码（M3.7）都在契约的错误码表里，码值与代码一致")
    void errorCodesDocumented() {
        List<ErrorCode> codes = new ArrayList<>(List.of(ErrorCode.UNAUTHORIZED, ErrorCode.FORBIDDEN,
                ErrorCode.PAY_OPEN_TIMESTAMP_EXPIRED, ErrorCode.PAY_OPEN_REPLAYED, ErrorCode.PAY_OPEN_FIELD_ENCRYPT_REQUIRED,
                ErrorCode.PAY_OPEN_FIELD_DECRYPT_FAILED, ErrorCode.PAY_KEY_SERVICE_UNAVAILABLE));
        for (ErrorCode c : codes) {
            assertThat(text).as("错误码 %s", c).contains("code: " + c.code()).contains(c.name());
        }
    }

    @Test
    @DisplayName("★★ 测试向量（API 清单 §7）写进契约示例：P1 / P2 / P3 / P4 的结果都在")
    void vectorsDocumented() {
        assertThat(text).contains("nsNjeNX+z0xMF/hIgsbFLBkbDWOFBAIYwMnKD+fQVxU=")
                .contains("umstQTPXokyyX02RxHKAeiNbQUc1VAAvHykNvWaInfQ=")
                .contains("obLD1OX2BxgpOktc59/SAi1E7uW27KsqhePcgjLC58SwEbcblEo=")
                .contains("Dw4NDAsKCQgHBgUE");
    }
}
