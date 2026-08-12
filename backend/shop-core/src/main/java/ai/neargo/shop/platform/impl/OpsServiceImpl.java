package ai.neargo.shop.platform.impl;

import ai.neargo.shop.platform.OpsService;
import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.ScopeDim;
import ai.neargo.shop.platform.perm.RolePermResolver;
import ai.neargo.shop.platform.perm.entity.SysRoleMember;
import ai.neargo.shop.platform.perm.mapper.PermMappers.RoleMemberMapper;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.spi.user.MerchantAdminPort;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.platform.dto.OpsVOs.AuditLogVO;
import ai.neargo.shop.platform.dto.OpsVOs.LoginResultVO;
import ai.neargo.shop.platform.dto.OpsVOs.MerchantApplyVO;
import ai.neargo.shop.platform.dto.OpsVOs.StaffVO;
import ai.neargo.shop.platform.OpsService.CreatedStaffVO;
import ai.neargo.shop.platform.entity.SysAuditLog;
import ai.neargo.shop.platform.entity.SysOpsStaff;
import ai.neargo.shop.platform.entity.MchEntityApply;
import ai.neargo.shop.platform.mapper.PlatformMappers.AuditLogMapper;
import ai.neargo.shop.platform.mapper.PlatformMappers.MerchantApplyMapper;
import ai.neargo.shop.platform.mapper.PlatformMappers.StaffMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.Set;
import java.util.LinkedHashSet;
import java.security.SecureRandom;
import java.util.regex.Pattern;

@Service
public class OpsServiceImpl implements OpsService {

    /**
     * 邮箱格式校验。**只挡"忘了打 @"这类最常见的手滑**，不追求 RFC 5322 全量合规——
     * 真要验证这个地址收不收得到信，得发验证邮件，那是另一件事。
     */
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final StaffMapper staffMapper;
    private final RoleMemberMapper roleMemberMapper;
    private final RolePermResolver rolePermResolver;
    private final AuditLogMapper auditLogMapper;
    private final MerchantApplyMapper applyMapper;
    private final MerchantAdminPort merchantAdminPort;
    private final TokenStore tokenStore;
    private final ObjectMapper objectMapper;
    private final ObjectMapper json;
    private final ai.neargo.shop.platform.IndustryService industryService;
    private final ai.neargo.shop.platform.MasterDataService masterDataService;

    public OpsServiceImpl(StaffMapper staffMapper, RoleMemberMapper roleMemberMapper,
                          RolePermResolver rolePermResolver,
                          AuditLogMapper auditLogMapper,
                          MerchantApplyMapper applyMapper, MerchantAdminPort merchantAdminPort,
                          TokenStore tokenStore, ObjectMapper json, ObjectMapper objectMapper,
                          ai.neargo.shop.platform.IndustryService industryService,
                          ai.neargo.shop.platform.MasterDataService masterDataService) {
        this.masterDataService = masterDataService;
        this.industryService = industryService;
        this.objectMapper = objectMapper;
        this.staffMapper = staffMapper;
        this.roleMemberMapper = roleMemberMapper;
        this.rolePermResolver = rolePermResolver;
        this.auditLogMapper = auditLogMapper;
        this.applyMapper = applyMapper;
        this.merchantAdminPort = merchantAdminPort;
        this.tokenStore = tokenStore;
        this.json = json;
    }

    // ---------------------------------------------------------------- 登录

    @Override
    public LoginResultVO login(String username, String password) {
        SysOpsStaff staff = DataScopeContext.executeWithoutScope(() ->
                staffMapper.selectOne(Wrappers.<SysOpsStaff>lambdaQuery()
                        .eq(SysOpsStaff::getUsername, username).last("limit 1")));

        // **用户不存在与密码错误返回同一个错误** —— 区分开等于送了个用户名探测器
        if (staff == null || !hash(password).equals(staff.getPassword())
                || !"ACTIVE".equals(staff.getStatus())) {
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        }

        List<String> roles = readList(staff.getRoles());
        List<String> perms = rolePermResolver.of(roles);
        String token = tokenStore.issue(TokenStore.SessionData.of(
                LoginUser.operator(staff.getStaffNo(), staff.getRealName(), roles, perms,
                        scopeOf(staff, perms))));
        /*
         * 记最近登录。加了这一列却没人写的话，员工列表那一列永远是「-」——
         * 而运营停用一个长期没登录的账号之前，看的就是它。
         * 又一个「有列没人填」，只是这次在自己的批次里。
         */
        staff.setLastLoginAt(System.currentTimeMillis());
        staffMapper.updateById(staff);
        return new LoginResultVO(token, toVO(staff, roles, perms));
    }

