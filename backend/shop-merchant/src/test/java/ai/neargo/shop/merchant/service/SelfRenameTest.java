package ai.neargo.shop.merchant.service;

import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.auth.TokenStores;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.merchant.entity.MchAccount;
import ai.neargo.shop.merchant.mapper.MerchantMappers;
import ai.neargo.shop.merchant.service.impl.MerchantStaffServiceImpl;
import ai.neargo.shop.merchant.service.impl.StaffAuditLogger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 改用户名（显示名，§账号管理）。按 principal 自查，改自己那一行。 */
class SelfRenameTest {

    private final MerchantMappers.MchAccountMapper staffMapper = mock(MerchantMappers.MchAccountMapper.class);

    private MerchantStaffServiceImpl svc() {
        return new MerchantStaffServiceImpl(staffMapper, mock(TokenStores.class),
                mock(MerchantMappers.MchStoreMapper.class), mock(MerchantMappers.MchStoreRoleMapper.class),
                mock(MerchantMappers.MchStaffLogMapper.class), mock(MerchantMappers.MchRoleMapper.class),
                mock(StaffAuditLogger.class), mock(TokenStore.class), mock(OtpStore.class));
    }

    private static MchAccount account(String displayName) {
        MchAccount a = new MchAccount();
        a.setMchAccountNo("MA-1");
        a.setDisplayName(displayName);
        a.setStatus(MchAccount.ACTIVE);
        return a;
    }

    @Test
    void displayNameOf_returnsCurrentName() {
        when(staffMapper.selectOne(any())).thenReturn(account("张三"));
        assertThat(svc().displayNameOf("U-1")).isEqualTo("张三");
    }

    @Test
    void displayNameOf_noAccountOrNull_isBlank() {
        when(staffMapper.selectOne(any())).thenReturn(null);
        assertThat(svc().displayNameOf("U-x")).isEmpty();
        assertThat(svc().displayNameOf("")).isEmpty();
        assertThat(svc().displayNameOf(null)).isEmpty();
    }

    @Test
    void renameSelf_writesTrimmedName() {
        when(staffMapper.selectOne(any())).thenReturn(account("旧名"));
        svc().renameSelf("U-1", "  新名字  ");
        ArgumentCaptor<MchAccount> cap = ArgumentCaptor.forClass(MchAccount.class);
        verify(staffMapper).updateById(cap.capture());
        assertThat(cap.getValue().getDisplayName()).isEqualTo("新名字");   // 去空白后写入
    }

    @Test
    void renameSelf_blankOrTooLong_rejected() {
        assertThatThrownBy(() -> svc().renameSelf("U-1", "   ")).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> svc().renameSelf("U-1", "一二三四五六七八九十一二三四五六七八九十一"))
                .isInstanceOf(BizException.class);
        verify(staffMapper, never()).updateById(any(MchAccount.class));
    }

    @Test
    void renameSelf_noAccount_forbidden() {
        when(staffMapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> svc().renameSelf("U-x", "名")).isInstanceOf(BizException.class);
    }
}
