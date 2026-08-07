package ai.neargo.shop.platform.impl;

import ai.neargo.shop.platform.OpsService;
import ai.neargo.shop.platform.Perms;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.spi.user.MerchantAdminPort;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.platform.dto.OpsVOs.AuditLogVO;
import ai.neargo.shop.platform.dto.OpsVOs.LoginResultVO;
import ai.neargo.shop.platform.dto.OpsVOs.MerchantApplyVO;
import ai.neargo.shop.platform.dto.OpsVOs.StaffVO;
import ai.neargo.shop.platform.entity.SysAuditLog;
import ai.neargo.shop.platform.entity.SysStaff;
import ai.neargo.shop.platform.entity.UsrMerchantApply;
import ai.neargo.shop.platform.mapper.PlatformMappers.AuditLogMapper;
import ai.neargo.shop.platform.mapper.PlatformMappers.MerchantApplyMapper;
import ai.neargo.shop.platform.mapper.PlatformMappers.StaffMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class OpsServiceImpl implements OpsService {

    private final StaffMapper staffMapper;
    private final AuditLogMapper auditLogMapper;
    private final MerchantApplyMapper applyMapper;
    private final MerchantAdminPort merchantAdminPort;
    private final TokenStore tokenStore;
    private final ObjectMapper json;

    public OpsServiceImpl(StaffMapper staffMapper, AuditLogMapper auditLogMapper,
                          MerchantApplyMapper applyMapper, MerchantAdminPort merchantAdminPort,
                          TokenStore tokenStore, ObjectMapper json) {
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
        SysStaff staff = DataScopeContext.executeWithoutScope(() ->
                staffMapper.selectOne(Wrappers.<SysStaff>lambdaQuery()
                        .eq(SysStaff::getUsername, username).last("limit 1")));

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
        SysStaff staff = requireStaff(user.userNo());
        List<String> roles = readList(staff.getRoles());
        return toVO(staff, roles, Perms.of(roles));
    }

    @Override
    public List<StaffVO> staffList() {
        return DataScopeContext.executeWithoutScope(() ->
                        staffMapper.selectList(Wrappers.<SysStaff>lambdaQuery()
                                .orderByAsc(SysStaff::getId))).stream()
                .map(s -> {
                    List<String> roles = readList(s.getRoles());
                    return toVO(s, roles, Perms.of(roles));
                }).toList();
    }

    // ---------------------------------------------------------------- 入驻审核

    @Override
    public List<MerchantApplyVO> applyQueue() {
        return DataScopeContext.executeWithoutScope(() ->
                        applyMapper.selectList(Wrappers.<UsrMerchantApply>lambdaQuery()
                                .eq(UsrMerchantApply::getStatus, UsrMerchantApply.PENDING)
                                .orderByAsc(UsrMerchantApply::getId))).stream()
                .map(this::toVO).toList();
    }

    @Override
    @Transactional
    public void auditApply(String applyNo, boolean approved, String reason) {
        if (!approved && (reason == null || reason.isBlank())) {
            // 不写理由的驳回等于让对方猜。申请人拿不到理由就只能反复重提
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        UsrMerchantApply apply = DataScopeContext.executeWithoutScope(() ->
                applyMapper.selectOne(Wrappers.<UsrMerchantApply>lambdaQuery()
                        .eq(UsrMerchantApply::getApplyNo, applyNo).last("limit 1")));
        if (apply == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        if (!UsrMerchantApply.PENDING.equals(apply.getStatus())) {
            throw BizException.of(ErrorCode.ORDER_STATE_ILLEGAL);
        }

        LoginUser operator = SecurityUtils.requireUser();
        if (approved) {
            // 审核通过才创建商家主体：驳回的申请不该在库里留下一个「僵尸商家」
            String merchantNo = merchantAdminPort.activate(
                    apply.getUserNo(), apply.getName(), apply.getMerchantType());
            apply.setMerchantNo(merchantNo);
            apply.setStatus(UsrMerchantApply.APPROVED);
        } else {
            apply.setStatus(UsrMerchantApply.REJECTED);
            apply.setRejectReason(reason);
        }
        apply.setAuditedBy(operator.userNo());
        apply.setAuditedAt(System.currentTimeMillis());
        DataScopeContext.executeWithoutScope(() -> applyMapper.updateById(apply));

        // 审核是能改变别人生意的操作 —— 出问题时必须能回答「谁批的」
        audit("MERCHANT_AUDIT", applyNo,
                (approved ? "通过" : "驳回：" + reason) + "；商家=" + apply.getName());
    }

    @Override
    @Transactional
    public String createApply(String userNo, String name, String type,
                              String contactPhone, List<String> qualifications) {
        UsrMerchantApply apply = new UsrMerchantApply();
        apply.setApplyNo(BizKey.next(BizKey.MERCHANT_APPLY));
        apply.setUserNo(userNo);
        apply.setName(name);
        apply.setMerchantType(type == null ? "PERSONAL" : type);
        apply.setContactPhone(contactPhone);
        apply.setQualifications(writeJson(qualifications));
        apply.setStatus(UsrMerchantApply.PENDING);
        DataScopeContext.executeWithoutScope(() -> applyMapper.insert(apply));
        return apply.getApplyNo();
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

    private SysStaff requireStaff(String staffNo) {
        SysStaff staff = DataScopeContext.executeWithoutScope(() ->
                staffMapper.selectOne(Wrappers.<SysStaff>lambdaQuery()
                        .eq(SysStaff::getStaffNo, staffNo).last("limit 1")));
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

    private StaffVO toVO(SysStaff s, List<String> roles, List<String> perms) {
        return new StaffVO(s.getStaffNo(), s.getUsername(), s.getRealName(),
                roles, perms, s.getStatus());
    }

    private MerchantApplyVO toVO(UsrMerchantApply a) {
        return new MerchantApplyVO(a.getApplyNo(), a.getMerchantNo(), a.getName(),
                a.getMerchantType(), a.getContactPhone(), readList(a.getQualifications()),
                a.getStatus(), a.getRejectReason(),
                a.getCreatedAt() == null ? 0L
                        : a.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
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
}
