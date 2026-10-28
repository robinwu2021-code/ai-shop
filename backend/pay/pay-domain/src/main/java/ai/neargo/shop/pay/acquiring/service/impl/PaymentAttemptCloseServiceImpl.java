package ai.neargo.shop.pay.acquiring.service.impl;

import ai.neargo.shop.pay.access.PayCaller;
import ai.neargo.shop.pay.acquiring.entity.PayOrder;
import ai.neargo.shop.pay.acquiring.service.PaymentAttemptCloseService;
import ai.neargo.shop.pay.channel.api.AccessMode;
import ai.neargo.shop.pay.channel.api.ChannelAccountContext;
import ai.neargo.shop.pay.channel.api.ChannelGateway;
import ai.neargo.shop.pay.channel.api.ChannelResult;
import ai.neargo.shop.pay.channel.api.ClientType;
import ai.neargo.shop.pay.channel.api.InvocationMeta;
import ai.neargo.shop.pay.channel.api.SourceInfo;
import ai.neargo.shop.pay.channel.api.command.CloseCommand;
import ai.neargo.shop.pay.channel.api.command.QueryCommand;
import ai.neargo.shop.pay.channel.context.ChannelContextFactory;
import ai.neargo.shop.pay.channel.context.ChannelRoute;
import ai.neargo.shop.pay.entity.StlPayment;
import ai.neargo.shop.spi.pay.PayMerchantQueryPort;
import ai.neargo.shop.spi.pay.PayMerchantQueryPort.PayRoute;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** 关单与查回走同一渠道管线；保留冻结商户/密钥版本，不按门店今天的收款绑定选路。 */
@Service
public class PaymentAttemptCloseServiceImpl implements PaymentAttemptCloseService {
    private final ChannelGateway channels;
    private final ChannelContextFactory contexts;
    private final PayMerchantQueryPort merchants;

    public PaymentAttemptCloseServiceImpl(ChannelGateway channels, ChannelContextFactory contexts,
                                           PayMerchantQueryPort merchants) {
        this.channels = channels;
        this.contexts = contexts;
        this.merchants = merchants;
    }

    @Override
    public ChannelResult query(PayCaller caller, PayOrder order, StlPayment attempt) {
        ChannelAccountContext account = account(attempt);
        return account == null ? new ChannelResult.Unknown("ORIGINAL_MERCHANT_UNAVAILABLE")
                : channels.execute(account, new QueryCommand(meta(caller, order, attempt), attempt.getOutTradeNo()));
    }

    @Override
    public boolean close(PayCaller caller, PayOrder order, StlPayment attempt) {
        ChannelAccountContext account = account(attempt);
        if (account == null) {
            return false;
        }
        InvocationMeta meta = meta(caller, order, attempt);
        ChannelResult result = channels.execute(account, new CloseCommand(meta, attempt.getOutTradeNo()));
        if (result instanceof ChannelResult.Succeeded) {
            return true;
        }
        // 查无此单不能证明稍后不会创建；只能接受渠道明确已关的状态。
        ChannelResult queried = channels.execute(account, new QueryCommand(meta, attempt.getOutTradeNo()));
        return queried instanceof ChannelResult.Pending p
                && ("CLOSED".equals(p.channelStatus()) || "TRADE_CLOSED".equals(p.channelStatus()));
    }

    private ChannelAccountContext account(StlPayment attempt) {
        PayRoute merchant = attempt.getPayMerchantId() == null ? null
                : merchants.routeByPayMerchantId(attempt.getPayMerchantId(), attempt.getSecKeyVersion()).orElse(null);
        if (attempt.getPayMerchantId() != null && (merchant == null
                || !Objects.equals(merchant.payChannel(), attempt.getPayChannel()))) {
            return null;
        }
        AccessMode mode = merchant == null ? null : AccessMode.valueOf(merchant.accessMode());
        ChannelRoute route = new ChannelRoute(attempt.getPayChannel(), mode, attempt.getMarket(),
                attempt.getCurrency(), attempt.getPayMerchantId(),
                attempt.getPayMerchantNo() == null && merchant != null ? merchant.payMerchantNo()
                        : attempt.getPayMerchantNo(), attempt.getSubMerchantId(), attempt.getSecKeyVersion());
        return contexts.from(route);
    }

    private static InvocationMeta meta(PayCaller caller, PayOrder order, StlPayment attempt) {
        SourceInfo source = new SourceInfo(caller.type(), caller.callerId(), null, ClientType.SERVER,
                null, null, null, caller.operatorNo(), order.getBizType(), order.getBizNo());
        return new InvocationMeta(null, order.getId(), attempt.getId(), null, attempt.getPayMerchantId(), source);
    }
}
