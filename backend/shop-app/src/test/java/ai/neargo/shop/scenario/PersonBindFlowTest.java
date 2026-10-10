package ai.neargo.shop.scenario;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.user.entity.UsrPerson;
import ai.neargo.shop.user.service.PersonService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 平台人档：会员是「某个自然人 × 某家商家」的关系，所以先得有「这个自然人」。
 *
 * <p><b>这组用例守的是那条准入规则的收益</b>：会员必须有已验证手机号，
 * 因此「商家先录了号、他后来才注册」这条最常见的路径**不需要合并任何会员关系** ——
 * 两边指向的从头到尾是同一份人档。
 */
@SpringBootTest
@ActiveProfiles("test")
class PersonBindFlowTest {

    @Autowired
    private PersonService personService;

    @Autowired
    private ai.neargo.shop.user.mapper.UserMappers.UserMapper userMapper;

    /**
     * 建一个真账号。
     *
     * <p>用真账号而不是编一个号：`bindOnLogin` 的 C 分支会去查那个账号还在不在 ——
     * 编的号查不到，会被当成「已注销」而放行改绑，于是这条用例测不到它本该测的东西。
     */
    private String account(String tag) {
        var u = new ai.neargo.shop.user.entity.UsrAccount();
        u.setUserNo("U-" + tag + "-" + seq);
        u.setNickname("测试" + tag);
        u.setStatus("NORMAL");
        userMapper.insert(u);
        return u.getUserNo();
    }

    /** 每条用例用不同号段，避免共享库里互相撞唯一键 */
    @org.springframework.beans.factory.annotation.Autowired
    private ai.neargo.shop.user.service.PhoneCrypto phoneCrypto;

    private static int seq = 0;

    private static String phone() {
        return "1390000" + String.format("%04d", ++seq);
    }

    @Test
    @DisplayName("★ 手机号写法不同也是同一个人 —— 138-0013-8000 与 13800138000")
    void sameNumberDifferentFormatIsOnePerson() {
        String p = phone();
        UsrPerson a = personService.resolveOrCreateByPhone(p);
        UsrPerson b = personService.resolveOrCreateByPhone(
                p.substring(0, 3) + "-" + p.substring(3, 7) + "-" + p.substring(7));
        assertThat(b.getPersonNo()).isEqualTo(a.getPersonNo());
    }

    @Test
    @DisplayName("★★ 人档不存明文：哈希、后四位、密文三列里都找不到那串号码")
    void phoneIsNeverStoredInPlaintext() {
        String p = phone();
        UsrPerson person = personService.resolveOrCreateByPhone(p);

        assertThat(person.getPhoneHash()).isNotBlank().isNotEqualTo(p);
        assertThat(person.getPhoneTail()).isEqualTo(p.substring(p.length() - 4));

        /*
         * **这条断言原来写的是「密文为空」**，理由是「测试环境没配密钥」——
         * 于是它在一个**根本没有手机号可泄露**的环境里跑，永远绿，
         * 也永远守不住任何东西：把明文直接写进 phone_enc，它照样绿。
         *
         * 2026-08-25 给测试配上密钥之后暴露了这一点。断言改成对着**意图**：
         * 那一列里不能出现明文，而解出来必须是原号 —— 两句话缺一不可，
         * 只查前者的话，存一个乱码也能过。
         */
        assertThat(person.getPhoneEnc()).as("密文列里不能出现明文").doesNotContain(p);
        assertThat(phoneCrypto.decrypt(person.getPhoneEnc()))
                .as("解出来要还是那个号，否则存的是别的东西").isEqualTo(p);
    }

    @Test
    @DisplayName("★★ A 这个号还没有人档 —— 登录时建一份并绑上，一步到位")
    void bindCreatesPersonWhenPhoneIsNew() {
        String p = phone();
        String user = account("A");
        var person = personService.bindOnLogin(user, p).orElseThrow();
        assertThat(person.getUserNo()).isEqualTo(user);
        assertThat(personService.findByUser(user)).isPresent();
    }

    @Test
    @DisplayName("★★ B 商家早就录过他 —— 本人登录时**直接绑**，不需要合并任何东西")
    void bindClaimsExistingLeadWithoutMerge() {
        String p = phone();
        // 商家在 B 端录入：此刻只有人档，没有账号
        UsrPerson lead = personService.resolveOrCreateByPhone(p);
        assertThat(lead.getUserNo()).isNull();

        String user = account("B");
        UsrPerson bound = personService.bindOnLogin(user, p).orElseThrow();

        /*
         * 关键断言：**还是同一份人档**。挂在它下面的会员关系（商家当时录的备注与标签）
         * 因此一条都不用动 —— 这正是「会员必须有手机号」这条准入规则换来的东西。
         */
        assertThat(bound.getPersonNo()).isEqualTo(lead.getPersonNo());
        assertThat(bound.getUserNo()).isEqualTo(user);
    }