    @Override
    public StaffVO me() {
        LoginUser user = SecurityUtils.requireUser();
        SysOpsStaff staff = requireStaff(user.userNo());
        List<String> roles = readList(staff.getRoles());
        return toVO(staff, roles, rolePermResolver.of(roles));
    }

    @Override
    public List<StaffVO> staffList() {
        return DataScopeContext.executeWithoutScope(() ->
                        staffMapper.selectList(Wrappers.<SysOpsStaff>lambdaQuery()
                                .orderByAsc(SysOpsStaff::getId))).stream()
                .map(s -> {
                    List<String> roles = readList(s.getRoles());
                    return toVO(s, roles, rolePermResolver.of(roles));
                }).toList();
    }

    // ---------------------------------------------------------------- 入驻审核

    @Override
    public List<MerchantApplyVO> applyQueue() {
        return DataScopeContext.executeWithoutScope(() ->
                        applyMapper.selectList(Wrappers.<MchEntityApply>lambdaQuery()
                                .in(MchEntityApply::getStatus,
                                        List.of(MchEntityApply.PENDING, MchEntityApply.REVIEWING))
                                .orderByAsc(MchEntityApply::getId))).stream()
                .map(this::toVO).toList();
    }

    @Override
    public PageData<MerchantApplyVO> searchApplies(String status, String keyword, long page, long size) {
        List<String> statuses = status == null || status.isBlank()
                // 不给状态时只看待办两档 —— 运营台默认打开就该是「要我做的事」
                ? List.of(MchEntityApply.PENDING, MchEntityApply.REVIEWING)
                : Arrays.stream(status.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();

        var q = Wrappers.<MchEntityApply>lambdaQuery()
                .in(MchEntityApply::getStatus, statuses)
                /*
                 * 待办按提交顺序（先来先审），历史按最近优先。
                 * 两种排序不能合成一种：待办倒序会让最早提交的人一直排在最后，
                 * 而他等得最久。
                 */
                .orderByAsc(MchEntityApply::getId);
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            q.and(w -> w.like(MchEntityApply::getName, kw)
                    .or().like(MchEntityApply::getContactName, kw)
                    .or().like(MchEntityApply::getContactPhone, kw));
        }
        var p = DataScopeContext.executeWithoutScope(() ->
                applyMapper.selectPage(new Page<>(page, size), q));
        return PageData.of(p.getRecords().stream().map(this::toVO).toList(),
                p.getTotal(), p.getCurrent(), p.getSize());
    }

    @Override
    @Transactional
    public void acceptApply(String applyNo) {
        MchEntityApply apply = requireApply(applyNo);
        move(apply, MchEntityApply.REVIEWING);
        // 不动 activeOwner：受理不是终态，这份申请仍占着「一人一份」的名额
        DataScopeContext.executeWithoutScope(() -> applyMapper.updateById(apply));
        audit("MERCHANT_ACCEPT", applyNo, "受理；商家=" + apply.getName());
    }

