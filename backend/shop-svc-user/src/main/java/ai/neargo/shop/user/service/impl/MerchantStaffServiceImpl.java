package ai.neargo.shop.user.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.user.dto.StaffVO;
import ai.neargo.shop.user.merchant.entity.MchAccount;
import ai.neargo.shop.user.mapper.UserMappers.MchAccountMapper;
import ai.neargo.shop.user.mapper.UserMappers.MchStoreMapper;
import ai.neargo.shop.user.mapper.UserMappers.MchStoreRoleMapper;
import ai.neargo.shop.user.merchant.entity.MchStore;
import ai.neargo.shop.user.merchant.entity.MchStoreRole;
import ai.neargo.shop.user.service.MerchantStaffService;
import ai.neargo.shop.user.service.OtpStore;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** {@link MerchantStaffService} 实现。 */
@Service
public class MerchantStaffServiceImpl implements MerchantStaffService {

    private final MchAccountMapper staffMapper;
    private final MchStoreMapper storeMapper;
    private final MchStoreRoleMapper roleMapper;
    private final TokenStore tokenStore;
    private final OtpStore otpStore;

    public MerchantStaffServiceImpl(MchAccountMapper staffMapper, MchStoreMapper storeMapper,
                                    MchStoreRoleMapper roleMapper, TokenStore tokenStore,
                                    OtpStore otpStore) {
        this.staffMapper = staffMapper;
        this.storeMapper = storeMapper;
        this.roleMapper = roleMapper;
        this.tokenStore = tokenStore;
        this.otpStore = otpStore;
    }

