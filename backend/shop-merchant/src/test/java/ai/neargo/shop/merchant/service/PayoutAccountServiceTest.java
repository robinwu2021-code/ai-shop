package ai.neargo.shop.merchant.service;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.entity.MchEntity;
import ai.neargo.shop.merchant.entity.MchPayoutAccount;
import ai.neargo.shop.merchant.mapper.MerchantMappers;
import ai.neargo.shop.merchant.service.impl.PayoutAccountServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 供应商收款账户（ADR-011 · V358 · TDD §3.1 的四条规矩）。
 *
 * <p>这些用例钉的是<b>资金重定向</b>这条链上的判断：账号不能明文落库、
 * 户名必须三流一致、改卡必须经运营核、同主体只能有一个生效账户。
 * 每一条错了都不会报错 —— 只会在某一期货款打到别的账户上时才发现。
 */
class PayoutAccountServiceTest {

    private static final String ENTITY = "E-001";
    private static final String NAME = "深圳市虹选科技有限公司";
    private static final String CARD = "6222021234567890123";

    private MerchantMappers.PayoutAccountMapper accounts;
    private MerchantMappers.MchEntityMapper entities;
    private PayoutAccountCipher cipher;
    private PayoutAccountServiceImpl service;

    @BeforeEach
    void setUp() {
        accounts = mock(MerchantMappers.PayoutAccountMapper.class);
        entities = mock(MerchantMappers.MchEntityMapper.class);
        /*
         * **用真的 cipher 而不是 mock。**
         *
         * 要测的正是「明文没有被原样存进去」，而一个 mock 的 encrypt 返回什么
         * 都由我自己编 —— 编成什么样断言都成立，那就什么也没测到。
         * 真 cipher 用一把测试密钥（32 字节 base64）。
         */
        cipher = new PayoutAccountCipher("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        service = new PayoutAccountServiceImpl(accounts, entities, cipher);

        MchEntity e = new MchEntity();
        e.setEntityNo(ENTITY);
        e.setName(NAME);
        when(entities.selectOne(any())).thenReturn(e);
        when(accounts.selectCount(any())).thenReturn(0L);
    }

    @Test
    @DisplayName("提交：账号加密落库，明文不出现在任何字段里")
    void submit_encrypts_and_never_keeps_plaintext() {
        service.submit(ENTITY, new PayoutAccountService.SubmitCommand(
                MchPayoutAccount.CORPORATE, NAME, CARD, "浦发银行", "深圳分行"));

        ArgumentCaptor<MchPayoutAccount> cap = ArgumentCaptor.forClass(MchPayoutAccount.class);
        verify(accounts).insert(cap.capture());
        MchPayoutAccount saved = cap.getValue();

        // 落库的密文不等于明文，且能解回原值（GCM 完整性由 cipher 自己保证）
        assertThat(saved.getAccountNumberEnc()).isNotEqualTo(CARD);
        assertThat(cipher.decrypt(saved.getAccountNumberEnc())).isEqualTo(CARD);
        // 掩码只留尾四位
        assertThat(saved.getAccountMasked()).isEqualTo("****0123");
        // **整行里不能有任何字段等于明文** —— 防的是将来有人加一个 accountNumber 字段
        assertThat(saved.getAccountName()).isNotEqualTo(CARD);
        assertThat(saved.getBankName()).isNotEqualTo(CARD);
        assertThat(saved.getStatus()).isEqualTo(MchPayoutAccount.PENDING);
    }

    @Test
    @DisplayName("提交：户名与营业执照主体名不符时拒绝（三流一致）")
    void submit_rejects_name_mismatch() {
        assertThatThrownBy(() -> service.submit(ENTITY,
                new PayoutAccountService.SubmitCommand(
                        MchPayoutAccount.PERSONAL_BANK_CARD, "武斌", CARD, "浦发银行", null)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.PAYOUT_ACCOUNT_NAME_MISMATCH);
        verify(accounts, never()).insert(any(MchPayoutAccount.class));
    }

    @Test
    @DisplayName("提交：已有在审账户时拒绝，且先于户名校验报出")
    void submit_rejects_when_pending_exists() {
        when(accounts.selectCount(any())).thenReturn(1L);

        // 故意同时把户名也填错 —— 两条都命中时必须报「有单在审」，
        // 否则他改完户名重提还是会被拦，白改一次
        assertThatThrownBy(() -> service.submit(ENTITY,
                new PayoutAccountService.SubmitCommand(
                        MchPayoutAccount.CORPORATE, "填错的户名", CARD, "浦发银行", null)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.PAYOUT_ACCOUNT_PENDING_EXISTS);
    }

    @Test
    @DisplayName("审核通过：同主体的旧生效账户被停用，避免两条同时生效")
    void approve_disables_previous_active() {
        MchPayoutAccount pending = account("PAC-NEW", MchPayoutAccount.PENDING);
        MchPayoutAccount old = account("PAC-OLD", MchPayoutAccount.ACTIVE);
        when(accounts.selectOne(any())).thenReturn(pending);
        when(accounts.selectList(any())).thenReturn(List.of(old));

        service.audit("PAC-NEW", true, null);

        assertThat(old.getStatus()).isEqualTo(MchPayoutAccount.DISABLED);
        assertThat(pending.getStatus()).isEqualTo(MchPayoutAccount.ACTIVE);
        // 旧的先被更新，新的后更新 —— 中间一瞬间不能有两条 ACTIVE
        InOrder order = inOrder(accounts);
        order.verify(accounts).updateById(old);
        order.verify(accounts).updateById(pending);
    }

    @Test
    @DisplayName("驳回：不写原因直接拒，否则商家只能猜")
    void reject_requires_remark() {
        assertThatThrownBy(() -> service.audit("PAC-NEW", false, "  "))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
        verify(accounts, never()).updateById(any(MchPayoutAccount.class));
    }

    @Test
    @DisplayName("解密：只有生效中的账户能取明文，待审的不行")
    void decrypt_refuses_non_active() {
        MchPayoutAccount pending = account("PAC-NEW", MchPayoutAccount.PENDING);
        pending.setAccountNumberEnc(cipher.encrypt(CARD));
        when(accounts.selectOne(any())).thenReturn(pending);

        assertThatThrownBy(() -> service.decryptAccountNumber("PAC-NEW"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    private static MchPayoutAccount account(String no, String status) {
        MchPayoutAccount a = new MchPayoutAccount();
        a.setAccountNo(no);
        a.setEntityNo(ENTITY);
        a.setAccountName(NAME);
        a.setStatus(status);
        return a;
    }
}
