package ai.neargo.shop.user.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.Masks;
import ai.neargo.shop.spi.pay.PayApplymentGateway;
import ai.neargo.shop.spi.platform.MasterDataPort;
import ai.neargo.shop.user.dto.PaymentApplymentVO;
import ai.neargo.shop.user.mapper.UserMappers.MchEntityMapper;
import ai.neargo.shop.user.mapper.UserMappers.MchPaymentMapper;
import ai.neargo.shop.user.merchant.entity.MchEntity;
import ai.neargo.shop.user.merchant.entity.MchPaymentMerchant;
import ai.neargo.shop.user.service.MerchantPaymentService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 收款进件的推进链路：占位 → 补资料 → 提交 → 回执 → 能收钱。
 *
 * <p>此前只有「占位」这一步：主体激活时建一条 APPLYING 的记录就结束了，
 * 没有任何代码能把它推到 ACTIVE。表现是整条链路能跑到下单，
 * 而**收款方是个占位记录** —— 真钱进来分不出去。
 */
@Service
public class MerchantPaymentServiceImpl implements MerchantPaymentService {

    private final MchPaymentMapper paymentMapper;
    private final MchEntityMapper merchantMapper;
    private final MasterDataPort masterDataPort;
    /** 每通道一个实现；开发期是 STUB 一个顶俩 */
    private final Map<String, PayApplymentGateway> gateways;

    public MerchantPaymentServiceImpl(MchPaymentMapper paymentMapper, MchEntityMapper merchantMapper,
                                      MasterDataPort masterDataPort,
                                      List<PayApplymentGateway> gatewayList) {
        this.paymentMapper = paymentMapper;
        this.merchantMapper = merchantMapper;
        this.masterDataPort = masterDataPort;
        this.gateways = gatewayList.stream()
                .collect(Collectors.toMap(PayApplymentGateway::payChannel, Function.identity()));
    }

    @Override
    public List<PaymentApplymentVO> list(String merchantNo) {
        return rows(merchantNo).stream().map(this::toVO).toList();
    }

    @Override
    @Transactional
    public PaymentApplymentVO submit(String merchantNo, SubmitCommand cmd) {
        MchPaymentMerchant row = require(merchantNo, cmd.payChannel());

        /*
         * 已经开好的户不许重复提交。
         *
         * 通道侧重复进件会得到一个**新的二级商户号**，而历史订单的分账仍指向旧号 ——
         * 那是对不上账的开始，且发现时已经过了好几个账期。
         */
        if (MchPaymentMerchant.ACTIVE.equals(row.getApplyStatus())) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }

        MchEntity entity = DataScopeContext.executeWithoutScope(() ->
                merchantMapper.selectOne(Wrappers.<MchEntity>lambdaQuery()
                        .eq(MchEntity::getEntityNo, merchantNo).last("limit 1")));
        if (entity == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }

        String settleType = cmd.settleAccountType() != null && !cmd.settleAccountType().isBlank()
                ? cmd.settleAccountType()
                : masterDataPort.settleAccountType(row.getLegalForm());

        // 资料不齐就别往通道发：通道拒一次要等一个工作日，而缺什么我们自己就能看出来
        List<String> missing = missingOf(row, settleType, cmd.settleAccount(), cmd.licenses());
        if (!missing.isEmpty()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }

        PayApplymentGateway gateway = gateway(cmd.payChannel());
        String channelApplyNo = gateway.submit(new PayApplymentGateway.SubmitCommand(
                merchantNo, entity.getName(), row.getLegalForm(),
                cmd.contactName(), cmd.contactPhone(), cmd.licenses(),
                settleType, cmd.settleAccount()));

        row.setChannelApplyNo(channelApplyNo);
        row.setSettleAccountType(settleType);
        /*
         * **只存掩码**。明文账号在 gateway.submit 里用完就没了 ——
         * 落库的话，一次库泄露就是一批人的银行账号（ADR-002 §5）。
         */
        row.setSettleAccountMasked(mask(cmd.settleAccount()));
        row.setApplyStatus(MchPaymentMerchant.APPLYING);
        // 重提时清掉上一次的拒因：留着的话商家改完资料还看见旧原因，会以为没提交成功
        row.setRejectReason(null);
        row.setAppliedAt(System.currentTimeMillis());
        DataScopeContext.executeWithoutScope(() -> paymentMapper.updateById(row));

