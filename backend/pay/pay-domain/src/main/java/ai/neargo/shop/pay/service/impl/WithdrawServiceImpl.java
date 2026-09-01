package ai.neargo.shop.pay.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.pay.dto.FinanceVOs.TaxRuleVO;
import ai.neargo.shop.pay.dto.FinanceVOs.WithdrawVO;
import ai.neargo.shop.pay.entity.StlWithdraw;
import ai.neargo.shop.pay.mapper.SettleMappers.WithdrawMapper;
import ai.neargo.shop.pay.service.WithdrawService;
import ai.neargo.shop.pay.setting.PaySettingService;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** {@link WithdrawService} 实现。见接口注释：**只审批留痕，不打款**。 */
@Service
public class WithdrawServiceImpl implements WithdrawService {

    /**
     * 个税规则的参数键与缺省值。
     *
     * <p>落 {@code sys_setting} 而不是建表：它是<b>一组参数</b>不是一批记录，
     * 与极速退阈值、平台开票抬头同类。
     *
     * <p>缺省 100 元起征、20% 代扣，是「劳务报酬」的常见档 ——
     * <b>不是税务口径的结论</b>，运营必须按实际情况改。缺省为 0 更糟：
     * 那会让「还没配」看起来像「不用扣」。
     */
    private static final String TAX_RULE_KEY = "finance.tax-rule";
    private static final String TAX_RULE_DEFAULT = "{\"threshold\":10000,\"rate\":2000}";

    /** 税率硬上限（万分比）。超过它一定是配置错误，而它会扣光每一笔提现。 */
    public static final long MAX_RATE_BP = 4500L;

    private final WithdrawMapper withdrawMapper;
    private final MerchantQueryPort merchantPort;
    /** 支付域自己的设置（2026-09-01 从 sys_setting 搬过来，见 V285） */
    private final PaySettingService paySettings;
    private final ObjectMapper json;

    public WithdrawServiceImpl(WithdrawMapper withdrawMapper, MerchantQueryPort merchantPort,
                               PaySettingService paySettings, ObjectMapper json) {
        this.withdrawMapper = withdrawMapper;
        this.merchantPort = merchantPort;
        this.paySettings = paySettings;
        this.json = json;
    }

    @Override
    public PageData<WithdrawVO> list(String status, String keyword, long page, long size) {
        long p = Math.max(page, 1);
        long s = Math.max(size, 1);
        var w = Wrappers.<StlWithdraw>lambdaQuery()
                .eq(status != null && !status.isBlank(), StlWithdraw::getStatus, status);
        if (keyword != null && !keyword.isBlank()) {
            String k = keyword.trim();
            w.and(q -> q.like(StlWithdraw::getWithdrawNo, k)
                    .or().like(StlWithdraw::getEntityNo, k)
                    .or().like(StlWithdraw::getMerchantName, k));
        }
        // 先到先审：手工处理时顺序就是公平性
        w.orderByAsc(StlWithdraw::getId);
        /*
         * **不绕过数据域**：这是运营端的全量提现队列，正是数据域要起作用的地方。
         * 绕过的话，配了商家域的财务打开它会看到全平台的申请 —— 不报错，
         * 而下一步就是审批打款。stl_withdraw 已登记 MERCHANT → entity_no。
         */
        var pg = withdrawMapper.selectPage(new Page<>(p, s), w);
        return PageData.of(pg.getRecords().stream().map(WithdrawServiceImpl::toVO).toList(),
                pg.getTotal(), p, s);
    }