    @Override
    public String loginByPhone(String phone, String code) {
        if (!otpStore.verifyAndConsume(phone, code)) {
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        }
        MchAccount staff = DataScopeContext.executeWithoutScope(() ->
                staffMapper.selectOne(Wrappers.<MchAccount>lambdaQuery()
                        .eq(MchAccount::getLoginPhone, phone)
                        .eq(MchAccount::getStatus, MchAccount.ACTIVE)
                        // 多主体时取默认那个；排序确定，否则"今天进 A 店明天进 B 店"没人能复现
                        .orderByDesc(MchAccount::getIsPrimary)
                        .orderByAsc(MchAccount::getId)
                        .last("limit 1")));
        if (staff == null) {
            /*
             * 验证码对了但不是员工 —— 报 403 而不是「账号不存在」。
             * 后者会把「某个手机号是不是这家店的员工」变成一条可枚举的信息，
             * 而验证码本来就是任何人都能给自己的手机号要的。
             */
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        /*
         * principal 用 mch_account_no：这个员工可能**根本没有 C 端账号**。
         * BizIdentityResolver 两条路径都认（user_no 或 mch_account_no）。
         */
        return tokenStore.issue(TokenStore.SessionData.of(
                LoginUser.consumer(staff.getMchAccountNo(), "")));
    }

    // ---------------------------------------------------------------- 员工管理

    @Override
    public List<StaffVO> list(String merchantNo) {
        List<MchAccount> accounts = accounts(merchantNo);
        Map<String, String> storeNames = storeNames(merchantNo);
        Map<String, List<MchStoreRole>> byAccount = rolesOf(accounts).stream()
                .collect(Collectors.groupingBy(MchStoreRole::getMchAccountNo));
        return accounts.stream().map(a -> toVO(a, byAccount, storeNames)).toList();
    }

    @Override
    @Transactional
    public StaffVO add(String merchantNo, String loginPhone) {
        if (loginPhone == null || !loginPhone.matches("\\d{11}")) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        MchAccount existing = accounts(merchantNo).stream()
                .filter(a -> loginPhone.equals(a.getLoginPhone()))
                .findFirst().orElse(null);
        if (existing != null) {
            /*
             * 已存在就**把停用的重新启用**，而不是报「已存在」。
             * 店员离职再回来是常事；报错只会让店长去建一个带后缀的假号码，
             * 而那个假号码收不到验证码。
             */
            existing.setStatus(MchAccount.ACTIVE);
            DataScopeContext.executeWithoutScope(() -> staffMapper.updateById(existing));
            return single(merchantNo, existing);
        }

        MchAccount a = new MchAccount();
        a.setMchAccountNo(BizKey.next(BizKey.MERCHANT_STAFF));
        a.setEntityNo(merchantNo);
        a.setLoginPhone(loginPhone);
        a.setIsOwner(false);
        // 不是主账号：主账号决定登录后默认进哪个主体，那是老板的位置
        a.setIsPrimary(false);
        a.setStatus(MchAccount.ACTIVE);
        DataScopeContext.executeWithoutScope(() -> staffMapper.insert(a));
        return single(merchantNo, a);
    }

    @Override
    @Transactional
    public StaffVO setStatus(String merchantNo, String mchAccountNo, boolean active) {
        MchAccount a = require(merchantNo, mchAccountNo);
        /*
         * 老板不能被停用：停掉之后这个主体没有人能管，恢复要平台介入。
         * 一个能把自己锁在门外的按钮不该存在。
         */
        if (Boolean.TRUE.equals(a.getIsOwner()) && !active) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        a.setStatus(active ? MchAccount.ACTIVE : MchAccount.DISABLED);
        DataScopeContext.executeWithoutScope(() -> staffMapper.updateById(a));
        return single(merchantNo, a);
    }

    @Override
    @Transactional
    public StaffVO grantStore(String merchantNo, String mchAccountNo, String storeNo, String role) {
        MchAccount a = require(merchantNo, mchAccountNo);
        // 只能授权本主体的门店 —— 否则就是把别人的店交给自己的员工
        if (!storeNames(merchantNo).containsKey(storeNo)) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }

        MchStoreRole row = DataScopeContext.executeWithoutScope(() ->
                roleMapper.selectOne(Wrappers.<MchStoreRole>lambdaQuery()
                        .eq(MchStoreRole::getMchAccountNo, mchAccountNo)
                        .eq(MchStoreRole::getStoreNo, storeNo).last("limit 1")));

        if (role == null || role.isBlank()) {
            // 传空 = 收回这家店的授权
            if (row != null) {
                DataScopeContext.executeWithoutScope(() -> roleMapper.deleteById(row.getId()));
            }
            return single(merchantNo, a);
        }
        if (!MchStoreRole.MANAGER.equals(role) && !MchStoreRole.CLERK.equals(role)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        if (row == null) {
            MchStoreRole fresh = new MchStoreRole();
            fresh.setMchAccountNo(mchAccountNo);
            fresh.setStoreNo(storeNo);
            fresh.setRole(role);
            DataScopeContext.executeWithoutScope(() -> roleMapper.insert(fresh));
        } else {
            // 每店一个角色：改角色是覆盖，不是再加一行（库上有唯一键兜底）
            row.setRole(role);
            DataScopeContext.executeWithoutScope(() -> roleMapper.updateById(row));
        }
        return single(merchantNo, a);
    }

    // ------------------------------------------------------------------ 内部

    private List<MchAccount> accounts(String merchantNo) {
        return DataScopeContext.executeWithoutScope(() ->
                staffMapper.selectList(Wrappers.<MchAccount>lambdaQuery()
                        .eq(MchAccount::getEntityNo, merchantNo)
                        // 老板排最前：列表第一眼要能看出谁是老板
                        .orderByDesc(MchAccount::getIsOwner)
                        .orderByAsc(MchAccount::getId)));
    }

    /** 账号号对不上主体一律 404，**不要 403** —— 403 等于确认这个号存在。 */
    private MchAccount require(String merchantNo, String mchAccountNo) {
        return accounts(merchantNo).stream()
                .filter(a -> a.getMchAccountNo().equals(mchAccountNo))
                .findFirst()
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));
    }

    private Map<String, String> storeNames(String merchantNo) {
        return DataScopeContext.executeWithoutScope(() ->
                        storeMapper.selectList(Wrappers.<MchStore>lambdaQuery()
                                .eq(MchStore::getEntityNo, merchantNo)))
                .stream().collect(Collectors.toMap(MchStore::getStoreNo,
                        s -> s.getName() == null ? s.getStoreNo() : s.getName()));
    }

    private List<MchStoreRole> rolesOf(List<MchAccount> accounts) {
        if (accounts.isEmpty()) {
            return List.of();
        }
        List<String> nos = accounts.stream().map(MchAccount::getMchAccountNo).toList();
        return DataScopeContext.executeWithoutScope(() ->
                roleMapper.selectList(Wrappers.<MchStoreRole>lambdaQuery()
                        .in(MchStoreRole::getMchAccountNo, nos)));
    }

    private StaffVO single(String merchantNo, MchAccount a) {
        return toVO(a, rolesOf(List.of(a)).stream()
                        .collect(Collectors.groupingBy(MchStoreRole::getMchAccountNo)),
                storeNames(merchantNo));
    }

    private StaffVO toVO(MchAccount a, Map<String, List<MchStoreRole>> byAccount,
                         Map<String, String> storeNames) {
        List<StaffVO.StoreRoleVO> roles = byAccount.getOrDefault(a.getMchAccountNo(), List.of())
                .stream()
                .map(r -> new StaffVO.StoreRoleVO(r.getStoreNo(),
                        storeNames.getOrDefault(r.getStoreNo(), r.getStoreNo()), r.getRole()))
                .toList();
        return new StaffVO(a.getMchAccountNo(), mask(a.getLoginPhone()),
                Boolean.TRUE.equals(a.getIsOwner()), a.getStatus(), roles);
    }

    /**
     * 手机号脱敏。
     *
     * <p>店长能看到所有店员的完整手机号 = 一份可导出的通讯录。
     * 加员工时他本来就知道那个号码，列表里不需要再给一遍。
     */
    private String mask(String phone) {
        if (phone == null || phone.length() < 7) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}
