package ai.neargo.shop.elec.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.elec.config.ConditionalOnElec;
import ai.neargo.shop.elec.config.ElecProperties;
import ai.neargo.shop.elec.dto.SupplierDtos.RegisterReq;
import ai.neargo.shop.elec.dto.SupplierDtos.RenewResult;
import ai.neargo.shop.elec.dto.SupplierDtos.StockView;
import ai.neargo.shop.elec.dto.SupplierDtos.SupplierView;
import ai.neargo.shop.elec.entity.ElcStock;
import ai.neargo.shop.elec.entity.ElcStockBatch;
import ai.neargo.shop.elec.entity.ElcSupplier;
import ai.neargo.shop.elec.entity.ElcSupplierMember;
import ai.neargo.shop.elec.mapper.ElecMappers.StockBatchMapper;
import ai.neargo.shop.elec.mapper.ElecMappers.StockMapper;
import ai.neargo.shop.elec.mapper.ElecMappers.SupplierMapper;
import ai.neargo.shop.elec.mapper.ElecMappers.SupplierMemberMapper;
import ai.neargo.shop.elec.service.ElecMarketService;
import ai.neargo.shop.elec.service.ElecSupplierService;
import ai.neargo.shop.elec.support.ElecKeys;
import ai.neargo.shop.elec.support.Mpn;
import ai.neargo.shop.elec.gateway.ElecAlerts;
import ai.neargo.shop.elec.gateway.ElecAccounts;
import ai.neargo.shop.elec.gateway.ElecSupplierNotifier;
import ai.neargo.shop.elec.mapper.ElecMappers;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 供应商：入驻即可用（第一步不审核，平台在企业微信里事后看），之后定期上传库存。
 */
@ConditionalOnElec
@Service
public class ElecSupplierServiceImpl implements ElecSupplierService {

    private static final Logger log = LoggerFactory.getLogger(ElecSupplierServiceImpl.class);

    public static final Set<String> KINDS = Set.of("AGENT", "TRADER", "FACTORY", "OTHER");

    /** 「快到期」的提前量：工作台上提醒他续期或重传。运营端的「7 天内到期」用同一个数 */
    static final int EXPIRING_DAYS = 7;

    /** 到期提醒：提前几天说。比工作台的「7 天内到期」近 —— 站内信要说的是「真的快了」 */
    static final int REMIND_AHEAD_DAYS = 3;

    /** 同一家最多几天提醒一次。他不续期，每天一条就成了骚扰 */
    static final int REMIND_EVERY_DAYS = 6;

    private static final Pattern PHONE = Pattern.compile("^1\\d{10}$");

    private final ElecSupplierAccess access;
    private final SupplierMapper supplierMapper;
    private final SupplierMemberMapper memberMapper;
    private final StockMapper stockMapper;
    private final StockBatchMapper batchMapper;
    private final ElecMarketService market;
    private final ElecAccounts identity;
    private final ElecAlerts alerts;
    private final ElecProperties props;
    private final TransactionTemplate tx;
    private final ElecStockViews views;
    private final ElecSupplierNotifier notifier;

    public ElecSupplierServiceImpl(ElecSupplierAccess access, SupplierMapper supplierMapper,
                               SupplierMemberMapper memberMapper, StockMapper stockMapper,
                               StockBatchMapper batchMapper, ElecMarketService market, ElecAccounts identity,
                               ElecAlerts alerts, ElecProperties props, ElecStockViews views,
                               ElecSupplierNotifier notifier,
                               @Qualifier("elecTransactionManager") PlatformTransactionManager tm) {
        this.access = access;
        this.supplierMapper = supplierMapper;
        this.memberMapper = memberMapper;
        this.stockMapper = stockMapper;
        this.batchMapper = batchMapper;
        this.market = market;
        this.identity = identity;
        this.alerts = alerts;
        this.props = props;
        this.tx = new TransactionTemplate(tm);
        this.views = views;
        this.notifier = notifier;
    }

    @Override
    public SupplierView mine(String userNo) {
        ElcSupplier s = access.of(userNo);
        return s == null ? null : view(s);
    }

