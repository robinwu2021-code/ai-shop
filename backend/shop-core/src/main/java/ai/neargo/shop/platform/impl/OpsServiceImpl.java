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
import ai.neargo.shop.common.Masks;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.message.entity.SysNotifyLog;
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
import ai.neargo.shop.auth.RequestMetaContext;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.Set;
import java.util.LinkedHashSet;
import java.security.SecureRandom;
import java.util.regex.Pattern;

@Service
public class OpsServiceImpl implements OpsService {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(OpsServiceImpl.class);

    /**
     * 邮箱格式校验。**只挡"忘了打 @"这类最常见的手滑**，不追求 RFC 5322 全量合规——
     * 真要验证这个地址收不收得到信，得发验证邮件，那是另一件事。
     */
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final StaffMapper staffMapper;
    private final RoleMemberMapper roleMemberMapper;
    private final RolePermResolver rolePermResolver;
    /** 改角色/数据域之后清它 —— 身份也是现算的了（见 LiveIdentityResolver） */
    private final ai.neargo.shop.platform.perm.StaffIdentityResolver identityResolver;
    private final AuditLogMapper auditLogMapper;
    private final MerchantApplyMapper applyMapper;
    private final MerchantAdminPort merchantAdminPort;
    private final TokenStore tokenStore;
    private final ObjectMapper objectMapper;
    private final ObjectMapper json;
    private final ai.neargo.shop.platform.IndustryService industryService;
    private final ai.neargo.shop.platform.MasterDataService masterDataService;
    private final ai.neargo.shop.auth.PasswordHasher passwordHasher;
    private final ai.neargo.shop.message.notify.NotifyLoggingMailPort mailPort;
    private final PasswordResetTokens resetTokens;
    private final ai.neargo.shop.common.ratelimit.RateLimiter resetLimiter;

    /**
     * 初始密码怎么交付。{@code mail}（默认）= 邮件发本人、接口不返回明文；
     * {@code response} = 维持旧行为，是**邮件不通时的逃生口**。
     */
    private final String passwordDelivery;

    private static final String MAIL_DELIVERY = "mail";

    public OpsServiceImpl(StaffMapper staffMapper, RoleMemberMapper roleMemberMapper,
                          RolePermResolver rolePermResolver,
                          ai.neargo.shop.platform.perm.StaffIdentityResolver identityResolver,
                          AuditLogMapper auditLogMapper,
                          MerchantApplyMapper applyMapper, MerchantAdminPort merchantAdminPort,
                          TokenStore tokenStore, ObjectMapper json, ObjectMapper objectMapper,
                          ai.neargo.shop.platform.IndustryService industryService,
                          ai.neargo.shop.platform.MasterDataService masterDataService,
                          ai.neargo.shop.auth.PasswordHasher passwordHasher,
                          ai.neargo.shop.message.notify.NotifyLoggingMailPort mailPort,
                          PasswordResetTokens resetTokens,
                          ai.neargo.shop.common.ratelimit.RateLimiter resetLimiter,
                          @org.springframework.beans.factory.annotation.Value(
                                  "${shop.ops.password-delivery:mail}") String passwordDelivery) {
        this.mailPort = mailPort;
        this.resetTokens = resetTokens;
        this.resetLimiter = resetLimiter;
        this.passwordDelivery = passwordDelivery;
        this.masterDataService = masterDataService;
        this.passwordHasher = passwordHasher;
        this.industryService = industryService;
        this.objectMapper = objectMapper;
        this.staffMapper = staffMapper;
        this.roleMemberMapper = roleMemberMapper;
        this.identityResolver = identityResolver;
        this.rolePermResolver = rolePermResolver;
        this.auditLogMapper = auditLogMapper;
        this.applyMapper = applyMapper;
        this.merchantAdminPort = merchantAdminPort;
        this.tokenStore = tokenStore;
        this.json = json;
    }

