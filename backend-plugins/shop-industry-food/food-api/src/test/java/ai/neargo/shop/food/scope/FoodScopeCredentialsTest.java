package ai.neargo.shop.food.scope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.food.scope.FoodScopeCredentials.Claims;
import ai.neargo.shop.food.scope.FoodScopeCredentials.Realm;
import ai.neargo.shop.food.scope.FoodScopeCredentials.Rejected;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** TDD-餐饮系统-内部调用签名凭证 AC2 / AC3 / AC4 / AC6 / AC7 / AC8 的编解码与规则部分。 */
class FoodScopeCredentialsTest {
    private static final byte[] KEY = FoodScopeCredentials.key("k".repeat(32));
    private static final byte[] OLD = FoodScopeCredentials.key("o".repeat(32));
    private static final long NOW = 1_800_000_000L;
    private static final byte[] BODY =
            "{\"identity\":{\"entityNo\":\"E1\",\"storeNo\":\"S1\",\"actorNo\":\"U1\"},\"key\":\"k\"}"
                    .getBytes(StandardCharsets.UTF_8);
    private static final String DIGEST = FoodScopeCredentials.digest("POST", "/internal/food/carts/create", BODY);

    private static Claims staff(long exp) {
        return new Claims(Realm.STAFF, "E1", List.of("S1"), "U1", exp, DIGEST);
    }

    private static int status(Runnable r) {
        try {
            r.run();
            return 200;
        } catch (Rejected e) {
            return e.status();
        }
    }

    @Test
    void signedClaimsRoundTrip() {
        String header = FoodScopeCredentials.sign(staff(NOW + 60), KEY);
        assertThat(FoodScopeCredentials.verify(header, List.of(KEY), DIGEST, NOW)).isEqualTo(staff(NOW + 60));
    }

    @Test
    void ac1MissingMalformedOrForeignSignatureIs401() {
        assertThat(status(() -> FoodScopeCredentials.verify(null, List.of(KEY), DIGEST, NOW))).isEqualTo(401);
        assertThat(status(() -> FoodScopeCredentials.verify("v1.x", List.of(KEY), DIGEST, NOW))).isEqualTo(401);
        String foreign = FoodScopeCredentials.sign(staff(NOW + 60), FoodScopeCredentials.key("x".repeat(32)));
        assertThat(status(() -> FoodScopeCredentials.verify(foreign, List.of(KEY), DIGEST, NOW))).isEqualTo(401);
        // 改声明但不重签：签名对不上
        String good = FoodScopeCredentials.sign(staff(NOW + 60), KEY);
        String forgedPayload = FoodScopeCredentials.sign(
                new Claims(Realm.SYSTEM, null, List.of(), null, NOW + 60, DIGEST), KEY).split("\\.")[1];
        String forged = "v1." + forgedPayload + "." + good.split("\\.")[2];
        assertThat(status(() -> FoodScopeCredentials.verify(forged, List.of(KEY), DIGEST, NOW))).isEqualTo(401);
    }

    @Test
    void ac2ExpiredOrTooLongLivedIs401() {
        String expired = FoodScopeCredentials.sign(staff(NOW - FoodScopeCredentials.SKEW_SECONDS), KEY);
        assertThat(status(() -> FoodScopeCredentials.verify(expired, List.of(KEY), DIGEST, NOW))).isEqualTo(401);
        String withinSkew = FoodScopeCredentials.sign(staff(NOW - 5), KEY);
        assertThat(status(() -> FoodScopeCredentials.verify(withinSkew, List.of(KEY), DIGEST, NOW))).isEqualTo(200);
        String tooLong = FoodScopeCredentials.sign(staff(NOW + FoodScopeCredentials.MAX_AHEAD_SECONDS + 1), KEY);
        assertThat(status(() -> FoodScopeCredentials.verify(tooLong, List.of(KEY), DIGEST, NOW))).isEqualTo(401);
    }