        // 提交完立刻回查一次：stub 与部分通道是同步出结果的，让商家少等一轮
        return refresh(merchantNo, cmd.payChannel());
    }

    @Override
    @Transactional
    public PaymentApplymentVO refresh(String merchantNo, String payChannel) {
        MchPaymentMerchant row = require(merchantNo, payChannel);
        if (row.getChannelApplyNo() == null || row.getChannelApplyNo().isBlank()) {
            // 还没提交过，没什么可查的 —— 不要去问通道一个不存在的单号
            return toVO(row);
        }

        PayApplymentGateway.ApplymentResult r = gateway(payChannel).query(row.getChannelApplyNo());
        row.setApplyStatus(r.status());
        if (MchPaymentMerchant.ACTIVE.equals(r.status())) {
            row.setSubMchid(r.subMchid());
            /*
             * 开户成功才生成收款商户号业务键，且**只生成一次** ——
             * 通道重推回执是常态，每次都换一个号的话，门店挂的收款号会指向一个不存在的行。
             */
            if (row.getPayMerchantNo() == null || row.getPayMerchantNo().isBlank()) {
                row.setPayMerchantNo(BizKey.next(BizKey.PAY_MERCHANT));
            }
            if (row.getActivatedAt() == null) {
                row.setActivatedAt(System.currentTimeMillis());
            }
            row.setRejectReason(null);
        } else if (MchPaymentMerchant.REJECTED.equals(r.status())) {
            row.setRejectReason(r.rejectReason());
        }
        DataScopeContext.executeWithoutScope(() -> paymentMapper.updateById(row));
        return toVO(row);
    }

    // ------------------------------------------------------------------ 内部

    private List<MchPaymentMerchant> rows(String merchantNo) {
        return DataScopeContext.executeWithoutScope(() ->
                paymentMapper.selectList(Wrappers.<MchPaymentMerchant>lambdaQuery()
                        .eq(MchPaymentMerchant::getEntityNo, merchantNo)));
    }

    private MchPaymentMerchant require(String merchantNo, String payChannel) {
        return rows(merchantNo).stream()
                .filter(r -> r.getPayChannel().equals(payChannel))
                .findFirst()
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));
    }

    private PayApplymentGateway gateway(String payChannel) {
        /*
         * 没有对应实现时**直接失败**，不要回落到别的通道。
         * 回落的结果是「以为在给微信开户，其实开的是别处」，
         * 而这个错要到第一笔订单分账时才看得出来。
         */
        return Optional.ofNullable(gateways.get(payChannel))
                .or(() -> Optional.ofNullable(gateways.get("STUB")))
                .orElseThrow(() -> BizException.of(ErrorCode.BAD_REQUEST));
    }

    /** 缺什么就说缺什么 —— 「还差结算账户」比「审核中」有用得多。 */
    private List<String> missingOf(MchPaymentMerchant row, String settleType,
                                   String settleAccount, List<String> licenses) {
        List<String> missing = new ArrayList<>();
        if (settleType == null || settleType.isBlank()) {
            missing.add("settleAccountType");
        }
        if (settleAccount == null || settleAccount.isBlank()) {
            missing.add("settleAccount");
        }
        // 小微免执照，这正是它存在的意义（ADR-002 §4）；其余主体必须传
        if (!MchPaymentMerchant.MICRO.equals(row.getLegalForm())
                && (licenses == null || licenses.isEmpty())) {
            missing.add("licenses");
        }
        return missing;
    }

    private PaymentApplymentVO toVO(MchPaymentMerchant row) {
        boolean active = MchPaymentMerchant.ACTIVE.equals(row.getApplyStatus());
        List<String> missing = active ? List.of()
                : missingOf(row, row.getSettleAccountType(), row.getSettleAccountMasked(), null);
        return new PaymentApplymentVO(
                row.getPayChannel(),
                masterDataPort.channelName(row.getPayChannel()),
                row.getApplyStatus(),
                active,
                row.getPayMerchantNo(),
                mask(row.getSubMchid()),
                row.getSettleAccountType(),
                row.getSettleAccountMasked(),
                row.getRejectReason(),
                missing,
                row.getAppliedAt(),
                row.getActivatedAt());
    }

    /** 只留尾四位。口径与手机号/地址共用 {@link Masks} —— 三份实现就是三种口径。 */
    private String mask(String raw) {
        return Masks.tail(raw);
    }
}
