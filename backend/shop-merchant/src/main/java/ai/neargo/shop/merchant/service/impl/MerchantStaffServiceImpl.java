package ai.neargo.shop.merchant.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.dto.StaffVO;
import ai.neargo.shop.merchant.entity.MchAccount;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchAccountMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchStaffLogMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreRoleMapper;
import ai.neargo.shop.merchant.entity.MchStaffLog;
import ai.neargo.shop.merchant.entity.MchStore;
import ai.neargo.shop.merchant.entity.MchStoreRole;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.merchant.service.MerchantStaffService;
import ai.neargo.shop.common.OtpStore;
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
    private final MchStaffLogMapper logMapper;
    private final ai.neargo.shop.merchant.mapper.MerchantMappers.MchRoleMapper roleDefMapper;
    private final StaffAuditLogger audit;
    private final TokenStore tokenStore;
    private final OtpStore otpStore;

    public MerchantStaffServiceImpl(MchAccountMapper staffMapper, MchStoreMapper storeMapper,
                                    MchStoreRoleMapper roleMapper, MchStaffLogMapper logMapper,
                                    ai.neargo.shop.merchant.mapper.MerchantMappers.MchRoleMapper roleDefMapper,
                                    StaffAuditLogger audit, TokenStore tokenStore,
                                    OtpStore otpStore) {
        this.staffMapper = staffMapper;
        this.storeMapper = storeMapper;
        this.roleMapper = roleMapper;
        this.logMapper = logMapper;
        this.roleDefMapper = roleDefMapper;
        this.audit = audit;
        this.tokenStore = tokenStore;
        this.otpStore = otpStore;
    }

    @Override
    public String loginByPhone(String phone, String code) {
        if (!otpStore.verifyAndConsume(phone, code)) {
            // 与 AuthServiceImpl.verifyOtp 同一个码：**同一件事在两条登录路上
            // 不该有两种说法**（此前这条回 10401「未登录」，而他正在登录）
            throw BizException.of(ErrorCode.OTP_INVALID);
        }
        /*
         * 验证码对了但不是员工 —— 报 403 而不是「账号不存在」。
         * 后者会把「某个手机号是不是这家店的员工」变成一条可枚举的信息，
         * 而验证码本来就是任何人都能给自己的手机号要的。
         */
        return issueStaffSession(phone)
                .orElseThrow(() -> BizException.of(ErrorCode.FORBIDDEN));
    }

    @Override
    public java.util.Optional<String> issueStaffSession(String phone) {
        MchAccount staff = DataScopeContext.executeWithoutScope(() ->
                staffMapper.selectOne(Wrappers.<MchAccount>lambdaQuery()
                        .eq(MchAccount::getLoginPhone, phone)
                        .eq(MchAccount::getStatus, MchAccount.ACTIVE)
                        // 多主体时取默认那个；排序确定，否则"今天进 A 店明天进 B 店"没人能复现
                        .orderByDesc(MchAccount::getIsPrimary)
                        .orderByAsc(MchAccount::getId)
                        .last("limit 1")));
        if (staff == null) {
            return java.util.Optional.empty();
        }
        /*
         * principal 用 mch_account_no：这个员工可能**根本没有 C 端账号**。
         * BizIdentityResolver 两条路径都认（user_no 或 mch_account_no）。
         */
        return java.util.Optional.of(tokenStore.issue(TokenStore.SessionData.of(
                LoginUser.consumer(staff.getMchAccountNo(), ""))));
    }

    @Override
    public String loginPhoneOf(String principal) {
        if (principal == null || principal.isBlank()) {
            return "";
        }
        // 绕过数据域：这是「我自己是谁」的自查，而数据域本身就要靠身份才算得出来
        MchAccount staff = DataScopeContext.executeWithoutScope(() ->
                staffMapper.selectOne(Wrappers.<MchAccount>lambdaQuery()
                        .and(q -> q.eq(MchAccount::getUserNo, principal)
                                .or().eq(MchAccount::getMchAccountNo, principal))
                        .eq(MchAccount::getStatus, MchAccount.ACTIVE)
                        .orderByDesc(MchAccount::getIsPrimary)
                        .orderByAsc(MchAccount::getId)
                        .last("limit 1")));
        return staff == null || staff.getLoginPhone() == null ? "" : staff.getLoginPhone();
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
    public StaffVO add(String merchantNo, String loginPhone, String displayName) {
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
            // 回来时顺手更新备注名：离职再回来常伴随「这次是小李不是小张」
            if (displayName != null && !displayName.isBlank()) {
                existing.setDisplayName(displayName.trim());
            }
            DataScopeContext.executeWithoutScope(() -> staffMapper.updateById(existing));
            // 对老板来说这就是「把人加回来」，所以记 STAFF_ADD 而不是 ENABLE ——
            // 审计要还原他做了什么，不是还原代码走了哪个分支
            log(merchantNo, existing.getMchAccountNo(), MchStaffLog.STAFF_ADD,
                    null, null, "重新启用已存在的员工 " + mask(loginPhone));
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
        a.setDisplayName(displayName == null || displayName.isBlank() ? null : displayName.trim());
        DataScopeContext.executeWithoutScope(() -> staffMapper.insert(a));
        log(merchantNo, a.getMchAccountNo(), MchStaffLog.STAFF_ADD,
                null, null, "新增员工 " + mask(loginPhone));
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
        log(merchantNo, mchAccountNo,
                active ? MchStaffLog.STAFF_ENABLE : MchStaffLog.STAFF_DISABLE, null, null,
                // 停用不删门店授权，日志里点明 —— 否则事后会以为权限已经收回了
                active ? "启用员工" : "停用员工（门店授权保留）");
        return single(merchantNo, a);
    }

    @Override
    @Transactional
    public StaffVO grantStore(String merchantNo, String mchAccountNo, String storeNo,
                              String role, boolean granted) {
        MchAccount a = require(merchantNo, mchAccountNo);
        // 只能授权本主体的门店 —— 否则就是把别人的店交给自己的员工
        if (!storeNames(merchantNo).containsKey(storeNo)) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        /*
         * 角色必须是**这家商家可用的**角色（V71 起含自定义）。
         *
         * <p>此前这里判的是 {@code MchStoreRole.GRANTABLE} 那张写死的五元组 ——
         * 自定义角色一上来，授权就全被这行挡住了，而报的是「参数有误」，
         * 老板刚建完角色转头就用不了，且看不出为什么。
         *
         * <p><b>OWNER 授不出去</b>：老板不在 mch_store_role 里，
         * 把它当角色授给别人等于凭空造一个第二老板。
         */
        if (role == null || role.isBlank() || MchStoreRole.OWNER_CODE.equals(role)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        // 用 selectList 判有无而不是 exists()：后者生成 SELECT EXISTS(...)，
        // 数据域拦截器改写它时会翻车（表现是 10500，而不是一句「角色不存在」）
        boolean assignable = !DataScopeContext.executeWithoutScope(() ->
                roleDefMapper.selectList(Wrappers.<ai.neargo.shop.merchant.entity.MchRole>lambdaQuery()
                        .in(ai.neargo.shop.merchant.entity.MchRole::getEntityNo,
                                List.of(merchantNo,
                                        ai.neargo.shop.merchant.entity.MchRole.BUILTIN_ENTITY))
                        .eq(ai.neargo.shop.merchant.entity.MchRole::getRoleCode, role)
                        .last("limit 1"))).isEmpty();
        if (!assignable) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }

        /*
         * **增量式：这一次只动这一个角色**（V18 起一人一店可多角色）。
         *
         * 原先是覆盖式（查出这家店那一行、改它的 role）。多角色下那是错的 ——
         * 老板想「再加一个配送员」，结果把「店员」冲掉了，而且不报错。
         * 撤销同理：只删这一个角色的那一行，不碰别的。
         */
        MchStoreRole row = DataScopeContext.executeWithoutScope(() ->
                roleMapper.selectOne(Wrappers.<MchStoreRole>lambdaQuery()
                        .eq(MchStoreRole::getMchAccountNo, mchAccountNo)
                        .eq(MchStoreRole::getStoreNo, storeNo)
                        .eq(MchStoreRole::getRole, role).last("limit 1")));

        if (!granted) {
            // 撤销。**撤到一个不剩 = 从这家店移除他** —— 不留空壳行
            if (row != null) {
                DataScopeContext.executeWithoutScope(() -> roleMapper.deleteById(row.getId()));
                // 只有真删了才记。撤销一个他本来就没有的角色是空操作，
                // 记下来会让日志里出现一串「撤销了店长」而他从来不是店长
                log(merchantNo, mchAccountNo, MchStaffLog.ROLE_REVOKE, storeNo, role,
                        "撤销 " + storeNames(merchantNo).get(storeNo) + " 的 "
                                + roleName(merchantNo, role));
            }
            return single(merchantNo, a);
        }
        // 已经有了就什么都不做 —— 重复授予是幂等的，不该长出两行
        if (row == null) {
            /*
             * **先试着复活被逻辑删的那一行**。
             *
             * 撤销授权是逻辑删，而 uk_store_role 不含 deleted 列 ——
             * 「撤销再授予同一个角色」时直接 insert 必然撞唯一键，接口 500，
             * 而老板看到的只是「系统开小差」，与他刚做的操作毫无关系。
             *
             * 这个坑在商家社区表、商品社区池上各踩过一次，这是第三次：
             * 凡是「逻辑删 + 业务唯一键」的组合都有它，而它只在
             * 「删了再加回来」这条路径上出现 —— 日常测试很难走到。
             */
            boolean revived = DataScopeContext.executeWithoutScope(() ->
                    roleMapper.revive(mchAccountNo, storeNo, role)) > 0;
            if (!revived) {
                MchStoreRole fresh = new MchStoreRole();
                fresh.setMchAccountNo(mchAccountNo);
                fresh.setStoreNo(storeNo);
                fresh.setRole(role);
                DataScopeContext.executeWithoutScope(() -> roleMapper.insert(fresh));
            }
            // 复活与新建都是「授予」，记同一条 —— 那个区别是实现细节，不是老板做的事
            log(merchantNo, mchAccountNo, MchStaffLog.ROLE_GRANT, storeNo, role,
                    "授予 " + storeNames(merchantNo).get(storeNo) + " 的 "
                            + roleName(merchantNo, role));
        }
        return single(merchantNo, a);
    }

    @Override
    public List<ai.neargo.shop.merchant.dto.StaffLogVO> logs(String merchantNo,
                                                             String targetAccountNo) {
        /*
         * **必须显式按 entity_no 过滤** —— mch_staff_log 没有注册数据域
         * （理由见 V68 的注释：门店维度对员工表没有意义）。
         * 未注册的表不会被自动收窄，漏掉这个条件就是把别家商家的授权记录发出去，
         * 而且不报错。
         */
        List<MchStaffLog> rows = DataScopeContext.executeWithoutScope(() ->
                logMapper.selectList(Wrappers.<MchStaffLog>lambdaQuery()
                        .eq(MchStaffLog::getEntityNo, merchantNo)
                        .eq(targetAccountNo != null && !targetAccountNo.isBlank(),
                                MchStaffLog::getTargetAccountNo, targetAccountNo)
                        .orderByDesc(MchStaffLog::getId)
                        .last("limit 200")));
        if (rows.isEmpty()) {
            return List.of();
        }

        /*
         * 账号号 → 脱敏手机号。老板认得出「尾号 3456 那个」，认不出 SF2026…
         *
         * **不能用 Collectors.toMap** ——「手机号为空」是真实存在的状态：
         * 老板的 mch_account 是入驻时建的，login_phone 一直是 NULL（他走消费者账号登录）。
         * 而 toMap 对 null 值直接抛 NPE，表现是整个接口 500。
         * 这一条单测与 mock 都没抓到：两边造的数据里每个人都有手机号。
         */
        Map<String, String> phones = new java.util.HashMap<>();
        for (MchAccount a : accounts(merchantNo)) {
            // **优先姓名** —— 审计是给三个月后的人看的，那时一串号码说明不了任何事；
            // 没填姓名才退到脱敏号（日志是长期留存的文本，那里不需要完整号码）
            String label = labelOf(a);
            phones.put(a.getMchAccountNo(), label);
            /*
             * **两种键都要能查到**。写日志时存的是当前登录身份的 `userNo`
             * （`StaffAuditLogger.write` 里的 `LoginUser::userNo`），而这张表的键是
             * `mchAccountNo` —— 两个键永远对不上，于是**每一条日志的操作人都是 null**。
             *
             * 而 VO 上写着「取不到当前身份时为空」，于是这个 null 被读成
             * 「当时没取到身份」，真相是键不匹配：B-11.10.3 要的「谁干的」
             * 从上线起就没有一条记下来过（实测 17 条，actor 非空 0 条）。
             *
             * 老板尤其明显：他的 `mch_account.user_no` 有值而 `login_phone` 是 NULL，
             * 而绝大多数授权操作都是他做的。
             */
            if (a.getUserNo() != null && !a.getUserNo().isBlank()) {
                phones.put(a.getUserNo(), label);
            }
        }
        Map<String, String> storeNames = storeNames(merchantNo);

        return rows.stream().map(r -> new ai.neargo.shop.merchant.dto.StaffLogVO(
                phones.get(r.getActorAccountNo()),
                phones.get(r.getTargetAccountNo()),
                r.getAction(),
                r.getStoreNo() == null ? null
                        : storeNames.getOrDefault(r.getStoreNo(), r.getStoreNo()),
                r.getRole(), r.getDetail(),
                r.getCreatedAt() == null ? 0L
                        : r.getCreatedAt().atZone(java.time.ZoneId.systemDefault())
                                .toInstant().toEpochMilli())).toList();
    }

    // ------------------------------------------------------------------ 审计（B-11.10.3）

    /** 审计写入交给共享组件 —— 角色定义的变更也走它（V71），两处一套口径 */
    private void log(String merchantNo, String targetAccountNo, String action,
                     String storeNo, String role, String detail) {
        audit.staff(merchantNo, targetAccountNo, action, storeNo, role, detail);
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

    /**
     * 角色码 → 显示名（「店长」而不是 {@code MANAGER}）。
     *
     * <p>审计这行字是<b>给老板看的</b>，而他从来没见过角色码 ——
     * 自定义角色更甚：那边的码是 {@code R-…} 一串生成的业务键，
     * 写进日志等于这条记录三个月后没人读得懂。
     *
     * <p>查不到就退回码本身：角色被删掉之后日志还得留着，
     * 留一个码总比留一句「授予了 的 」强。
     */
    private String roleName(String merchantNo, String role) {
        return DataScopeContext.executeWithoutScope(() ->
                        roleDefMapper.selectList(
                                Wrappers.<ai.neargo.shop.merchant.entity.MchRole>lambdaQuery()
                                        .in(ai.neargo.shop.merchant.entity.MchRole::getEntityNo,
                                                List.of(merchantNo,
                                                        ai.neargo.shop.merchant.entity.MchRole.BUILTIN_ENTITY))
                                        .eq(ai.neargo.shop.merchant.entity.MchRole::getRoleCode, role)
                                        .last("limit 1")))
                .stream().findFirst()
                .map(ai.neargo.shop.merchant.entity.MchRole::getName)
                .filter(n -> n != null && !n.isBlank())
                .orElse(role);
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

    /**
     * 审计日志里怎么称呼一个人。**优先姓名** —— 审计是给三个月后的人看的，
     * 那时一串号码说明不了任何事；没填姓名才退到脱敏号
     * （日志长期留存，那里不需要完整号码）。
     *
     * <p><b>老板要单独兜底</b>：他的 `mch_account` 是入驻时建的，
     * `display_name` 与 `login_phone` <b>两列都是 NULL</b>（他走消费者账号登录），
     * 于是标签算出来是空字符串 —— 而绝大多数授权操作都是他做的，
     * 结果是整张日志表上「谁干的」全是空白。
     */
    private String labelOf(MchAccount a) {
        if (a.getDisplayName() != null && !a.getDisplayName().isBlank()) {
            return a.getDisplayName();
        }
        String masked = mask(a.getLoginPhone());
        if (masked != null && !masked.isBlank()) {
            return masked;
        }
        // 「店主」而不是账号号：老板认得出前者，SF2026… 谁也认不出
        return Boolean.TRUE.equals(a.getIsOwner()) ? "店主" : a.getMchAccountNo();
    }

    private StaffVO toVO(MchAccount a, Map<String, List<MchStoreRole>> byAccount,
                         Map<String, String> storeNames) {
        List<StaffVO.StoreRoleVO> roles = byAccount.getOrDefault(a.getMchAccountNo(), List.of())
                .stream()
                .map(r -> new StaffVO.StoreRoleVO(r.getStoreNo(),
                        storeNames.getOrDefault(r.getStoreNo(), r.getStoreNo()), r.getRole()))
                .toList();
        /*
         * **手机号不脱敏**（2026-08-12 拍板）。
         *
         * 之前脱敏的理由是「一份可导出的通讯录」，但那条理由在这里站不住：
         * 手机号<b>就是员工的登录用户名</b> —— 老板要核对「他是用哪个号登录的」、
         * 要在人换号时改，脱敏之后这两件事都做不了，而号码本来就是老板自己填进去的。
         *
         * 姓名（displayName）是另一件事：它是认人的，不是身份。两者都要。
         */
        return new StaffVO(a.getMchAccountNo(), a.getDisplayName(), a.getLoginPhone(),
                Boolean.TRUE.equals(a.getIsOwner()), a.getStatus(), roles);
    }

    /**
     * 手机号脱敏 —— <b>只用于审计文案</b>。
     *
     * <p>档案里的号码不脱敏（见 {@code toVO}：那是登录用户名，老板要能核对）；
     * 但日志是一行会被长期留存、可能被导出的文本，
     * 它要回答的是「谁把谁改成了什么」，不需要一个完整号码。
     */
    private String mask(String phone) {
        if (phone == null || phone.length() < 7) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}