    /**
     * 启动时报一次：还有多少账号停在一期占位哈希。
     *
     * <p><b>刻意做成一条会自己消失的日志</b>，而不是接口上的一个字段：
     * 字段会永远在那里（哪怕早就 0 个），而运营看了也不知道该做什么；
     * 日志在数字降到 0 之后就不再出现，那本身就是「迁完了」的信号。
     */
    @org.springframework.context.event.EventListener(
            org.springframework.boot.context.event.ApplicationReadyEvent.class)
    void reportLegacyPasswords() {
        try {
            long n = staffMapper.selectList(Wrappers.<SysOpsStaff>lambdaQuery()
                            .notLike(SysOpsStaff::getPassword, "$2%")).size();
            if (n > 0) {
                log.warn("还有 {} 个运营账号是一期占位哈希 —— 他们下次登录时会自动升级成 bcrypt。"
                        + "长期不登录的账号会一直停在旧格式", n);
            }
        } catch (Exception e) {
            // 一条提示而已，查不出来不该影响启动
            log.debug("统计存量密码失败：{}", e.toString());
        }
    }

    // ---------------------------------------------------------------- 登录

    @Override
    public LoginResultVO login(String username, String password) {
        SysOpsStaff staff = DataScopeContext.executeWithoutScope(() ->
                staffMapper.selectOne(Wrappers.<SysOpsStaff>lambdaQuery()
                        .eq(SysOpsStaff::getUsername, username).last("limit 1")));

        // **用户不存在与密码错误返回同一个错误** —— 区分开等于送了个用户名探测器
        if (staff == null || !passwordHasher.matches(password, staff.getPassword())
                || !"ACTIVE".equals(staff.getStatus())) {
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        }
        /*
         * 存量密码就地升级成 bcrypt。**登录成功这一刻是唯一能拿到明文的时机**。
         *
         * 放在验证之后：验证失败也重写等于把错误密码写进库，
         * 而那种故障发生在「用户下次输对了密码」的时刻，最难让人相信是系统的问题。
         */
        if (passwordHasher.needsUpgrade(staff.getPassword())) {
            staff.setPassword(passwordHasher.encode(password));
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

            /*
             * **把入驻时收的资质转存到主体档案上。**
             *
             * 此前这一步不存在：商家传的执照停在 mch_entity_apply 里，
             * 而上架的两个闸门（资质过期、类目授权）读的是 mch_qualification ——
             * 那张表实测 0 行，于是两个闸门都写好了、都从不触发。
             *
             * 放在 activate 之后：主体号要先有。与本方法同一个事务，
             * 转存失败则审核一并回滚 —— 半通过（主体建了、资质没进）是最难查的状态。
             */
            int saved = merchantAdminPort.saveQualifications(
                    merchantNo, toPortItems(apply.getQualificationItems()));
            if (saved > 0) {
                audit("MERCHANT_QUALIFICATION_IMPORT", merchantNo,
                        "入驻转存资质 " + saved + " 条");
            }
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
        requireLicenseIfNeeded(cmd);

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
        apply.setQualificationItems(writeItemsJson(cmd.qualificationItems()));
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
    /**
     * 需要执照的档位，提交时就必须带营业执照。
     *
     * <p><b>此前提交与审核两侧都不校验</b>，唯一硬拦在支付进件 ——
     * 于是没有执照的商家可以完整走完入驻、建店、上架，
     * 直到他去开通收款那一刻才被拦，而那时商品已经在架上了。
     *
     * <p>免执照的档位（自然人）跳过 —— 对他们要执照本来就是错的。
     */
    private void requireLicenseIfNeeded(SubmitApplyCommand cmd) {
        /*
         * ⚠️ **只对已支持结构化资质的客户端生效**（qualificationItems 非 null）。
         *
         * 端上还没开始传这个字段时硬拦，等于把入驻整条路堵死 ——
         * 我第一版就是这么写的，当场打断 24 个测试 fixture 里的商家创建。
         * 校验必须晚于「能满足它的 UI」上线，否则拦的不是坏商家，是所有人。
         *
         * 字段非 null = 这个端知道结构化资质这回事，那就按新规矩要求它。
         * b-app 逐项录入上线后（第二步 2-5），这条自然全量生效。
         *
         * <b>在此之前真正的防线是审核那一侧</b>：运营看不到执照就不该点通过。
         */
        if (cmd.qualificationItems() == null) {
            return;
        }
        String canonical = masterDataService.canonicalSubject(cmd.subject());
        if (!masterDataService.needLicense(canonical != null ? canonical : cmd.subject())) {
            return;
        }
        boolean hasLicense = cmd.qualificationItems().stream().anyMatch(it -> it != null
                && "BUSINESS_LICENSE".equals(it.type()));
        if (!hasLicense) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }

    /** 申请单上的资质 JSON → Port 的入参。解析不出来当作空，不抛 —— 审核不该被脏数据卡死 */
    private java.util.List<ai.neargo.shop.spi.user.MerchantAdminPort.QualificationItem>
            toPortItems(String jsonText) {
        if (jsonText == null || jsonText.isBlank()) {
            return java.util.List.of();
        }
        try {
            java.util.List<OpsService.QualificationItem> raw = this.json.readValue(jsonText,
                    new TypeReference<java.util.List<OpsService.QualificationItem>>() { });
            return raw == null ? java.util.List.of() : raw.stream()
                    .map(it -> new ai.neargo.shop.spi.user.MerchantAdminPort.QualificationItem(
                            it.type(), it.code(), it.imageUrl(), it.expireAt(), it.issuer()))
                    .toList();
        } catch (RuntimeException e) {
            // 解析不出来当作空：**审核不该被一条脏数据卡死**。
            // 但不要静默 —— 记一条审计，否则「资质没转过来」就成了无从追查的哑故障
            audit("MERCHANT_QUALIFICATION_PARSE_FAIL", "", "结构化资质解析失败，已按空处理");
            return java.util.List.of();
        }
    }

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
        audit(action, target, detail, false, null, null);
    }

    @Override
    public void audit(String action, String target, String detail, boolean critical,
                       String beforeJson, String afterJson) {
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
        log.setCritical(critical);
        log.setBeforeJson(beforeJson);
        log.setAfterJson(afterJson);
        /*
         * IP/操作端不直接碰 HttpServletRequest —— platform 是领域代码，
         * 不能依赖 web 运行时（ArchitectureTest.domainsMustNotTouchWebRuntime）。
         * 认证过滤器把这两样塞进 RequestMetaContext，这里只读；非请求线程
         * （SYSTEM 触发、后台任务）取不到就是 null，不是 bug。
         */
        RequestMetaContext.Meta meta = RequestMetaContext.current();
        log.setIp(meta == null ? null : meta.ip());
        log.setClientType(meta == null ? null : meta.clientType());
        DataScopeContext.executeWithoutScope(() -> auditLogMapper.insert(log));
    }

    @Override
    public PageData<AuditLogVO> auditLogs(String target, String keyword, Boolean critical, long page, long size) {
        var w = Wrappers.<SysAuditLog>lambdaQuery();
        if (target != null && !target.isBlank()) {
            w.eq(SysAuditLog::getTarget, target);
        }
        if (critical != null) {
            w.eq(SysAuditLog::getCritical, critical);
        }
        if (keyword != null && !keyword.isBlank()) {
            w.and(x -> x.like(SysAuditLog::getStaffNo, keyword)
                    .or().like(SysAuditLog::getStaffName, keyword)
                    .or().like(SysAuditLog::getOpAction, keyword)
                    .or().like(SysAuditLog::getTarget, keyword)
                    .or().like(SysAuditLog::getDetail, keyword));
        }
        w.orderByDesc(SysAuditLog::getId);
        Page<SysAuditLog> p = DataScopeContext.executeWithoutScope(
                () -> auditLogMapper.selectPage(Page.of(page, size), w));
        List<AuditLogVO> records = p.getRecords().stream()
                .map(l -> new AuditLogVO(l.getId(), l.getStaffNo(), l.getStaffName(), l.getOpAction(),
                        l.getTarget(), l.getDetail(), l.getAt() == null ? 0L : l.getAt(),
                        l.getIp(), l.getClientType(), Boolean.TRUE.equals(l.getCritical()),
                        l.getBeforeJson(), l.getAfterJson()))
                .toList();
        return PageData.of(records, p.getTotal(), p.getCurrent(), p.getSize());
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
        audit("STAFF_ENABLED", staffNo, enabled ? "启用" : "停用", true,
                objectMapper.writeValueAsString(Map.of("status", enabled ? "DISABLED" : "ACTIVE")),
                objectMapper.writeValueAsString(Map.of("status", enabled ? "ACTIVE" : "DISABLED")));
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
        /*
         * **不再踢会话**（2026-08-13）：角色与数据域已经改成每请求现算
         * （`LiveIdentityResolver`），会话里只剩「他是谁」。改完清一次快照，
         * 同实例下一个请求就是新身份，跨实例最坏一个 TTL —— 而且不打断任何人。
         *
         * 要立刻收回权限（开错了），走运营端那个显式的「强制重新登录」按钮。
         */
        identityResolver.invalidate();
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
        staff.setPassword(passwordHasher.encode(initial));
        staff.setRoles(writeList(want));
        staff.setStatus("ACTIVE");
        staff.setMustChangePassword(true);
        staffMapper.insert(staff);
        syncRoleMember(staff.getStaffNo(), want);

        // 审计里**不写密码**。写了就等于把「一次性」这件事作废
        audit("STAFF_CREATE", staff.getStaffNo(), username + " / " + String.join(",", want));

        /*
         * 密码交付。**默认走邮件**，接口不再返回明文。
         *
         * 发失败就整体回滚（不 catch）：留下一个「已建号但没人知道密码」的账号
         * 比原来的问题更糟 —— 它看起来是正常账号，只是永远没人登得进去，
         * 而运营会以为建成功了，等新同事说登不上才发现。
         *
         * `response` 模式是**逃生口**：邮件不通时（密码错、MFA、SMTP 被封）
         * 若没有它，新建的账号就永远没人能登录，而那时管理员可能连一个
         * 能用的运营账号都没有。
         */
        if (MAIL_DELIVERY.equals(passwordDelivery)) {
            mailPort.send(username, "【数智邻购】运营端账号已开通",
                    "你好 " + realName + "，\n\n"
                            + "你的运营端账号已开通。\n"
                            + "登录名：" + username + "\n"
                            + "初始密码：" + initial + "\n\n"
                            + "**首次登录会要求你立即修改密码**。请勿转发本邮件。\n",
                    SysNotifyLog.BIZ_OPS_INIT_PASSWORD, SecurityUtils.currentUserNo());
            return new CreatedStaffVO(toVO(staff, want, rolePermResolver.of(want)),
                    null, Masks.email(username));
        }
        return new CreatedStaffVO(toVO(staff, want, rolePermResolver.of(want)), initial, null);
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
        /*
         * **不再踢会话**（2026-08-13）：角色与数据域已经改成每请求现算
         * （`LiveIdentityResolver`），会话里只剩「他是谁」。改完清一次快照，
         * 同实例下一个请求就是新身份，跨实例最坏一个 TTL —— 而且不打断任何人。
         *
         * 要立刻收回权限（开错了），走运营端那个显式的「强制重新登录」按钮。
         */
        identityResolver.invalidate();
        audit("STAFF_ROLES", staffNo, before + " → " + writeList(want), true,
                objectMapper.writeValueAsString(Map.of("roles", readList(before))),
                objectMapper.writeValueAsString(Map.of("roles", want)));
        return toVO(staff, want, rolePermResolver.of(want));
    }

    @Override
    @Transactional
    public void changeOwnPassword(String oldPassword, String newPassword) {
        if (!notBlank(newPassword) || newPassword.length() < 8) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        SysOpsStaff staff = requireStaff(SecurityUtils.requireUser().userNo());
        if (!passwordHasher.matches(oldPassword, staff.getPassword())) {
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        }
        staff.setPassword(passwordHasher.encode(newPassword));
        staff.setMustChangePassword(false);
        staffMapper.updateById(staff);
        /*
         * 改完密码把自己的其它会话踢掉 —— 改密的常见动机就是「怀疑密码泄露了」，
         * 不踢的话拿着旧 token 的人照样在线，改密等于没改。
         */
        tokenStore.revokeUser(staff.getStaffNo());
        audit("STAFF_PASSWORD", staff.getStaffNo(), "自助改密", true, null, null);
    }

    @Override
    public void forgotPassword(String username) {
        SysOpsStaff staff = DataScopeContext.executeWithoutScope(() ->
                staffMapper.selectOne(Wrappers.<SysOpsStaff>lambdaQuery()
                        .eq(SysOpsStaff::getUsername, username).last("limit 1")));
        /*
         * **账号不存在 / 已停用时也正常返回**，不抛错、不提示。
         *
         * 区分开就等于送了个账号探测器：拿一份邮箱清单批量试，就能问出哪些是运营账号，
         * 而运营账号的价值远高于普通用户（改费率、批提现、封商家）。
         * 与 login() 那处「用户不存在与密码错误返回同一个错误」是同一条规矩。
         */
        if (staff == null || !"ACTIVE".equals(staff.getStatus())) {
            log.info("[ops] 忘记密码：{} 不存在或已停用，静默返回", Masks.email(username));
            return;
        }
        /*
         * 限流按**账号**而不是按 IP：这条端点会往真实邮箱发信，
         * 不限的话任何人都能拿它把某个运营的邮箱刷爆（而且发件人是我们）。
         */
        if (!resetLimiter.tryAcquire("ops:forgot:" + staff.getStaffNo(),
                ai.neargo.shop.common.ratelimit.RateRule.of(
                        "ops.forgot", java.time.Duration.ofHours(1), 5)).allowed()) {
            log.warn("[ops] 忘记密码触发限流 staff={}", staff.getStaffNo());
            return;   // 同样静默：告诉他「你被限流了」也是一种账号存在性泄露
        }

        String token = resetTokens.issue(staff.getStaffNo());
        mailPort.send(username, "【数智邻购】运营端密码重置",
                "你好 " + staff.getRealName() + "，\n\n"
                        + "有人为你的运营端账号申请了密码重置。\n"
                        + "重置码（15 分钟内有效，只能用一次）：\n\n    " + token + "\n\n"
                        + "**如果不是你本人操作，忽略本邮件即可**，你的密码不会有任何变化。\n",
                SysNotifyLog.BIZ_OPS_RESET_PASSWORD, null);
        audit("STAFF_PASSWORD_FORGOT", staff.getStaffNo(), "已发送重置邮件", true, null, null);
    }

    @Override
    @Transactional
    public void resetPassword(String token, String newPassword) {
        if (!notBlank(newPassword) || newPassword.length() < 8) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        String staffNo = resetTokens.consume(token)
                .orElseThrow(() -> BizException.of(ErrorCode.RESET_TOKEN_INVALID));

        SysOpsStaff staff = DataScopeContext.executeWithoutScope(() ->
                staffMapper.selectOne(Wrappers.<SysOpsStaff>lambdaQuery()
                        .eq(SysOpsStaff::getStaffNo, staffNo).last("limit 1")));
        if (staff == null || !"ACTIVE".equals(staff.getStatus())) {
            // 令牌已经消费掉了，不退回 —— 停用的账号不该能靠一封旧邮件复活
            throw BizException.of(ErrorCode.RESET_TOKEN_INVALID);
        }
        staff.setPassword(passwordHasher.encode(newPassword));
        /*
         * **重置之后不再要求改密**：他刚刚就是在设新密码，
         * 再逼一次会让人以为没设成功。这与建号时的 mustChangePassword 不同 ——
         * 那个密码是别人生成的，这个是本人设的。
         */
        staff.setMustChangePassword(false);
        staffMapper.updateById(staff);
        // 忘记密码的常见动机就是「怀疑被人用了」，不踢的话拿着旧 token 的人照样在线
        tokenStore.revokeUser(staffNo);
        audit("STAFF_PASSWORD_RESET", staffNo, "经邮件重置", true, null, null);
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
        /*
         * **不再踢会话**（2026-08-13）：角色与数据域已经改成每请求现算
         * （`LiveIdentityResolver`），会话里只剩「他是谁」。改完清一次快照，
         * 同实例下一个请求就是新身份，跨实例最坏一个 TTL —— 而且不打断任何人。
         *
         * 要立刻收回权限（开错了），走运营端那个显式的「强制重新登录」按钮。
         */
        identityResolver.invalidate();
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
                a.getAuditedAt() == null ? 0L : a.getAuditedAt(),
                readQualItems(a.getQualificationItems()));
    }

    /** 申请单上的结构化资质 → VO。解析不出来给空 —— 审核页不该被脏数据整页打不开 */
    private List<ai.neargo.shop.platform.dto.OpsVOs.QualificationItemVO> readQualItems(String jsonText) {
        if (jsonText == null || jsonText.isBlank()) {
            return List.of();
        }
        try {
            List<OpsService.QualificationItem> raw = this.json.readValue(jsonText,
                    new TypeReference<List<OpsService.QualificationItem>>() { });
            return raw == null ? List.of() : raw.stream()
                    .map(it -> new ai.neargo.shop.platform.dto.OpsVOs.QualificationItemVO(
                            it.type(), it.code(), it.imageUrl(), it.expireAt(), it.issuer()))
                    .toList();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /** 结构化资质单独一个方法：与 {@link #writeJson(List)} 的 List&lt;String&gt; 重载会冲突 */
    private String writeItemsJson(List<OpsService.QualificationItem> items) {
        try {
            return json.writeValueAsString(items == null ? List.of() : items);
        } catch (Exception e) {
            return "[]";
        }
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