    @Test
    void ac3TamperedBodyOrPathIs401() {
        String header = FoodScopeCredentials.sign(staff(NOW + 60), KEY);
        byte[] other = new String(BODY, StandardCharsets.UTF_8).replace("S1", "S2").getBytes(StandardCharsets.UTF_8);
        String otherBody = FoodScopeCredentials.digest("POST", "/internal/food/carts/create", other);
        String otherPath = FoodScopeCredentials.digest("POST", "/internal/food/carts/archive-line", BODY);
        assertThat(status(() -> FoodScopeCredentials.verify(header, List.of(KEY), otherBody, NOW))).isEqualTo(401);
        assertThat(status(() -> FoodScopeCredentials.verify(header, List.of(KEY), otherPath, NOW))).isEqualTo(401);
    }

    @Test
    void ac6PreviousKeyStillVerifies() {
        String byOld = FoodScopeCredentials.sign(staff(NOW + 60), OLD);
        assertThat(status(() -> FoodScopeCredentials.verify(byOld, List.of(KEY, OLD), DIGEST, NOW))).isEqualTo(200);
        assertThat(status(() -> FoodScopeCredentials.verify(byOld, List.of(KEY), DIGEST, NOW))).isEqualTo(401);
    }

    @Test
    void ac7MissingOrShortKeyFailsClosed() {
        assertThat(FoodScopeCredentials.key("")).isNull();
        assertThat(FoodScopeCredentials.key("short")).isNull();
        assertThat(status(() -> FoodScopeCredentials.sign(staff(NOW + 60), null))).isEqualTo(401);
        String header = FoodScopeCredentials.sign(staff(NOW + 60), KEY);
        assertThat(status(() -> FoodScopeCredentials.verify(header, List.of(), DIGEST, NOW))).isEqualTo(401);
    }

    @Test
    void ac4StaffBodyMustMatchEntityStoresAndActor() {
        var body = FoodScopeBodyScanner.scan(BODY);
        assertThat(status(() -> FoodScopeCredentials.requireBodyMatches(staff(NOW), body))).isEqualTo(200);
        assertThat(status(() -> FoodScopeCredentials.requireBodyMatches(
                new Claims(Realm.STAFF, "E2", List.of("S1"), "U1", NOW, DIGEST), body))).isEqualTo(403);
        assertThat(status(() -> FoodScopeCredentials.requireBodyMatches(
                new Claims(Realm.STAFF, "E1", List.of("S9"), "U1", NOW, DIGEST), body))).isEqualTo(403);
        assertThat(status(() -> FoodScopeCredentials.requireBodyMatches(
                new Claims(Realm.STAFF, "E1", List.of("S1"), "U2", NOW, DIGEST), body))).isEqualTo(403);
        // 嵌套里第二个门店也要在声明内
        var two = FoodScopeBodyScanner.scan(("{\"a\":{\"storeNo\":\"S1\"},\"b\":[{\"store\":\"S2\"}]}")
                .getBytes(StandardCharsets.UTF_8));
        assertThat(two.stores()).containsExactlyInAnyOrder("S1", "S2");
        assertThat(status(() -> FoodScopeCredentials.requireBodyMatches(staff(NOW), two))).isEqualTo(403);
    }

    @Test
    void ac8ConsumerBuyerMustBeTheSignedConsumer() {
        var body = FoodScopeBodyScanner.scan("{\"access\":{\"sessionNo\":\"G1\",\"buyerNo\":\"B1\"}}"
                .getBytes(StandardCharsets.UTF_8));
        assertThat(status(() -> FoodScopeCredentials.requireBodyMatches(
                new Claims(Realm.CONSUMER, null, List.of(), "B1", NOW, DIGEST), body))).isEqualTo(200);
        assertThat(status(() -> FoodScopeCredentials.requireBodyMatches(
                new Claims(Realm.CONSUMER, null, List.of(), "B2", NOW, DIGEST), body))).isEqualTo(403);
        assertThat(status(() -> FoodScopeCredentials.requireBodyMatches(
                new Claims(Realm.CONSUMER, null, List.of(), null, NOW, DIGEST), body))).isEqualTo(403);
        var anonymous = FoodScopeBodyScanner.scan("{\"access\":{\"sessionNo\":\"G1\"}}".getBytes(StandardCharsets.UTF_8));
        assertThat(status(() -> FoodScopeCredentials.requireBodyMatches(
                new Claims(Realm.CONSUMER, null, List.of(), null, NOW, DIGEST), anonymous))).isEqualTo(200);
    }
}
