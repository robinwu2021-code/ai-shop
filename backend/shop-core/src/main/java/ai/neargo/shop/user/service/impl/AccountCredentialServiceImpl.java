package ai.neargo.shop.user.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.Masks;
import ai.neargo.shop.common.otp.OtpEntries;
import ai.neargo.shop.common.otp.OtpScenes;
import ai.neargo.shop.common.otp.service.OtpSendService;
import ai.neargo.shop.common.otp.service.OtpSendService.Channel;
import ai.neargo.shop.common.otp.service.OtpSendService.OtpContext;
import ai.neargo.shop.common.otp.service.OtpSendService.VerifyCommand;
import ai.neargo.shop.user.IdentityType;
import ai.neargo.shop.user.entity.UsrAccount;
import ai.neargo.shop.user.entity.UsrIdentity;
import ai.neargo.shop.user.mapper.UserMappers.IdentityMapper;
import ai.neargo.shop.user.mapper.UserMappers.UserMapper;
import ai.neargo.shop.user.service.AccountCredentialService;
import ai.neargo.shop.user.service.AuthService;
import ai.neargo.shop.user.service.EmailOtpService;
import ai.neargo.shop.user.service.PersonService;
import ai.neargo.shop.user.service.TestIdentityService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountCredentialServiceImpl implements AccountCredentialService {

    static final String PHONE_CHANGE = "PHONE_CHANGE";
    static final String EMAIL_BIND = "EMAIL_BIND";
    static final String EMAIL_CHANGE = "EMAIL_CHANGE";
    static final String DEREGISTER = "DEREGISTER";

    private final UserMapper users;
    private final IdentityMapper identities;
    private final OtpSendService otp;
    private final TestIdentityService testIdentities;
    private final PersonService persons;
    private final AuthService auth;
    private final JdbcTemplate jdbc;
    private final List<String> mockPrefixes;
    private final boolean mailStub;
    private final boolean stubOtpAllowed;

    public AccountCredentialServiceImpl(UserMapper users, IdentityMapper identities, OtpSendService otp,
                                        TestIdentityService testIdentities, PersonService persons,
                                        AuthService auth, JdbcTemplate jdbc,
                                        @Value("${shop.sms.mock-prefixes:}") String mockPrefixes,
                                        @Value("${shop.mail.stub:true}") boolean mailStub,
                                        @Value("${shop.mail.otp-stub-allowed:false}") boolean stubOtpAllowed) {
        this.users = users;
        this.identities = identities;
        this.otp = otp;
        this.testIdentities = testIdentities;
        this.persons = persons;
        this.auth = auth;
        this.jdbc = jdbc;
        this.mockPrefixes = mockPrefixes == null ? List.of() : Arrays.stream(mockPrefixes.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        this.mailStub = mailStub;
        this.stubOtpAllowed = stubOtpAllowed;
    }

    @Override
    public Credentials credentials(String userNo) {
        UsrAccount account = requireAccount(userNo);
        String phone = identityValue(userNo, IdentityType.PHONE);
        if (phone == null) phone = blankToNull(account.getPhone());
        String email = identityValue(userNo, IdentityType.EMAIL);
        return new Credentials(userNo, phone, email, auth.hasPassword(userNo), verifyBy(phone, email),
                mailUsable(), mockPrefixes);
    }

    @Override
    public List<String> smsUnavailablePrefixes() {
        return mockPrefixes;
    }

    @Override
    public void verifyCurrent(String userNo, String code) {
        Credentials c = credentials(userNo);
        if (c.verifyBy() == null) throw BizException.of(ErrorCode.RESOURCE_DEPENDENCY_UNAVAILABLE);
        verify(c.verifyBy() == VerifyBy.PHONE ? Channel.SMS : Channel.EMAIL, c.verifyTarget(), code);
    }

    @Override
    @Transactional
    public String changePhone(String userNo, String currentCode, String newPhone, String newCode, String clientIp) {
        if (newPhone == null || !newPhone.matches("\\+?\\d{6,20}") || blank(currentCode) || blank(newCode)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        Credentials before = credentials(userNo);
        // Rule ⑦: refuse before the user goes through two steps for a code that can never arrive.
        if (before.verifyBy() == null || !smsDeliverable(newPhone)) {
            throw BizException.of(ErrorCode.RESOURCE_DEPENDENCY_UNAVAILABLE);
        }
        // Checked before any code is consumed: the same phone cannot carry two live codes at once.
        if (newPhone.equals(before.phone())) throw BizException.of(ErrorCode.PHONE_ALREADY_BOUND);
        verifyCurrent(userNo, currentCode);
        verify(Channel.SMS, newPhone, newCode);
        UsrIdentity owner = identity(IdentityType.PHONE, newPhone);
        if (owner != null && !userNo.equals(owner.getUserNo())) throw BizException.of(ErrorCode.CONFLICT);

        upsertIdentity(userNo, IdentityType.PHONE, newPhone);
        users.update(null, Wrappers.<UsrAccount>lambdaUpdate()
                .eq(UsrAccount::getUserNo, userNo).set(UsrAccount::getPhone, newPhone));
        try {
            persons.changePhone(userNo, newPhone);
        } catch (BizException taken) {
            // The contract reports every "this phone belongs to someone else" case as 10409.
            if (taken.errorCode() == ErrorCode.PERSON_PHONE_TAKEN) throw BizException.of(ErrorCode.CONFLICT);
            throw taken;
        }
        log(userNo, PHONE_CHANGE, maskPhone(before.phone()), maskPhone(newPhone), clientIp);
        return before.phone();
    }

    @Override
    @Transactional
    public String bindEmail(String userNo, String email, String code, String currentCode, String clientIp) {
        if (blank(email) || blank(code)) throw BizException.of(ErrorCode.BAD_REQUEST);
        String normalized = EmailOtpService.normalize(email);
        Credentials before = credentials(userNo);
        if (!emailDeliverable(normalized)) throw BizException.of(ErrorCode.RESOURCE_DEPENDENCY_UNAVAILABLE);
        // Checked before any code is consumed: the same address cannot carry two live codes at once.
        if (normalized.equals(before.email())) throw BizException.of(ErrorCode.EMAIL_ALREADY_BOUND);
        if (before.email() != null) {
            // D3: replacing an email needs a code to the current one.
            if (blank(currentCode)) throw BizException.of(ErrorCode.BAD_REQUEST);
            verify(Channel.EMAIL, before.email(), currentCode);
        }
        verify(Channel.EMAIL, normalized, code);
        UsrIdentity owner = identity(IdentityType.EMAIL, normalized);
        if (owner != null && !userNo.equals(owner.getUserNo())) throw BizException.of(ErrorCode.CONFLICT);

        upsertIdentity(userNo, IdentityType.EMAIL, normalized);
        log(userNo, before.email() == null ? EMAIL_BIND : EMAIL_CHANGE,
                before.email() == null ? null : Masks.email(before.email()), Masks.email(normalized), clientIp);
        return before.email();
    }

    @Override
    public void logDeregister(String userNo, String clientIp) {
        log(userNo, DEREGISTER, null, null, clientIp);
    }

    // ------------------------------------------------------------------ deliverability

    private VerifyBy verifyBy(String phone, String email) {
        if (phone != null && smsDeliverable(phone)) return VerifyBy.PHONE;
        if (email != null && emailDeliverable(email)) return VerifyBy.EMAIL;
        return null;
    }

    /** Same rule as the OTP channel: mocked prefixes never get a real SMS, whitelisted test numbers still log in. */
    private boolean smsDeliverable(String phone) {
        return mockPrefixes.stream().noneMatch(phone::startsWith)
                || testIdentities.isTestIdentity(TestIdentityService.PHONE, phone);
    }

    private boolean mailUsable() {
        return !mailStub || stubOtpAllowed;
    }

    private boolean emailDeliverable(String email) {
        return mailUsable() || testIdentities.isTestIdentity(TestIdentityService.EMAIL, email);
    }

    private void verify(Channel channel, String recipient, String code) {
        if (blank(code) || !otp.verifyAndConsume(
                new VerifyCommand(OtpScenes.LOGIN, channel, recipient, null, code),
                new OtpContext(OtpEntries.BIZ_LOGIN, null, null))) {
            throw BizException.of(ErrorCode.OTP_INVALID);
        }
    }

    // ------------------------------------------------------------------ storage

    private UsrAccount requireAccount(String userNo) {
        UsrAccount account = users.selectOne(Wrappers.<UsrAccount>lambdaQuery()
                .eq(UsrAccount::getUserNo, userNo).last("limit 1"));
        if (account == null) throw BizException.of(ErrorCode.UNAUTHORIZED);
        return account;
    }

    private String identityValue(String userNo, String type) {
        UsrIdentity row = identities.selectOne(Wrappers.<UsrIdentity>lambdaQuery()
                .eq(UsrIdentity::getUserNo, userNo).eq(UsrIdentity::getIdentityType, type).last("limit 1"));
        return row == null ? null : row.getIdentityValue();
    }

    private UsrIdentity identity(String type, String value) {
        return identities.selectOne(Wrappers.<UsrIdentity>lambdaQuery()
                .eq(UsrIdentity::getIdentityType, type).eq(UsrIdentity::getIdentityValue, value).last("limit 1"));
    }

    private void upsertIdentity(String userNo, String type, String value) {
        UsrIdentity existing = identities.selectOne(Wrappers.<UsrIdentity>lambdaQuery()
                .eq(UsrIdentity::getUserNo, userNo).eq(UsrIdentity::getIdentityType, type).last("limit 1"));
        if (existing != null) {
            existing.setIdentityValue(value);
            existing.setVerifiedAt(LocalDateTime.now());
            identities.updateById(existing);
            return;
        }
        UsrIdentity row = new UsrIdentity();
        row.setUserNo(userNo);
        row.setIdentityType(type);
        row.setIdentityValue(value);
        row.setVerifiedAt(LocalDateTime.now());
        identities.insert(row);
    }

    private void log(String userNo, String action, String before, String after, String clientIp) {
        jdbc.update("INSERT INTO usr_account_change_log (user_no, action, before_masked, after_masked, actor_kind,"
                        + " client_ip, created_at, updated_at, version, deleted) VALUES (?,?,?,?,?,?,?,?,0,0)",
                userNo, action, before, after, "SELF", clientIp, LocalDateTime.now(), LocalDateTime.now());
    }

    private static String maskPhone(String phone) {
        return phone == null ? null : Masks.phone(phone);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String blankToNull(String s) {
        return blank(s) ? null : s;
    }
}