    @Override
    @Transactional
    public void auditApply(String applyNo, boolean approved, String reason,
                           String serviceScope, List<String> communityNos) {
        if (!approved && (reason == null || reason.isBlank())) {
            // 不写理由的驳回等于让对方猜。申请人拿不到理由就只能反复重提
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        MchEntityApply apply = requireApply(applyNo);

        /*
         * 通过时**先把服务范围定下来**，再走状态机。
         *
         * 商家申请时可以留空（ADR-009），但通过时不能空 —— 空的后果是
         * 上着架却对谁都不可见，而这个故障没有任何报错，商家和运营都查不出原因。
         * 运营在这里补的值直接写回申请单，activate 读的就是它，只有一份真源。
         */
        if (approved) {
            if (serviceScope != null && !serviceScope.isBlank()) {
                // 值域 + 一期启用白名单。运营这条路径同样要校验 ——
                // 只校验商家侧的话，运营在审核时补一个未开放的范围照样能写进去
                masterDataService.assertServiceScopeAllowed(serviceScope);
                apply.setServiceScope(serviceScope);
            }
            if (communityNos != null && !communityNos.isEmpty()) {
                apply.setCommunityNos(writeCommunityNos(communityNos));
            }
        }

        // 走状态机而不是「是不是进行中」：后者放行了 APPROVED → APPROVED 之外的所有组合，
        // 而重复审批会重复建商家。状态机是唯一一处写着「什么能变成什么」的地方
        move(apply, approved ? MchEntityApply.APPROVED : MchEntityApply.REJECTED);

        LoginUser operator = SecurityUtils.requireUser();
        if (approved) {
            // 审核通过才创建商家主体：驳回的申请不该在库里留下一个「僵尸商家」
            /*
             * 建商家 + 配可达范围 + 建分账主体，**三件事在 Port 内部同一个事务**。
             * 少任何一件，商家就是「存在但做不了生意」，而这个故障没有任何报错。
             * 覆盖社区来自审核时的勾选（存在申请单上），不是事后再补 —— 分两步做永远有人忘。
             */
            /*
             * 末位传本申请**已激活过的主体号**：非空即重复点击，Port 按它幂等重放。
             * 传 apply.getEntityNo() 而不是「这个人的主体」—— 后者会让第二张执照
             * 覆盖掉第一个主体（两家店变一家，且不报错）。
             */
            String merchantNo = merchantAdminPort.activate(new MerchantAdminPort.ActivateCommand(
                    apply.getUserNo(), apply.getName(), apply.getLegalForm(),
                    apply.getServiceScope(), readCommunityNos(apply.getCommunityNos()),
                    null, apply.getIndustry(), apply.getDescription(),
                    apply.getEntityNo()));
            apply.setEntityNo(merchantNo);
        } else {
            apply.setRejectReason(reason);
        }
        // 终态释放「一人一份进行中」的名额：唯一索引忽略 NULL，历史申请不再占位
        apply.setActiveOwner(null);
        apply.setAuditedBy(operator.userNo());
        apply.setAuditedAt(System.currentTimeMillis());
        DataScopeContext.executeWithoutScope(() -> applyMapper.updateById(apply));

        // 审核是能改变别人生意的操作 —— 出问题时必须能回答「谁批的」
        audit("MERCHANT_AUDIT", applyNo,
                (approved ? "通过" : "驳回：" + reason) + "；商家=" + apply.getName());
    }

    @Override
    @Transactional
    public String createApply(SubmitApplyCommand cmd) {
        /*
         * 一人同时只能有一份进行中的申请。
         * 先查是为了给出人话错误；**真正兜底的是 uk_apply_active_owner 唯一键** ——
         * 表单页重复点击是常态，先查后插必然有竞态。
         */
        MchEntityApply active = DataScopeContext.executeWithoutScope(() ->
                applyMapper.selectOne(Wrappers.<MchEntityApply>lambdaQuery()
                        .eq(MchEntityApply::getActiveOwner, cmd.userNo()).last("limit 1")));
        if (active != null) {
            throw BizException.of(ErrorCode.CONFLICT);
        }

        requireSubjectAllowedByIndustry(cmd.industry(), cmd.subject());

        MchEntityApply apply = new MchEntityApply();
        apply.setApplyNo(BizKey.next(BizKey.MERCHANT_APPLY));
        apply.setUserNo(cmd.userNo());
        apply.setName(cmd.name());
        // 写入用权威码（ADR-010 §4 第 2 步）。认不出来的原样存 —— 兜底会掩盖脏数据
        String canonicalSubject = masterDataService.canonicalSubject(cmd.subject());
        apply.setLegalForm(canonicalSubject != null ? canonicalSubject : cmd.subject());
        apply.setContactName(cmd.contactName());
        apply.setContactPhone(cmd.contactPhone());
        apply.setCategory(cmd.category());
        apply.setDescription(cmd.description());
        masterDataService.assertServiceScopeAllowed(cmd.serviceScope());
        apply.setServiceScope(cmd.serviceScope() == null
                ? ai.neargo.shop.common.ServiceScopes.COMMUNITY : cmd.serviceScope());
        apply.setCommunityNos(writeJson(cmd.communityNos()));
        apply.setQualifications(writeJson(cmd.qualifications()));
        apply.setAsPickupPoint(cmd.asPickupPoint());
        apply.setIndustry(cmd.industry());
        apply.setStatus(MchEntityApply.PENDING);
        apply.setActiveOwner(cmd.userNo());   // 进行中才占名额，终态时置 NULL
        DataScopeContext.executeWithoutScope(() -> applyMapper.insert(apply));
        return apply.getApplyNo();
    }

    private MchEntityApply requireApply(String applyNo) {
        MchEntityApply apply = DataScopeContext.executeWithoutScope(() ->
                applyMapper.selectOne(Wrappers.<MchEntityApply>lambdaQuery()
                        .eq(MchEntityApply::getApplyNo, applyNo).last("limit 1")));
        if (apply == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return apply;
    }

    /** 按状态机推进，非法迁移当场拒。 */
    private static void move(MchEntityApply apply, String to) {
        if (!MchEntityApply.canMove(apply.getStatus(), to)) {
            throw BizException.of(ErrorCode.ORDER_STATE_ILLEGAL);
        }
        apply.setStatus(to);
    }

    /**
     * <b>行业决定可选主体</b>（M2 / 支付通道准入）。
     *
     * <p>微信小微的准入白名单是按行业给的，线上业态不支持。这条规则必须在
     * <b>提交申请那一刻</b>就生效，而不是等到进件：
     * 等到那时入驻早已通过、商家已经在上架商品，再告诉他「你这行不能用这个主体」，
     * 要么改主体重走一遍资质，要么这家店根本收不了款 —— 而这两条都不是他的错。
     *
     * <p>只拦「小微」这一档。个体户/企业不受行业白名单限制，
     * 它们本来就要提交营业执照，通道走的是另一套准入。
     *
     * <p>行业为空时<b>放行</b>：存量商家的行业还要人工映射（V40 刻意可空），
     * 这里拦住等于把老商家的重新提交也一并堵死。行业的必填由端上表单保证。
     */
    private void requireSubjectAllowedByIndustry(String industry, String subject) {
        if (industry == null || industry.isBlank()) {
            return;
        }
        /*
         * 「这个主体受不受行业白名单限制」不再硬编码成 PERSONAL，而是查主数据
         * （sys_legal_form.industry_gated）。此前这个判断在代码里出现过三次、
         * 各写各的 —— 判错一次商家就是进件被拒。
         *
         * canonicalSubject 把存量取值（PERSONAL/INDIVIDUAL_BIZ/COMPANY）翻译成
         * 通道口径的权威码，映射只此一份。
         */
        String canonical = masterDataService.canonicalSubject(subject);
        if (canonical == null || !masterDataService.industryGated(canonical)) {
            return;
        }
        // 两家通道任一允许即放行 —— 商家最终走哪个通道由进件时决定，
        // 这里只拦「两家都不收」的组合。全拦死会把只能走支付宝的行业挡在门外
        boolean ok = industryService.microAllowed(industry, "WECHAT")
                || industryService.microAllowed(industry, "ALIPAY");
        if (!ok) {
            throw BizException.of(ErrorCode.INDUSTRY_SUBJECT_NOT_ALLOWED);
        }
    }

    @Override
    public MerchantApplyVO myApply(String userNo) {
        // 取最近一份：被驳回后重提会有多份，商家关心的是最新那份的进度
        MchEntityApply apply = DataScopeContext.executeWithoutScope(() ->
                applyMapper.selectOne(Wrappers.<MchEntityApply>lambdaQuery()
                        .eq(MchEntityApply::getUserNo, userNo)
                        .orderByDesc(MchEntityApply::getId).last("limit 1")));
        return apply == null ? null : toVO(apply);
    }


    // ---------------------------------------------------------------- 审计

    @Override
    public void audit(String action, String target, String detail) {
        LoginUser operator = SecurityUtils.currentUser().orElse(null);
        SysAuditLog log = new SysAuditLog();
        log.setStaffNo(operator == null ? "SYSTEM" : operator.userNo());
        log.setStaffName(operator == null ? "系统" : operator.nickname());
        log.setOpAction(action);
        log.setTarget(target);
        log.setDetail(detail);
        log.setAt(System.currentTimeMillis());
        log.setTenantNo("MAIN");
        log.setCreatedAt(LocalDateTime.now());
        DataScopeContext.executeWithoutScope(() -> auditLogMapper.insert(log));
    }

    @Override
    public List<AuditLogVO> auditLogs(String target) {
        var w = Wrappers.<SysAuditLog>lambdaQuery();
        if (target != null && !target.isBlank()) {
            w.eq(SysAuditLog::getTarget, target);
        }
        w.orderByDesc(SysAuditLog::getId).last("limit 200");
        return DataScopeContext.executeWithoutScope(() -> auditLogMapper.selectList(w)).stream()
                .map(l -> new AuditLogVO(l.getStaffNo(), l.getStaffName(), l.getOpAction(),
                        l.getTarget(), l.getDetail(), l.getAt() == null ? 0L : l.getAt()))
                .toList();
    }

    // ---------------------------------------------------------------- 内部

    private SysOpsStaff requireStaff(String staffNo) {
        SysOpsStaff staff = DataScopeContext.executeWithoutScope(() ->
                staffMapper.selectOne(Wrappers.<SysOpsStaff>lambdaQuery()
                        .eq(SysOpsStaff::getStaffNo, staffNo).last("limit 1")));
        if (staff == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return staff;
    }

    /**
     * 一期占位哈希。**接 auth-core 时换 bcrypt** ——
     * 这里刻意不用「明文比较」，否则替换时容易漏掉某处比较逻辑。
     */
    public static String hash(String raw) {
        return Integer.toHexString(("shop$" + raw).hashCode());
    }

    private StaffVO toVO(SysOpsStaff s, List<String> roles, List<String> perms) {
        return new StaffVO(s.getStaffNo(), s.getUsername(), s.getRealName(),
                roles, perms, s.getStatus(),
                s.getMerchantNo(), s.getCommunityNo(), s.getPickupNo(), s.getLastLoginAt(),
                Boolean.TRUE.equals(s.getMustChangePassword()));
    }

    // ---------------------------------------------------------------- 员工写侧

    /** 拥有通配权限的角色不受数据域约束 —— 给他们配数据域是配置错误。 */
    /**
     * 把员工身上的三个归属键翻成数据域规则。
     *
     * <p><b>这一句是「数据域」这半个权限模型此前唯一缺的东西</b>：
     * 拦截器、表注册、{@code DataScopeSpec} 全都在，
     * 而 {@code LoginUser.operator} 一律签发 {@code ALL} ——
     * 于是配好的数据域一路带到 token 就被丢掉了，
     * 运营以为「已限定到某商家」，那个人照样看到全量。
     *
     * <p><b>全量角色一律 ALL</b>：超管即使库里有残留的归属键也不受限 ——
     * 与 {@code setStaffScope} 拒绝给全量角色配数据域是同一条规矩的两面。
     *
     * <p>三个键都为空时返回 {@code ALL}。**空 = 不限定**，
     * 不能返回一条 refs 为空的规则 —— 那会被翻成 {@code IN ()}，这个人什么都看不到。
     */
    private static ai.neargo.common.data.scope.DataScopeSpec scopeOf(SysOpsStaff staff,
                                                                     List<String> perms) {
        if (perms.contains("*")) {
            return ai.neargo.common.data.scope.DataScopeSpec.ALL;
        }
        List<ai.neargo.common.data.scope.DataScopeSpec.Rule> rules = new java.util.ArrayList<>();
        addRule(rules, ScopeDim.MERCHANT, staff.getMerchantNo());
        addRule(rules, ScopeDim.COMMUNITY, staff.getCommunityNo());
        addRule(rules, ScopeDim.PICKUP, staff.getPickupNo());
        return rules.isEmpty() ? ai.neargo.common.data.scope.DataScopeSpec.ALL
                : new ai.neargo.common.data.scope.DataScopeSpec(false, rules);
    }

    private static void addRule(List<ai.neargo.common.data.scope.DataScopeSpec.Rule> rules,
                                String dim, String value) {
        if (value != null && !value.isBlank()) {
            rules.add(new ai.neargo.common.data.scope.DataScopeSpec.Rule(dim, java.util.Set.of(value)));
        }
    }

    private boolean isFullAccess(List<String> roles) {
        return rolePermResolver.of(roles).contains("*");
    }

    @Override
    @Transactional
    public StaffVO setStaffEnabled(String staffNo, boolean enabled) {
        SysOpsStaff staff = requireStaff(staffNo);
        /*
         * 不能停用自己。超管把自己停了就没人能改回来 ——
         * 只能去库里手改，而那时通常是深夜。
         * 这类「把自己锁在门外」的操作，拦住的成本远低于事后恢复。
         */
        if (staffNo.equals(SecurityUtils.currentUserNo())) {
            throw BizException.of(ErrorCode.STAFF_SELF_OPERATION);
        }
        staff.setStatus(enabled ? "ACTIVE" : "DISABLED");
        staffMapper.updateById(staff);
        /*
         * 停用要**踢掉在线会话**：只改状态的话，已经登录的人在 token 过期之前
         * 照常操作 —— 而停用他的那个人以为立刻生效了。
         */
        if (!enabled) {
            tokenStore.revokeUser(staffNo);
        }
        audit("STAFF_ENABLED", staffNo, enabled ? "启用" : "停用");
        List<String> roles = readList(staff.getRoles());
        return toVO(staff, roles, rolePermResolver.of(roles));
    }

    @Override
    @Transactional
    public StaffVO setStaffRole(String staffNo, String role) {
        if (role == null || role.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        /*
         * **角色码必须真实存在**。写进一个后端没配过的角色（比如前端的
         * CAMPAIGN_OPS 在后端补配置之前），这个账号的 perms 会是空集 ——
         * 他能登录、导航一片空白、页面上看不出任何原因。
         * 那种「界面正常、就是什么都没有」的故障最难查。
         */
        if (rolePermResolver.of(List.of(role)).isEmpty()) {
            throw BizException.of(ErrorCode.STAFF_ROLE_UNKNOWN, role);
        }
        SysOpsStaff staff = requireStaff(staffNo);
        // 不能改自己的角色：与不能停用自己同一个理由（超管把自己降成客服就回不去了）
        if (staffNo.equals(SecurityUtils.currentUserNo())) {
            throw BizException.of(ErrorCode.STAFF_SELF_OPERATION);
        }
        String before = staff.getRoles();
        staff.setRoles("[\"" + role + "\"]");
        staffMapper.updateById(staff);
        /*
         * **同步 sys_role_member**。
         *
         * 判权读 sys_ops_staff.roles，而动态菜单读 sys_role_member ——
         * 只写一处的后果是「改完角色，他的权限变了、菜单没变」：
         * 新角色该看的菜单不出现，旧角色的还在，而两边的数据各自都说得通。
         *
         * 这正是 DevSeeder 那里刚写过的「员工与他的角色是同一件事，
         * 分两处写必然漏一处」—— 而我在写接口这一侧漏了。
         * 两张表并存是迁移期的过渡（roles 列保留但停写），
         * 过渡期内**每一处改角色的地方都要同时写两边**。
         */
        syncRoleMember(staffNo, List.of(role));
        // 换角色即刻生效：旧会话里带的是旧 perms
        tokenStore.revokeUser(staffNo);
        audit("STAFF_ROLE", staffNo, before + " → " + role);
        List<String> roles = List.of(role);
        return toVO(staff, roles, rolePermResolver.of(roles));
    }

    @Override
    @Transactional
    public CreatedStaffVO createStaff(String username, String realName, List<String> roles) {
        if (!notBlank(username) || !notBlank(realName)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        /*
         * **新建员工的登录名必须是邮箱。**存量账号（admin/bd 这类短用户名）不受影响——
         * 这条校验只挡在 createStaff 这一个入口，不回填、不改老数据。
         * 邮箱格式用一个足够严格、不做网络校验的正则：本地部分 + @ + 至少一个点的域名，
         * 挡得住"忘了打 @"这种最常见的手滑，不追求 RFC 5322 全量合规。
         */
        if (!EMAIL.matcher(username).matches()) {
            throw BizException.of(ErrorCode.STAFF_USERNAME_NOT_EMAIL);
        }
        List<String> want = normalizeRoles(roles);
        // **不能给自己加角色**（这里是「建一个新账号」，自然不涉及自己），
        // 但角色码仍要逐个校验：写进一个不存在的角色，这个账号 perms 为空、
        // 能登录、导航全空，而页面上看不出任何原因
        for (String r : want) {
            if (rolePermResolver.of(List.of(r)).isEmpty()) {
                throw BizException.of(ErrorCode.STAFF_ROLE_UNKNOWN, r);
            }
        }
        boolean dup = DataScopeContext.executeWithoutScope(() ->
                staffMapper.selectCount(Wrappers.<SysOpsStaff>lambdaQuery()
                        .eq(SysOpsStaff::getUsername, username)) > 0);
        if (dup) {
            throw BizException.of(ErrorCode.STAFF_USERNAME_TAKEN, username);
        }

        String initial = randomPassword();
        SysOpsStaff staff = new SysOpsStaff();
        staff.setStaffNo("E" + System.currentTimeMillis() % 100000000L);
        staff.setUsername(username);
        staff.setRealName(realName);
        staff.setPassword(hash(initial));
        staff.setRoles(writeList(want));
        staff.setStatus("ACTIVE");
        staff.setMustChangePassword(true);
        staffMapper.insert(staff);
        syncRoleMember(staff.getStaffNo(), want);

        // 审计里**不写密码**。写了就等于把「一次性」这件事作废
        audit("STAFF_CREATE", staff.getStaffNo(), username + " / " + String.join(",", want));
        return new CreatedStaffVO(toVO(staff, want, rolePermResolver.of(want)), initial);
    }

    @Override
    @Transactional
    public StaffVO setStaffRoles(String staffNo, List<String> roles) {
        List<String> want = normalizeRoles(roles);
        if (want.isEmpty()) {
            // 空角色 = 能登录但什么都点不动，且界面上看不出原因。要停用请用 enabled
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        for (String r : want) {
            if (rolePermResolver.of(List.of(r)).isEmpty()) {
                throw BizException.of(ErrorCode.STAFF_ROLE_UNKNOWN, r);
            }
        }
        SysOpsStaff staff = requireStaff(staffNo);
        /*
         * **不能改自己的角色。**
         *
         * 单角色版这条闸的理由是「超管把自己降成客服就回不去了」，
         * 多角色版还多一条、而且更重要：**否则有 iam:staff:update 的人
         * 可以顺手给自己加超管** —— 降权是自己倒霉，提权是所有人的事。
         */
        if (staffNo.equals(SecurityUtils.currentUserNo())) {
            throw BizException.of(ErrorCode.STAFF_SELF_OPERATION);
        }
        String before = staff.getRoles();
        staff.setRoles(writeList(want));
        staffMapper.updateById(staff);
        syncRoleMember(staffNo, want);
        // 换角色即刻生效：旧会话里带的是旧 perms
        tokenStore.revokeUser(staffNo);
        audit("STAFF_ROLES", staffNo, before + " → " + writeList(want));
        return toVO(staff, want, rolePermResolver.of(want));
    }

    @Override
    @Transactional
    public void changeOwnPassword(String oldPassword, String newPassword) {
        if (!notBlank(newPassword) || newPassword.length() < 8) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        SysOpsStaff staff = requireStaff(SecurityUtils.requireUser().userNo());
        if (!hash(oldPassword == null ? "" : oldPassword).equals(staff.getPassword())) {
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        }
        staff.setPassword(hash(newPassword));
        staff.setMustChangePassword(false);
        staffMapper.updateById(staff);
        /*
         * 改完密码把自己的其它会话踢掉 —— 改密的常见动机就是「怀疑密码泄露了」，
         * 不踢的话拿着旧 token 的人照样在线，改密等于没改。
         */
        tokenStore.revokeUser(staff.getStaffNo());
        audit("STAFF_PASSWORD", staff.getStaffNo(), "自助改密");
    }

    /** 去空白、去重、保序 —— 「配了两遍同一个角色」不该变成两行成员关系 */
    private static List<String> normalizeRoles(List<String> roles) {
        return roles == null ? List.of() : roles.stream()
                .filter(OpsServiceImpl::notBlank).map(String::trim).distinct().toList();
    }

    private static String writeList(List<String> roles) {
        return roles.stream().map(r -> "\"" + r + "\"")
                .collect(Collectors.joining(",", "[", "]"));
    }

    /**
     * 初始密码。**不要求好记** —— 它的寿命只到第一次登录，
     * 好记反而会让人想留着用。
     */
    private static String randomPassword() {
        final String alphabet = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        SecureRandom rnd = new SecureRandom();
        StringBuilder sb = new StringBuilder(12);
        for (int i = 0; i < 12; i++) {
            sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    @Override
    @Transactional
    public StaffVO setStaffScope(String staffNo, String merchantNo, String communityNo,
                                 String pickupNo) {
        SysOpsStaff staff = requireStaff(staffNo);
        List<String> roles = readList(staff.getRoles());
        boolean anyScope = notBlank(merchantNo) || notBlank(communityNo) || notBlank(pickupNo);
        /*
         * 给全量角色配数据域**直接拒绝**，而不是存下来。
         * 存下来的后果是：配置页显示「已限定到某商家」，而这个人照样看到全量 ——
         * 一个看着生效、实际没有的限制，比没有限制更危险。
         */
        if (anyScope && isFullAccess(roles)) {
            throw BizException.of(ErrorCode.STAFF_SCOPE_ON_FULL_ACCESS);
        }
        staff.setMerchantNo(blankToNull(merchantNo));
        staff.setCommunityNo(blankToNull(communityNo));
        staff.setPickupNo(blankToNull(pickupNo));
        staffMapper.updateById(staff);
        // 数据域进 token，改了要重登才生效
        tokenStore.revokeUser(staffNo);
        audit("STAFF_SCOPE", staffNo, "merchant=" + merchantNo + "｜community=" + communityNo
                + "｜pickup=" + pickupNo);
        return toVO(staff, roles, rolePermResolver.of(roles));
    }

    /**
     * 把这个人的角色成员行重写成「只有 role 这一个」。
     *
     * <p>先删后插而不是增量比对：运营端一个人只有一个角色（下拉单选），
     * 增量比对是为多选设计的复杂度，这里用不上。
     */
    /**
     * 同步 {@code sys_role_member} 到给定角色集合。
     *
     * <p><b>按集合增删，不是清空重插</b>：{@code granted_at} / {@code granted_by} 是审计信息 ——
     * 清空重插会把「这个角色是三个月前谁给的」改成「今天我给的」，
     * 而那正是权限审计要查的东西。只动真正变化的那几行。
     */
    private void syncRoleMember(String staffNo, List<String> roles) {
        List<SysRoleMember> existing = roleMemberMapper.selectList(
                Wrappers.<SysRoleMember>lambdaQuery()
                        .eq(SysRoleMember::getEndCode, "OPS")
                        .eq(SysRoleMember::getSubjectNo, staffNo));
        Set<String> want = new LinkedHashSet<>(roles);
        Set<String> have = existing.stream().map(SysRoleMember::getRoleCode)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        for (SysRoleMember m : existing) {
            if (!want.contains(m.getRoleCode())) {
                roleMemberMapper.deleteById(m.getId());
            }
        }
        for (String role : want) {
            if (have.contains(role)) {
                continue;
            }
            SysRoleMember m = new SysRoleMember();
            m.setEndCode("OPS");
            m.setSubjectNo(staffNo);
            m.setRoleCode(role);
            m.setGrantedBy(SecurityUtils.currentUserNo());
            m.setGrantedAt(System.currentTimeMillis());
            roleMemberMapper.insert(m);
        }
    }

    private static boolean notBlank(String v) {
        return v != null && !v.isBlank();
    }

    /** 空字符串归一成 null：**空 = 不限定**，两种「空」在库里要长一样 */
    private static String blankToNull(String v) {
        return notBlank(v) ? v : null;
    }

    private MerchantApplyVO toVO(MchEntityApply a) {
        return new MerchantApplyVO(a.getApplyNo(), a.getEntityNo(), a.getName(),
                a.getLegalForm(), a.getContactName(), a.getContactPhone(),
                a.getCategory(), a.getDescription(), a.getServiceScope(),
                readList(a.getCommunityNos()), readList(a.getQualifications()),
                Boolean.TRUE.equals(a.getAsPickupPoint()), a.getIndustry(),
                a.getStatus(), a.getRejectReason(),
                a.getCreatedAt() == null ? 0L
                        : a.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
                a.getAuditedAt() == null ? 0L : a.getAuditedAt());
    }

    private String writeJson(List<String> values) {
        try {
            return json.writeValueAsString(values == null ? List.of() : values);
        } catch (Exception e) {
            return "[]";
        }
    }

    private List<String> readList(String jsonArray) {
        if (jsonArray == null || jsonArray.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(jsonArray, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 运营在审核时补的社区列表写回申请单 —— activate 读的就是它，只有一份真源 */
    private String writeCommunityNos(List<String> nos) {
        return objectMapper.writeValueAsString(nos);
    }

    /** 申请单上的社区列表存 JSON；解析失败按空处理，由 activate 的必填校验兜住 */
    private List<String> readCommunityNos(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

}
