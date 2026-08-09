package ai.neargo.shop.platform.impl;

import ai.neargo.shop.platform.OpsService;
import ai.neargo.shop.auth.Perms;

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

@Service
public class OpsServiceImpl implements OpsService {



    private final StaffMapper staffMapper;
    private final AuditLogMapper auditLogMapper;
    private final MerchantApplyMapper applyMapper;
    private final MerchantAdminPort merchantAdminPort;
    private final TokenStore tokenStore;
    private final ObjectMapper objectMapper;
    private final ObjectMapper json;
    private final ai.neargo.shop.platform.IndustryService industryService;
    private final ai.neargo.shop.platform.MasterDataService masterDataService;

    public OpsServiceImpl(StaffMapper staffMapper, AuditLogMapper auditLogMapper,
                          MerchantApplyMapper applyMapper, MerchantAdminPort merchantAdminPort,
                          TokenStore tokenStore, ObjectMapper json, ObjectMapper objectMapper,
                          ai.neargo.shop.platform.IndustryService industryService,
                          ai.neargo.shop.platform.MasterDataService masterDataService) {
        this.masterDataService = masterDataService;
        this.industryService = industryService;
        this.objectMapper = objectMapper;
        this.staffMapper = staffMapper;
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
        List<String> perms = Perms.of(roles);
        String token = tokenStore.issue(TokenStore.SessionData.of(
                LoginUser.operator(staff.getStaffNo(), staff.getRealName(), roles, perms)));
        return new LoginResultVO(token, toVO(staff, roles, perms));
    }

    @Override
    public StaffVO me() {
        LoginUser user = SecurityUtils.requireUser();
        SysOpsStaff staff = requireStaff(user.userNo());
        List<String> roles = readList(staff.getRoles());
        return toVO(staff, roles, Perms.of(roles));
    }

    @Override
    public List<StaffVO> staffList() {
        return DataScopeContext.executeWithoutScope(() ->
                        staffMapper.selectList(Wrappers.<SysOpsStaff>lambdaQuery()
                                .orderByAsc(SysOpsStaff::getId))).stream()
                .map(s -> {
                    List<String> roles = readList(s.getRoles());
                    return toVO(s, roles, Perms.of(roles));
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
        apply.setServiceScope(cmd.serviceScope() == null ? "COMMUNITY" : cmd.serviceScope());
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
                roles, perms, s.getStatus());
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
