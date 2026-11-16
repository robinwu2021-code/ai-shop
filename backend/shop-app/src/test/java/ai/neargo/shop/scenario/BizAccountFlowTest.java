package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.support.TestLogin;
import ai.neargo.shop.user.service.EmailOtpService;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Merchant App account centre (TDD-商户App统一登录与账号 endpoints 4–8, AC11–AC22).
 */
@SpringBootTest
@ActiveProfiles("test")
class BizAccountFlowTest {

    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper json;
    @Autowired private OtpStore otpStore;
    @Autowired private JdbcTemplate jdbc;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    private static String phone() {
        return "139" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
    }

    private static String email() {
        return "acct." + Long.toHexString(ThreadLocalRandom.current().nextLong()) + "@example.test";
    }

    private String smsCode(String phone) throws Exception {
        mvc().perform(post("/biz/auth/otp/send").contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"" + phone + "\"}"));
        return otpStore.peek(phone).orElseThrow(() -> new AssertionError("no SMS code for " + phone));
    }

    private String emailCode(String email) throws Exception {
        mvc().perform(post("/biz/auth/email-otp/send").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\"}"));
        return otpStore.peek(EmailOtpService.key(EmailOtpService.normalize(email)))
                .orElseThrow(() -> new AssertionError("no email code for " + email));
    }

    private JsonNode call(String method, String path, String token, String body) throws Exception {
        var req = ("GET".equals(method) ? get(path) : post(path).contentType(MediaType.APPLICATION_JSON)
                .content(body == null ? "{}" : body));
        if (token != null) req.header("Authorization", "Bearer " + token);
        return json.readTree(mvc().perform(req).andReturn().getResponse().getContentAsString());
    }

    private String userNo(String token) throws Exception {
        return call("GET", "/biz/auth/account", token, null).path("data").path("userNo").asString();
    }

    @Test
    @DisplayName("AC11 owner reads own credentials; verifyBy is PHONE when SMS can reach the phone")
    void ownerReadsOwnCredentials() throws Exception {
        String phone = phone();
        String token = TestLogin.merchantOwner(mvc(), json, otpStore, phone);
        JsonNode r = call("GET", "/biz/auth/account", token, null);
        assertThat(r.path("code").asInt()).as(r.toString()).isZero();
        JsonNode d = r.path("data");
        assertThat(d.path("phone").asString()).isEqualTo(phone);
        assertThat(d.path("principalNo").asString()).isEqualTo(d.path("userNo").asString());
        assertThat(d.path("phoneChangeVerifyBy").asString()).isEqualTo("PHONE");
        assertThat(d.path("phoneChangeAvailable").asBoolean()).isTrue();
        assertThat(d.path("deregisterAvailable").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("AC12–AC14 phone change keeps this session, ends the others, and the new phone signs in to the same account")
    void phoneChangeKeepsCurrentSessionOnly() throws Exception {
        String oldPhone = phone();
        String current = TestLogin.merchantOwner(mvc(), json, otpStore, oldPhone);
        String other = TestLogin.merchantOwner(mvc(), json, otpStore, oldPhone);
        String me = userNo(current);

        String newPhone = phone();
        String currentCode = smsCode(oldPhone);
        String newCode = smsCode(newPhone);
        JsonNode r = call("POST", "/biz/auth/phone/change", current,
                "{\"currentCode\":\"" + currentCode + "\",\"newPhone\":\"" + newPhone + "\",\"newCode\":\"" + newCode + "\"}");
        assertThat(r.path("code").asInt()).as(r.toString()).isZero();
        assertThat(r.path("data").path("account").path("phone").asString()).isEqualTo(newPhone);

        assertThat(call("GET", "/biz/auth/account", current, null).path("code").asInt()).isZero();
        assertThat(call("GET", "/biz/auth/account", other, null).path("code").asInt()).isNotZero();

        String again = TestLogin.merchantOwner(mvc(), json, otpStore, newPhone);
        assertThat(userNo(again)).isEqualTo(me);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM usr_account_change_log WHERE user_no=? AND action='PHONE_CHANGE'"
                + " AND after_masked NOT LIKE ?", Integer.class, me, "%" + newPhone + "%")).isEqualTo(1);
    }

    @Test
    @DisplayName("AC13 a phone that belongs to another account is refused with 10409; the same phone with 10458")
    void phoneTakenOrUnchanged() throws Exception {
        String mine = phone();
        String theirs = phone();
        String token = TestLogin.merchantOwner(mvc(), json, otpStore, mine);
        TestLogin.merchantOwner(mvc(), json, otpStore, theirs);

        JsonNode taken = call("POST", "/biz/auth/phone/change", token,
                "{\"currentCode\":\"" + smsCode(mine) + "\",\"newPhone\":\"" + theirs + "\",\"newCode\":\"" + smsCode(theirs) + "\"}");
        assertThat(taken.path("code").asInt()).as(taken.toString()).isEqualTo(10409);

        JsonNode same = call("POST", "/biz/auth/phone/change", token,
                "{\"currentCode\":\"111111\",\"newPhone\":\"" + mine + "\",\"newCode\":\"222222\"}");
        assertThat(same.path("code").asInt()).as(same.toString()).isEqualTo(10458);
    }

    @Test
    @DisplayName("AC12 a wrong current code is 10455 and nothing changes")
    void wrongCurrentCodeChangesNothing() throws Exception {
        String phone = phone();
        String token = TestLogin.merchantOwner(mvc(), json, otpStore, phone);
        String newPhone = phone();
        JsonNode r = call("POST", "/biz/auth/phone/change", token,
                "{\"currentCode\":\"000000\",\"newPhone\":\"" + newPhone + "\",\"newCode\":\"" + smsCode(newPhone) + "\"}");
        assertThat(r.path("code").asInt()).as(r.toString()).isEqualTo(10455);
        assertThat(call("GET", "/biz/auth/account", token, null).path("data").path("phone").asString()).isEqualTo(phone);
    }

    @Test
    @DisplayName("AC15 / D3 first email bind needs only the new code; replacing needs the current email's code; same email is 10475")
    void emailBindAndReplace() throws Exception {
        String token = TestLogin.merchantOwner(mvc(), json, otpStore, phone());
        String first = email();
        JsonNode bound = call("POST", "/biz/auth/email/bind", token,
                "{\"email\":\"" + first + "\",\"code\":\"" + emailCode(first) + "\"}");
        assertThat(bound.path("code").asInt()).as(bound.toString()).isZero();
        assertThat(bound.path("data").path("account").path("email").asString()).isEqualTo(first);

        String second = email();
        JsonNode missing = call("POST", "/biz/auth/email/bind", token,
                "{\"email\":\"" + second + "\",\"code\":\"" + emailCode(second) + "\"}");
        assertThat(missing.path("code").asInt()).as(missing.toString()).isEqualTo(10400);

        JsonNode replaced = call("POST", "/biz/auth/email/bind", token,
                "{\"email\":\"" + second + "\",\"code\":\"" + emailCode(second) + "\",\"currentCode\":\"" + emailCode(first) + "\"}");
        assertThat(replaced.path("code").asInt()).as(replaced.toString()).isZero();
        assertThat(replaced.path("data").path("account").path("email").asString()).isEqualTo(second);

        JsonNode same = call("POST", "/biz/auth/email/bind", token,
                "{\"email\":\"" + second + "\",\"code\":\"111111\",\"currentCode\":\"222222\"}");
        assertThat(same.path("code").asInt()).as(same.toString()).isEqualTo(10475);
    }

    @Test
    @DisplayName("AC17–AC18 an owner with nothing pending can delete the account; the session ends and the phone starts a new account")
    void deregisterWithoutBlockers() throws Exception {
        String phone = phone();
        String token = TestLogin.merchantOwner(mvc(), json, otpStore, phone);
        String me = userNo(token);

        JsonNode pre = call("GET", "/biz/auth/deregister/precheck", token, null).path("data");
        assertThat(pre.path("allowed").asBoolean()).as(pre.toString()).isTrue();
        assertThat(pre.path("verifyBy").asString()).isEqualTo("PHONE");
        assertThat(pre.path("verifyTarget").asString()).isEqualTo(phone);
        assertThat(pre.path("verifyTargetMasked").asString()).doesNotContain(phone);

        JsonNode unconfirmed = call("POST", "/biz/auth/deregister", token,
                "{\"code\":\"" + smsCode(phone) + "\",\"confirm\":false}");
        assertThat(unconfirmed.path("code").asInt()).isEqualTo(10400);

        JsonNode done = call("POST", "/biz/auth/deregister", token,
                "{\"code\":\"" + smsCode(phone) + "\",\"confirm\":true}");
        assertThat(done.path("code").asInt()).as(done.toString()).isZero();
        assertThat(call("GET", "/biz/auth/account", token, null).path("code").asInt()).isNotZero();
        assertThat(jdbc.queryForObject("SELECT status FROM usr_account WHERE user_no=?", String.class, me))
                .isEqualTo("DEREGISTERED");

        String fresh = TestLogin.merchantOwner(mvc(), json, otpStore, phone);
        assertThat(userNo(fresh)).isNotEqualTo(me);
    }

    @Test
    @DisplayName("AC19 a blocker found at submit time refuses the deletion with 70100")
    void blockedDeregistration() throws Exception {
        String phone = phone();
        String token = TestLogin.merchantOwner(mvc(), json, otpStore, phone);
        // An unpaid order of the same person as a buyer is the cheapest real blocker to set up.
        String buyer = TestLogin.consumer(mvc(), json, otpStore, phone);
        mvc().perform(post("/mp/cart/add").header("Authorization", "Bearer " + buyer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"goodsNo\":\"G0002\",\"skuNo\":\"SK0003\",\"qty\":1}"));
        JsonNode order = json.readTree(mvc().perform(post("/mp/order").header("Authorization", "Bearer " + buyer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\"}"))
                .andReturn().getResponse().getContentAsString());
        assertThat(order.path("code").asInt()).as(order.toString()).isZero();
        JsonNode pre = call("GET", "/biz/auth/deregister/precheck", token, null).path("data");
        assertThat(pre.path("allowed").asBoolean()).as(pre.toString()).isFalse();
        assertThat(pre.path("blockers").toString()).contains("BUYER_OPEN_ORDERS");

        JsonNode r = call("POST", "/biz/auth/deregister", token,
                "{\"code\":\"" + smsCode(phone) + "\",\"confirm\":true}");
        assertThat(r.path("code").asInt()).as(r.toString()).isEqualTo(70100);
        assertThat(call("GET", "/biz/auth/account", token, null).path("code").asInt()).isZero();
    }

    // ------------------------------------------------------------ store owners (review fixes)

    private String uid() {
        return Long.toHexString(ThreadLocalRandom.current().nextLong() & Long.MAX_VALUE);
    }

    private void account(String no, String entity, String userNo, String phone, boolean owner, String status) {
        jdbc.update("INSERT INTO mch_account (mch_account_no, entity_no, user_no, login_phone, is_owner, status,"
                + " created_at, updated_at) VALUES (?,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                no, entity, userNo, phone, owner ? 1 : 0, status);
    }

    private String status(String table, String keyCol, String key) {
        return jdbc.queryForObject("SELECT status FROM " + table + " WHERE " + keyCol + "=?", String.class, key);
    }

    @Test
    @DisplayName("Review #3: a disabled staff row on the new phone in the owner's entity is 10409, not a 500")
    void phoneChangeIntoDisabledStaffPhoneIsConflict() throws Exception {
        String phone = phone();
        String token = TestLogin.merchantOwner(mvc(), json, otpStore, phone);
        String me = userNo(token);
        String entity = "ENT-ACCT-" + uid();
        String target = phone();
        account("MA-O-" + uid(), entity, me, phone, true, "ACTIVE");
        account("MA-S-" + uid(), entity, null, target, false, "DISABLED");

        JsonNode r = call("POST", "/biz/auth/phone/change", token,
                "{\"currentCode\":\"" + smsCode(phone) + "\",\"newPhone\":\"" + target + "\",\"newCode\":\"" + smsCode(target) + "\"}");
        assertThat(r.path("code").asInt()).as(r.toString()).isEqualTo(10409);
        assertThat(call("GET", "/biz/auth/account", token, null).path("data").path("phone").asString()).isEqualTo(phone);
    }

    @Test
    @DisplayName("D5: owner row and other owners' unlinked staff rows follow the new phone; a row linked to someone else does not")
    void staffRowsFollowPhoneChange() throws Exception {
        String phone = phone();
        String token = TestLogin.merchantOwner(mvc(), json, otpStore, phone);
        String me = userNo(token);
        String own = "MA-O-" + uid(), staff = "MA-S-" + uid(), foreign = "MA-F-" + uid();
        account(own, "ENT-A-" + uid(), me, phone, true, "ACTIVE");
        account(staff, "ENT-B-" + uid(), null, phone, false, "ACTIVE");
        account(foreign, "ENT-C-" + uid(), "someone-else-" + uid(), phone, false, "ACTIVE");

        String newPhone = phone();
        JsonNode r = call("POST", "/biz/auth/phone/change", token,
                "{\"currentCode\":\"" + smsCode(phone) + "\",\"newPhone\":\"" + newPhone + "\",\"newCode\":\"" + smsCode(newPhone) + "\"}");
        assertThat(r.path("code").asInt()).as(r.toString()).isZero();
        String sql = "SELECT login_phone FROM mch_account WHERE mch_account_no=?";
        assertThat(jdbc.queryForObject(sql, String.class, own)).isEqualTo(newPhone);
        assertThat(jdbc.queryForObject(sql, String.class, staff)).isEqualTo(newPhone);
        assertThat(jdbc.queryForObject(sql, String.class, foreign)).isEqualTo(phone);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mch_staff_log WHERE target_account_no=? AND action='STAFF_PHONE_CHANGE'",
                Integer.class, staff)).isEqualTo(1);
    }

    @Test
    @DisplayName("D8 / D14-B / review #5: deregistration freezes owned stores and disables only this person's rows")
    void ownerDeregistrationFreezesStoresAndSparesOthers() throws Exception {
        String phone = phone();
        String token = TestLogin.merchantOwner(mvc(), json, otpStore, phone);
        String me = userNo(token);
        String entity = "ENT-D-" + uid(), store = "ST-D-" + uid();
        String own = "MA-O-" + uid(), staff = "MA-S-" + uid(), foreign = "MA-F-" + uid();
        account(own, entity, me, phone, true, "ACTIVE");
        account(staff, "ENT-E-" + uid(), null, phone, false, "ACTIVE");
        account(foreign, "ENT-F-" + uid(), "someone-else-" + uid(), phone, false, "ACTIVE");
        jdbc.update("INSERT INTO mch_store (store_no, entity_no, name, status, created_at, updated_at)"
                + " VALUES (?,?,?,'ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", store, entity, "Dereg store");

        JsonNode pre = call("GET", "/biz/auth/deregister/precheck", token, null).path("data");
        assertThat(pre.path("allowed").asBoolean()).as(pre.toString()).isTrue();
        assertThat(pre.path("staffMemberships").asInt()).isEqualTo(1);

        JsonNode done = call("POST", "/biz/auth/deregister", token,
                "{\"code\":\"" + smsCode(phone) + "\",\"confirm\":true}");
        assertThat(done.path("code").asInt()).as(done.toString()).isZero();
        assertThat(status("mch_store", "store_no", store)).isEqualTo("READONLY");
        assertThat(status("mch_account", "mch_account_no", own)).isEqualTo("DISABLED");
        assertThat(status("mch_account", "mch_account_no", staff)).isEqualTo("DISABLED");
        assertThat(status("mch_account", "mch_account_no", foreign)).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("AC20: a staff session can read its account but every write is 10403")
    void staffSessionIsReadOnly() throws Exception {
        String ownerPhone = phone();
        String ownerToken = TestLogin.merchantOwner(mvc(), json, otpStore, ownerPhone);
        String staffPhone = phone();
        account("MA-S-" + uid(), "ENT-G-" + uid(), null, staffPhone, false, "ACTIVE");
        String staff = TestLogin.merchantStaff(mvc(), json, otpStore, staffPhone);

        JsonNode read = call("GET", "/biz/auth/account", staff, null);
        assertThat(read.path("code").asInt()).as(read.toString()).isZero();
        assertThat(read.path("data").path("phone").asString()).isEqualTo(staffPhone);
        assertThat(read.path("data").path("phoneChangeAvailable").asBoolean()).isFalse();
        assertThat(call("POST", "/biz/auth/email/bind", staff, "{\"email\":\"" + email() + "\",\"code\":\"123456\"}")
                .path("code").asInt()).isEqualTo(10403);
        assertThat(call("GET", "/biz/auth/deregister/precheck", staff, null).path("code").asInt()).isEqualTo(10403);
        assertThat(ownerToken).isNotBlank();
    }
}
