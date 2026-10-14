package ai.neargo.shop.merchant.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.Masks;
import ai.neargo.shop.merchant.entity.MchEntity;
import ai.neargo.shop.merchant.entity.MchPayoutAccount;
import ai.neargo.shop.merchant.mapper.MerchantMappers;
import ai.neargo.shop.merchant.service.PayoutAccountCipher;
import ai.neargo.shop.merchant.service.PayoutAccountService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/** {@link PayoutAccountService} 实现。见接口注释：<b>账号明文只在两个瞬间存在</b>。 */
@Service
public class PayoutAccountServiceImpl implements PayoutAccountService {

    private static final Logger log = LoggerFactory.getLogger(PayoutAccountServiceImpl.class);

    private final MerchantMappers.PayoutAccountMapper accounts;
    private final MerchantMappers.MchEntityMapper entities;
    private final PayoutAccountCipher cipher;

    public PayoutAccountServiceImpl(MerchantMappers.PayoutAccountMapper accounts,
                                    MerchantMappers.MchEntityMapper entities,
                                    PayoutAccountCipher cipher) {
        this.accounts = accounts;
        this.entities = entities;
        this.cipher = cipher;
    }

    @Override
    @Transactional
    public PayoutAccountVO submit(String entityNo, SubmitCommand cmd) {
        if (cmd == null || cmd.accountNumber() == null || cmd.accountNumber().isBlank()
                || cmd.accountName() == null || cmd.accountName().isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }

        /*
         * **先拦「已有在审」，再校验户名。**
         *
         * 两条都可能命中，而它们的下一步动作不同：有单在审是「等着」，
         * 户名不符是「改写法重提」。先报户名的话，他改完再提还是会被在审那条拦下来，
         * 白改一次。
         */
        /*
         * **B 端路径一律绕过数据域。** 这张表登记了 MERCHANT 锚点（DataScopeRegistration），
         * 而商家自己的会话里没有运营的数据域上下文 —— 直查的后果是
         * SELECT 查不到、UPDATE 静默 0 行，且两者都不报错。
         */
        Long pending = DataScopeContext.executeWithoutScope(() ->
                accounts.selectCount(Wrappers.<MchPayoutAccount>lambdaQuery()
                        .eq(MchPayoutAccount::getEntityNo, entityNo)
                        .eq(MchPayoutAccount::getStatus, MchPayoutAccount.PENDING)));
        if (pending != null && pending > 0) {
            throw BizException.of(ErrorCode.PAYOUT_ACCOUNT_PENDING_EXISTS);
        }

        /*
         * **三流一致：户名必须等于营业执照主体名。**
         *
         * 这是硬校验不是提示 —— 户名对不上时钱付得出去，但这笔支出在税上站不住
         * （V23 的进项票注释写的是同一件事）。查不到主体也拒：
         * 放过去的话，一个不存在的主体会挂上一张能收钱的卡。
         */
        MchEntity entity = entities.selectOne(Wrappers.<MchEntity>lambdaQuery()
                .eq(MchEntity::getEntityNo, entityNo));
        if (entity == null || entity.getName() == null
                || !entity.getName().trim().equals(cmd.accountName().trim())) {
            throw BizException.of(ErrorCode.PAYOUT_ACCOUNT_NAME_MISMATCH);
        }

        MchPayoutAccount a = new MchPayoutAccount();
        a.setAccountNo(BizKey.next(BizKey.PAYOUT_ACCOUNT));
        a.setEntityNo(entityNo);
        a.setAccountType(cmd.accountType());
        a.setAccountName(cmd.accountName().trim());
        // 明文在这里进去，出来的只有密文与掩码 —— 原值不再被任何字段持有
        a.setAccountNumberEnc(cipher.encrypt(cmd.accountNumber().trim()));
        a.setAccountMasked(Masks.tail(cmd.accountNumber().trim()));
        a.setBankName(cmd.bankName());
        a.setBankBranch(cmd.bankBranch());
        a.setStatus(MchPayoutAccount.PENDING);
        DataScopeContext.executeWithoutScope(() -> accounts.insert(a));

        // 日志只记掩码。账号进日志与进库是同一类泄露，且日志更难清
        log.info("[payout-account] {} 提交收款账户 {}（{}）",
                entityNo, a.getAccountNo(), a.getAccountMasked());
        return toVO(a);
    }

    @Override
    public List<PayoutAccountVO> myAccounts(String entityNo) {
        // B 端路径，同 submit：绕过数据域，否则店主看自己的卡是一片空白
        return DataScopeContext.executeWithoutScope(() ->
                        accounts.selectList(Wrappers.<MchPayoutAccount>lambdaQuery()
                                .eq(MchPayoutAccount::getEntityNo, entityNo)
                                .orderByDesc(MchPayoutAccount::getId)))
                .stream().map(PayoutAccountServiceImpl::toVO).toList();
    }