    @Override
    @Transactional("payTxManager")
    public WithdrawVO decide(String withdrawNo, boolean pass, String remark, String operatorNo) {
        /*
         * 审批也走数据域：**看不见的单不该批得动**。域外的单在这里查不到，
         * 于是落到下面的 NOT_FOUND —— 与「列表里看不到」是同一个答案，
         * 而不是「列表看不到但知道单号就能批」。
         */
        StlWithdraw w = withdrawMapper.selectOne(Wrappers.<StlWithdraw>lambdaQuery()
                .eq(StlWithdraw::getWithdrawNo, withdrawNo).last("limit 1"));
        if (w == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        /*
         * 状态机先判：已驳回 / 已打款的单被二次审批，后果是同一笔钱批两次。
         * 单独一个错误码而不是 CONFLICT —— 财务看到「操作冲突」不知道该做什么，
         * 看到「这张单已经审过了」就知道该刷新列表。
         */
        if (!w.decidable()) {
            throw BizException.of(ErrorCode.WITHDRAW_STATE_ILLEGAL);
        }
        String note = remark == null ? null : remark.trim();

        if (!pass) {
            // 驳回原因原样回商家 B 端，不写等于让人猜
            if (note == null || note.isEmpty()) {
                throw BizException.of(ErrorCode.REASON_REQUIRED);
            }
            w.setStatus(StlWithdraw.REJECTED);
        } else {
            MerchantQueryPort.MerchantBrief m = merchantPort.find(w.getEntityNo())
                    .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));
            // 没有收款账户，批了钱也打不出去（ADR-002：分账前必须先报备接收方）
            if (!m.canReceive()) {
                throw BizException.of(ErrorCode.SPLIT_RECEIVER_NOT_READY);
            }
            /*
             * 封禁的商家不放行。解封是另一条链路上的决定（P-11.1.4），
             * 在这里绕过去等于让处置形同虚设 —— 而处置期间恰恰是最该冻住资金的时候。
             */
            if (!m.canSell()) {
                throw BizException.of(ErrorCode.WITHDRAW_MERCHANT_BANNED);
            }
            long amount = nz(w.getAmountMinor());
            // 用申请那一刻的余额快照，不是实时值 —— 实时值会因期间的新订单而漂移
            if (amount > nz(w.getAvailableBalanceMinor())) {
                throw BizException.of(ErrorCode.WITHDRAW_OVER_BALANCE);
            }
            if (amount < StlWithdraw.MIN_AMOUNT_MINOR) {
                throw BizException.of(ErrorCode.WITHDRAW_BELOW_MIN);
            }
            // 大额是最容易被冒用的口子：没有说明的「批了五万」事后解释不了
            if (amount >= StlWithdraw.REVIEW_THRESHOLD_MINOR && (note == null || note.isEmpty())) {
                throw BizException.of(ErrorCode.WITHDRAW_REVIEW_NOTE_REQUIRED);
            }
            // ⚠️ APPROVED 不是 PAID：打款结果来自渠道回执，见类注释
            w.setStatus(StlWithdraw.APPROVED);
        }

        w.setRemark(note == null || note.isEmpty() ? null : note);
        w.setDecidedAt(System.currentTimeMillis());
        w.setDecidedBy(operatorNo);
        DataScopeContext.executeWithoutScope(() -> withdrawMapper.updateById(w));
        return toVO(w);
    }

    @Override
    public TaxRuleVO taxRule() {
        return json.readValue(paySettings.get(TAX_RULE_KEY, TAX_RULE_DEFAULT), TaxRuleVO.class);
    }

    @Override
    public TaxRuleVO saveTaxRule(long threshold, long rate, String operatorNo) {
        if (threshold < 0 || rate < 0) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        if (rate > MAX_RATE_BP) {
            throw BizException.of(ErrorCode.TAX_RATE_TOO_HIGH);
        }
        /*
         * 留痕写进值里。SettingPort.get 只返回值本身，不返回它是谁什么时候写的 ——
         * 不放进 JSON 就取不回来，而**改税率必须能追到是谁改的**。
         */
        TaxRuleVO saved = new TaxRuleVO(threshold, rate,
                java.time.Instant.now().toString(), operatorNo);
        paySettings.put(TAX_RULE_KEY, json.writeValueAsString(saved), operatorNo);
        return saved;
    }

    private static WithdrawVO toVO(StlWithdraw w) {
        return new WithdrawVO(w.getWithdrawNo(), w.getEntityNo(), w.getMerchantName(),
                nz(w.getAmountMinor()), nz(w.getAvailableBalanceMinor()),
                w.getBankAccountMasked(), w.getStatus(),
                iso(w.getAppliedAt()), iso(w.getDecidedAt()), w.getDecidedBy(), w.getRemark());
    }

    /** 见 {@code FinanceVOs} 的类注释：这几个类型的时间字段在契约里是 ISO 串。 */
    static String iso(Long millis) {
        return millis == null || millis <= 0 ? null
                : java.time.Instant.ofEpochMilli(millis).toString();
    }

    static long nz(Long v) {
        return v == null ? 0L : v;
    }
}
