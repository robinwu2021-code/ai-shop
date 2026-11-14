package ai.neargo.shop.spi.resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Evidence serialization must work on the base classpath without merchant or MyBatis. */
class ResourcePlanContractTest {
    private static final String TYPE = "ai.neargo.shop.spi.resource.ResourcePlanContract";
    private static final String JSON = "{\"schemaVersion\":1,\"demands\":[{\"resourceNo\":\"R\","
            + "\"sourceLineRef\":\"L\",\"quantity\":1,\"intervals\":[{\"startAt\":\"2026-10-21T10:00:00Z\","
            + "\"endAt\":\"2026-10-21T10:05:00Z\"}]}],\"qualifications\":[{\"resourceNo\":\"R\","
            + "\"sourceLineRef\":\"L\",\"goodsNo\":\"G\",\"skuNo\":null,\"required\":true}]}";

    private Object call(String method, Object... args) {
        Class<?> type = assertDoesNotThrow(() -> Class.forName(TYPE));
        try {
            var target = Arrays.stream(type.getMethods()).filter(value -> value.getName().equals(method))
                    .findFirst().orElseThrow();
            return target.invoke(null, args);
        } catch (InvocationTargetException ex) {
            if (ex.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw new AssertionError(ex.getCause());
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }

    private Object field(Object value, String name) {
        return assertDoesNotThrow(() -> value.getClass().getMethod(name).invoke(value));
    }

    @Test
    void exactBytesRoundTripWithoutMerchantOrPersistence() {
        var plan = call("decode", JSON);
        assertThat(call("encode", field(plan, "demands"), field(plan, "qualifications"))).isEqualTo(JSON);
        assertThatThrownBy(() -> Class.forName("ai.neargo.shop.merchant.policy.ResourceDemandNormalizer"))
                .isInstanceOf(ClassNotFoundException.class);
        assertThatThrownBy(() -> Class.forName("com.baomidou.mybatisplus.core.mapper.BaseMapper"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void digestMatchesFrozenLengthPrefixedUtf8Bytes() {
        assertThat(call("hash", JSON)).isEqualTo("f2495ee3539b985a9823c061c96eb831aa4248e561e6bf8df06f54dded765e77");
        assertThat(call("hash", " " + JSON)).isNotEqualTo(call("hash", JSON));
    }

    @Test
    void strictSchemaRejectsUnknownDuplicateTrailingAndWrongTypes() {
        call("decode", JSON);
        for (String invalid : List.of(JSON.replace("\"quantity\":1", "\"quantity\":1,\"hidden\":true"),
                JSON.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"), JSON + "{}",
                JSON.replace("\"quantity\":1", "\"quantity\":\"1\""), JSON.replace("\"skuNo\":null,", ""),
                JSON.replace("10:05:00Z", "09:55:00Z"), JSON.replace("10:00:00Z", "10:00:00.000001Z"))) {
            assertThatThrownBy(() -> call("decode", invalid)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void allDecodedCollectionsAreImmutable() {
        var plan = call("decode", JSON);
        var demands = (List<?>) field(plan, "demands");
        assertThatThrownBy(demands::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(((List<?>) field(demands.getFirst(), "intervals"))::clear)
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(((List<?>) field(plan, "qualifications"))::clear)
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rawIntervalAndExemptionRemainIndependentEvidence() {
        var hash = call("hash", JSON);
        assertThat(call("hash", JSON.replace("10:00:00Z", "10:00:01Z"))).isNotEqualTo(hash);
        var exempt = JSON.replace("\"G\"", "null").replace("\"required\":true", "\"required\":false");
        assertThat(call("hash", exempt)).isNotEqualTo(hash);
    }
}