    /**
     * 入驻。<b>事务提交之后才通知</b>：通知失败不能把入驻回滚掉，通知也不能先于数据落库
     * （群里看到了、库里却没有，运营会以为系统坏了）。
     */
    @Override
    public SupplierView register(String userNo, RegisterReq req) {
        // 平台要能打给他：没绑手机号先去绑（端上会先弹手机号闸，这里是兜底）
        String boundPhone = identity.phone(userNo).orElseThrow(() -> BizException.of(ErrorCode.ELEC_PHONE_REQUIRED));
        String company = trimmed(req.companyName(), 128);
        if (company != null && company.length() < 2) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        String contact = trimmed(req.contactName(), 32);
        String phone = trimmed(req.contactPhone(), 32);
        if (phone == null) {
            phone = boundPhone;
        } else if (!PHONE.matcher(phone).matches()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        String kind = oneOf(req.kind(), KINDS, "TRADER");
        String contactPhone = phone;

        ElcSupplier created = tx.execute(st -> {
            if (access.of(userNo) != null) {
                throw BizException.of(ErrorCode.ELEC_SUPPLIER_EXISTS);
            }
            ElcSupplier s = new ElcSupplier();
            s.setSupplierNo(ElecKeys.next(ElecKeys.SUPPLIER));
            s.setCompanyName(company);
            s.setKind(kind);
            s.setCity(trimmed(req.city(), 32));
            s.setContactName(contact);
            s.setContactPhone(contactPhone);
            s.setStatus(ElcSupplier.STATUS_ACTIVE);
            s.setCreatedBy(userNo);
            s.setUpdatedBy(userNo);
            insertWithMask(s);
            ElcSupplierMember m = new ElcSupplierMember();
            m.setSupplierNo(s.getSupplierNo());
            m.setAccountRef(userNo);
            m.setRole(ElcSupplierMember.ROLE_OWNER);
            m.setStatus(ElcSupplierMember.STATUS_ACTIVE);
            m.setCreatedBy(userNo);
            m.setUpdatedBy(userNo);
            try {
                memberMapper.insert(m);
            } catch (DuplicateKeyException e) {
                // 同一个人连点两次：第二次撞 account_ref 唯一键
                throw BizException.of(ErrorCode.ELEC_SUPPLIER_EXISTS);
            }
            return s;
        });

        boolean sent = alerts.newSupplier(new ElecAlerts.SupplierAlert(created.getSupplierNo(),
                created.getCompanyName(), created.getKind(), created.getCity(), created.getContactName(),
                created.getContactPhone()));
        if (sent) {
            created.setNotifiedAt(LocalDateTime.now());
            supplierMapper.updateById(created);
        } else {
            log.warn("元器件供应商入驻通知没送到企业微信 supplierNo={}（elc_supplier.notified_at 为空的就是这些）",
                    created.getSupplierNo());
        }
        return view(created);
    }

    @Override
    @Transactional(transactionManager = "elecTransactionManager")
    public SupplierView update(String userNo, RegisterReq req) {
        ElcSupplier s = access.requireActive(userNo);
        applyUpdate(s, req, userNo);
        supplierMapper.updateById(s);
        return view(s);
    }

    /**
     * 把「补资料」的请求套到供应商上：空字段 = 不改；给了就校验。
     * 供应商自己改与运营代改<b>同一套校验</b> —— 运营端放宽了，就会出现供应商自己改不回去的数据。
     */
    static void applyUpdate(ElcSupplier s, RegisterReq req, String actor) {
        String company = trimmed(req.companyName(), 128);
        if (company != null) {
            if (company.length() < 2) {
                throw BizException.of(ErrorCode.BAD_REQUEST);
            }
            s.setCompanyName(company);
        }
        String phone = trimmed(req.contactPhone(), 32);
        if (phone != null) {
            if (!PHONE.matcher(phone).matches()) {
                throw BizException.of(ErrorCode.BAD_REQUEST);
            }
            s.setContactPhone(phone);
        }
        if (req.kind() != null && KINDS.contains(req.kind())) {
            s.setKind(req.kind());
        }
        String city = trimmed(req.city(), 32);
        if (city != null) {
            s.setCity(city);
        }
        String contact = trimmed(req.contactName(), 32);
        if (contact != null) {
            s.setContactName(contact);
        }
        s.setUpdatedBy(actor);
    }

    /** 匿名代号撞了就换一个再插（4 位 32 进制约一百万个，撞三次基本不可能） */
    private void insertWithMask(ElcSupplier s) {
        for (int i = 0; ; i++) {
            s.setMaskCode(ElecKeys.maskCode());
            try {
                supplierMapper.insert(s);
                return;
            } catch (DuplicateKeyException e) {
                if (i >= 3) {
                    throw e;
                }
            }
        }
    }

    @Override
    public List<StockView> stocks(String userNo, String keyword, String filter, int page, int size) {
        ElcSupplier s = access.requireActive(userNo);
        LocalDate today = LocalDate.now();
        LambdaQueryWrapper<ElcStock> q = stockQuery(s.getSupplierNo(), keyword, filter, today)
                .orderByAsc(ElcStock::getValidUntil).orderByAsc(ElcStock::getMpnNorm);
        return stockMapper.selectList(page(q, page, size)).stream().map(r -> views.view(r, today)).toList();
    }

    @Override
    @Transactional(transactionManager = "elecTransactionManager")
    public RenewResult renew(String userNo) {
        ElcSupplier s = access.requireActive(userNo);
        LocalDate until = LocalDate.now().plusDays(props.getStockTtlDays());
        List<ElcStock> rows = stockMapper.selectList(Wrappers.<ElcStock>lambdaQuery()
                .eq(ElcStock::getSupplierNo, s.getSupplierNo())
                .eq(ElcStock::getStatus, ElcStock.STATUS_ON));
        if (rows.isEmpty()) {
            return new RenewResult(0, until);
        }
        ElcStock patch = new ElcStock();
        patch.setValidUntil(until);
        patch.setConfirmedAt(LocalDateTime.now());
        patch.setUpdatedBy(userNo);
        int n = stockMapper.update(patch, Wrappers.<ElcStock>lambdaUpdate()
                .eq(ElcStock::getSupplierNo, s.getSupplierNo())
                .eq(ElcStock::getStatus, ElcStock.STATUS_ON));
        market.refresh(rows.stream().map(ElcStock::getPartNo).toList());
        return new RenewResult(n, until);
    }

    private SupplierView view(ElcSupplier s) {
        LocalDate today = LocalDate.now();
        long on = stockMapper.selectCount(Wrappers.<ElcStock>lambdaQuery()
                .eq(ElcStock::getSupplierNo, s.getSupplierNo())
                .eq(ElcStock::getStatus, ElcStock.STATUS_ON)
                .ge(ElcStock::getValidUntil, today));
        long expiring = stockMapper.selectCount(Wrappers.<ElcStock>lambdaQuery()
                .eq(ElcStock::getSupplierNo, s.getSupplierNo())
                .eq(ElcStock::getStatus, ElcStock.STATUS_ON)
                .ge(ElcStock::getValidUntil, today)
                .le(ElcStock::getValidUntil, today.plusDays(EXPIRING_DAYS)));
        ElcStockBatch last = batchMapper.selectOne(Wrappers.<ElcStockBatch>lambdaQuery()
                .eq(ElcStockBatch::getSupplierNo, s.getSupplierNo())
                .eq(ElcStockBatch::getStatus, ElcStockBatch.STATUS_APPLIED)
                .orderByDesc(ElcStockBatch::getId).last("LIMIT 1"));
        return new SupplierView(s.getSupplierNo(), s.getCompanyName(), s.getKind(), s.getCity(),
                s.getContactName(), s.getContactPhone(), s.getMaskCode(), s.getStatus(),
                (int) on, (int) expiring, last == null ? null : last.getAppliedAt(), props.getStockTtlDays());
    }

    @Override
    public int remindExpiring() {
        LocalDate today = LocalDate.now();
        int n = 0;
        for (ElecMappers.ExpiringRow r : stockMapper.expiringBySupplier(today, today.plusDays(REMIND_AHEAD_DAYS))) {
            /*
             * 先抢再发：条件更新到 1 行才算这一周轮到它。单实例时它只是去重；
             * 将来 elec-svc 扩成多实例，同一家同一周期也只有一个实例抢得到 —— 不靠分布式锁。
             */
            /*
             * **截到整秒**：列是 DATETIME（秒精度），带纳秒写进去会被截掉（MySQL 默认还是四舍五入），
             * 下面「没送到就还回去」那句按 = now 比，永远对不上 —— 占位还不回去，没送到的要等一周。
             */
            LocalDateTime now = LocalDateTime.now().withNano(0);
            int won = supplierMapper.update(null, Wrappers.<ElcSupplier>lambdaUpdate()
                    .eq(ElcSupplier::getSupplierNo, r.getSupplierNo())
                    .and(w -> w.isNull(ElcSupplier::getExpiryRemindedAt)
                            .or().lt(ElcSupplier::getExpiryRemindedAt, now.minusDays(REMIND_EVERY_DAYS)))
                    .set(ElcSupplier::getExpiryRemindedAt, now));
            if (won == 0) {
                continue;
            }
            String account = access.ownerAccount(r.getSupplierNo());
            if (account == null) {
                continue;
            }
            boolean ok;
            try {
                ok = notifier.stockExpiring(account, r.getSupplierNo(), r.getRowsCnt().intValue(), r.getFirstDate());
            } catch (RuntimeException e) {
                ok = false;
            }
            if (ok) {
                n++;
            } else {
                // 没送到就把占位还回去，明天再试 —— 否则要等一周
                supplierMapper.update(null, Wrappers.<ElcSupplier>lambdaUpdate()
                        .eq(ElcSupplier::getSupplierNo, r.getSupplierNo())
                        .eq(ElcSupplier::getExpiryRemindedAt, now)
                        .set(ElcSupplier::getExpiryRemindedAt, null));
                log.warn("库存到期提醒没送到供应商 supplierNo={}，明天再试", r.getSupplierNo());
            }
        }
        return n;
    }

    /**
     * 在售库存的筛选：按料号前缀、到期状态。供应商看自己的、运营看某家的或全部的，同一套条件。
     *
     * @param supplierNo 为空 = 不限供应商（运营端的库存行查询）
     * @param filter     ALL / EXPIRING（7 天内到期）/ EXPIRED；其余当 ALL
     */
    static LambdaQueryWrapper<ElcStock> stockQuery(String supplierNo, String keyword, String filter,
                                                   LocalDate today) {
        LambdaQueryWrapper<ElcStock> q = Wrappers.<ElcStock>lambdaQuery()
                .eq(ElcStock::getStatus, ElcStock.STATUS_ON);
        if (supplierNo != null) {
            q.eq(ElcStock::getSupplierNo, supplierNo);
        }
        String norm = Mpn.norm(keyword);
        if (!norm.isEmpty()) {
            q.likeRight(ElcStock::getMpnNorm, norm);
        }
        if ("EXPIRING".equals(filter)) {
            q.ge(ElcStock::getValidUntil, today).le(ElcStock::getValidUntil, today.plusDays(EXPIRING_DAYS));
        } else if ("EXPIRED".equals(filter)) {
            q.lt(ElcStock::getValidUntil, today);
        }
        return q;
    }

    /** 不用分页插件（本域工厂没装它）：offset/limit 手写。每页最多 100 */
    static <T> LambdaQueryWrapper<T> page(LambdaQueryWrapper<T> q, int page, int size) {
        int p = Math.max(1, page);
        int n = Math.min(100, Math.max(1, size));
        return q.last("LIMIT " + n + " OFFSET " + (long) (p - 1) * n);
    }

    /**
     * 取值在集合里就用它，否则用默认值。<b>不能直接 {@code Set.of(...).contains(v)}</b>：
     * 不可变集合对 null 抛 NPE，而这些字段端上都可以不传 —— 不传的老调用方会直接 500。
     */
    static String oneOf(String v, Set<String> allowed, String dflt) {
        return v != null && allowed.contains(v) ? v : dflt;
    }

    static String trimmed(String v, int max) {
        if (v == null) {
            return null;
        }
        String s = v.trim();
        if (s.isEmpty()) {
            return null;
        }
        return s.length() > max ? s.substring(0, max) : s;
    }
}
