package ai.neargo.shop.merchant.funds.mapper;

import ai.neargo.shop.merchant.funds.entity.MchAdmissionPolicy;
import ai.neargo.shop.merchant.funds.entity.MchDebt;
import ai.neargo.shop.merchant.funds.entity.MchDebtTxn;
import ai.neargo.shop.merchant.funds.entity.MchDeposit;
import ai.neargo.shop.merchant.funds.entity.MchDepositTxn;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * 准入规则、保证金、欠款的 Mapper。
 *
 * <p>新的库表设计把保证金与欠款并入账务结算库的凭证，准入规则并入支付商户库，都不在平台基础库。
 * 所以从商户域的 Mapper 集合里拆出来；只在 {@code merchant.funds} 包内使用，
 * 商户核心经 {@code AdmissionService}、{@code DebtService}、准入端口访问。
 */
public final class FundsMappers {

    private FundsMappers() {
    }

    /** 准入规则：开通某类经营要交多少保证金。 */
    public interface AdmissionPolicyMapper extends BaseMapper<MchAdmissionPolicy> {
    }

    public interface DebtMapper extends BaseMapper<MchDebt> {
    }

    public interface DebtTxnMapper extends BaseMapper<MchDebtTxn> {
    }

    public interface DepositMapper extends BaseMapper<MchDeposit> {
    }

    public interface DepositTxnMapper extends BaseMapper<MchDepositTxn> {
    }
}
