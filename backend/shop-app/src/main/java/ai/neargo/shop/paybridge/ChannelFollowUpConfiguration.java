package ai.neargo.shop.paybridge;

import ai.neargo.shop.pay.channel.api.ChannelGateway;
import ai.neargo.shop.pay.channel.followup.ChannelFollowUpService;
import ai.neargo.shop.pay.channel.followup.FollowUpResolver;
import ai.neargo.shop.pay.channel.followup.FollowUpStore;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 补查服务装在应用层：入账要跨到订单（{@link PaymentFollowUpResolver}），pay-channel 不认识订单 */
@Configuration(proxyBeanMethods = false)
public class ChannelFollowUpConfiguration {

    @Bean
    ChannelFollowUpService channelFollowUpService(FollowUpStore store, ChannelGateway channels,
                                                  FollowUpResolver resolver) {
        return new ChannelFollowUpService(store, channels, resolver, Clock.systemUTC());
    }
}