    @Override
    public ai.neargo.shop.common.PageData<PayoutAccountVO> list(String status, String entityNo,
                                                                long page, long size) {
        long p = page < 1 ? 1 : page;
        long s = size < 1 || size > 100 ? 20 : size;
        var w = Wrappers.<MchPayoutAccount>lambdaQuery()
                .eq(status != null && !status.isBlank(), MchPayoutAccount::getStatus, status)
                .eq(entityNo != null && !entityNo.isBlank(), MchPayoutAccount::getEntityNo, entityNo)
                .orderByDesc(MchPayoutAccount::getId);
        var pg = accounts.selectPage(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(p, s), w);
        return ai.neargo.shop.common.PageData.of(
                pg.getRecords().stream().map(PayoutAccountServiceImpl::toVO).toList(),
                pg.getTotal(), p, s);
    }

    @Override
    @Transactional
    public PayoutAccountVO audit(String accountNo, boolean approved, String remark) {
        // 驳回不写原因等于让商家猜。与入驻驳回、提现驳回同一条规矩
        if (!approved && (remark == null || remark.isBlank())) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        MchPayoutAccount a = accounts.selectOne(Wrappers.<MchPayoutAccount>lambdaQuery()
                .eq(MchPayoutAccount::getAccountNo, accountNo));
        if (a == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        // 只有待审的能审。已生效的再审一次会把旧账户二次顶替，且审计上看不出是重复动作
        if (!MchPayoutAccount.PENDING.equals(a.getStatus())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }

        if (approved) {
            /*
             * **先停旧的，再启新的。**
             *
             * 顺序反过来的话，中间那一瞬间同主体有两条 ACTIVE ——
             * 而付款任务如果正好在这一刻读，取到哪一条取决于查询顺序。
             */
            /*
             * 停旧账户这一步**绕过数据域**：它是数据一致性逻辑，不是人的查询。
             * 受数据域约束的话，运营的可见范围会决定「有没有把旧卡停掉」——
             * 而漏停的后果是同主体两条 ACTIVE，付款时取到哪张取决于查询顺序。
             * 能审到这张单本身已经过了数据域那道闸（上面的 selectOne 没有绕过）。
             */
            List<MchPayoutAccount> olds = DataScopeContext.executeWithoutScope(() ->
                    accounts.selectList(Wrappers.<MchPayoutAccount>lambdaQuery()
                            .eq(MchPayoutAccount::getEntityNo, a.getEntityNo())
                            .eq(MchPayoutAccount::getStatus, MchPayoutAccount.ACTIVE)));
            for (MchPayoutAccount old : olds) {
                old.setStatus(MchPayoutAccount.DISABLED);
                DataScopeContext.executeWithoutScope(() -> accounts.updateById(old));
            }
            a.setStatus(MchPayoutAccount.ACTIVE);
        } else {
            a.setStatus(MchPayoutAccount.REJECTED);
        }
        a.setAuditRemark(remark);
        a.setAuditedAt(System.currentTimeMillis());
        accounts.updateById(a);

        log.info("[payout-account] {} 审核 {} → {}", accountNo,
                approved ? "通过" : "驳回", a.getStatus());
        return toVO(a);
    }

    @Override
    public Optional<PayoutAccountVO> activeAccount(String entityNo) {
        /*
         * **付款任务是系统行为，不是某个人的查询**，所以也绕过数据域 ——
         * 否则这一期该付谁取决于任务跑在谁的上下文里，而那个答案不该由它决定。
         */
        return Optional.ofNullable(DataScopeContext.executeWithoutScope(() ->
                        accounts.selectOne(Wrappers.<MchPayoutAccount>lambdaQuery()
                                .eq(MchPayoutAccount::getEntityNo, entityNo)
                                .eq(MchPayoutAccount::getStatus, MchPayoutAccount.ACTIVE)
                                .last("LIMIT 1"))))
                .map(PayoutAccountServiceImpl::toVO);
    }

    @Override
    public String decryptAccountNumber(String accountNo) {
        MchPayoutAccount a = accounts.selectOne(Wrappers.<MchPayoutAccount>lambdaQuery()
                .eq(MchPayoutAccount::getAccountNo, accountNo));
        if (a == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        /*
         * **只有生效中的账户能解密。**
         *
         * 待审/被驳/已停用的账号没有任何一个合法用途需要明文 ——
         * 而导出付款清单是唯一的调用方，它本来就只该导生效账户。
         * 把这条闸放在这里而不是调用方：将来多一个调用方时不会漏掉。
         */
        if (!MchPayoutAccount.ACTIVE.equals(a.getStatus())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        return cipher.decrypt(a.getAccountNumberEnc());
    }

    private static PayoutAccountVO toVO(MchPayoutAccount a) {
        return new PayoutAccountVO(a.getAccountNo(), a.getEntityNo(), a.getAccountType(),
                a.getAccountName(), a.getAccountMasked(), a.getBankName(), a.getBankBranch(),
                a.getStatus(), a.getAuditRemark(), a.getAuditedAt());
    }
}