    @Test
    @DisplayName("★★ C 这个号绑着别人的账号 —— **显式**绑定时拒绝，绝不自动改绑")
    void explicitBindRefusesWhenPhoneBelongsToAnotherAccount() {
        String p = phone();
        personService.bindOnLogin(account("C1"), p);
        String other = account("C2");

        assertThatThrownBy(() -> personService.bindPhone(other, p))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).errorCode())
                        .isEqualTo(ErrorCode.PERSON_PHONE_TAKEN));
    }

    @Test
    @DisplayName("★★ 同样的冲突发生在**登录**时：跳过，不抛 —— 登录不能被会员关系挡住")
    void loginBindSkipsConflictInsteadOfBlocking() {
        String p = phone();
        personService.bindOnLogin(account("C3"), p);
        String other = account("C4");

        /*
         * 这条是 uk_identity 的形状决定的：它的唯一键是 (identity_type, identity_value)，
         * 不是手机号单列 —— 同一个号落在两种 type 下就会撞到这里。
         * 那时抛异常等于把人关在登录之外，而他只是想进门，并没有要绑号。
         */
        assertThat(personService.bindOnLogin(other, p)).isEmpty();
        assertThat(personService.findByUser(other)).as("没给他建档，但也没拦住他").isEmpty();
    }

    @Test
    @DisplayName("★★ 注销之后同一个号能重新注册 —— 不让出手机号的话，他永远登不回来")
    void deregisteredPhoneCanBeUsedAgain() {
        String p = phone();
        String first = account("F1");
        String firstPerson = personService.bindOnLogin(first, p).orElseThrow().getPersonNo();

        personService.deregister(first);

        // 同一个号回来：拿到的是**一份新人档**，与「同一个微信注销后拿到全新账号」同一语义
        String second = account("F2");
        var again = personService.bindOnLogin(second, p).orElseThrow();
        assertThat(again.getPersonNo()).isNotEqualTo(firstPerson);
        assertThat(again.getUserNo()).isEqualTo(second);

        // 旧人档：手机号让出去了、账号解绑了
        var old = personService.find(firstPerson).orElseThrow();
        assertThat(old.getUserNo()).isNull();
        assertThat(old.getPhoneTail()).isNull();
    }

    @Test
    @DisplayName("★ 同一个人反复登录是幂等的 —— 不会每次都建一份新档")
    void repeatedLoginIsIdempotent() {
        String p = phone();
        String user = account("D");
        String first = personService.bindOnLogin(user, p).orElseThrow().getPersonNo();
        String again = personService.bindOnLogin(user, p).orElseThrow().getPersonNo();
        assertThat(again).isEqualTo(first);
    }

    @Test
    @DisplayName("★ 微信登录没授权手机号 —— 没有人档，也就不是任何商家的会员。这不是错误")
    void noPhoneMeansNoPerson() {
        String user = account("E");
        assertThat(personService.bindOnLogin(user, null)).isEmpty();
        assertThat(personService.bindOnLogin(user, "  ")).isEmpty();
        assertThat(personService.findByUser(user)).isEmpty();
    }

    @Test
    @DisplayName("★★ 合并：源档让出手机号并留痕，重复合并是空操作")
    void mergeReleasesPhoneAndIsIdempotent() {
        String pa = phone();
        String pb = phone();
        UsrPerson from = personService.resolveOrCreateByPhone(pa);
        UsrPerson into = personService.resolveOrCreateByPhone(pb);

        personService.merge(from.getPersonNo(), into.getPersonNo(),
                ai.neargo.shop.user.entity.UsrPersonMergeLog.CHANGE_PHONE, 3, "OPS-TEST");

        UsrPerson merged = personService.find(from.getPersonNo()).orElseThrow();
        assertThat(merged.getStatus()).isEqualTo(UsrPerson.MERGED);
        assertThat(merged.getMergedInto()).isEqualTo(into.getPersonNo());
        /*
         * 源档必须**让出手机号哈希**：否则那个号永远查不到新档，
         * 而唯一键还会挡住这个人日后换回旧号。
         */
        UsrPerson reResolved = personService.resolveOrCreateByPhone(pa);
        assertThat(reResolved.getPersonNo()).isNotEqualTo(from.getPersonNo());

        // 幂等：再合并一次不抛也不改
        personService.merge(from.getPersonNo(), into.getPersonNo(),
                ai.neargo.shop.user.entity.UsrPersonMergeLog.OPS, 0, "OPS-TEST");
    }
}
