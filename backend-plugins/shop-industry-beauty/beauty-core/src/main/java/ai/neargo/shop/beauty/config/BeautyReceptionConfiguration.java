package ai.neargo.shop.beauty.config;

import ai.neargo.shop.beauty.mapper.BeautyMappers;
import ai.neargo.shop.beauty.policy.AppointmentAccess;
import ai.neargo.shop.beauty.policy.CustomerOwnership;
import ai.neargo.shop.beauty.policy.ReceptionAccess;
import ai.neargo.shop.beauty.policy.ReceptionIdentity;
import ai.neargo.shop.beauty.service.impl.AppointmentPersistence;
import ai.neargo.shop.beauty.service.impl.GuestRegistrationServiceImpl;
import ai.neargo.shop.beauty.service.impl.ReceptionPreparation;
import ai.neargo.shop.beauty.service.impl.ReceptionServiceImpl;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.spi.member.CustomerIdentityPort;
import ai.neargo.shop.spi.member.GuestRegistrationPort;
import ai.neargo.shop.spi.user.MerchantOperatorAccessPort;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Local reception capabilities use lazy real owner ports, retaining disabled and remote-only deployment shapes. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBeautyEnabled
public class BeautyReceptionConfiguration {
    @Bean CustomerOwnership customerOwnership(BeautyMappers.GuestClaimReceiptMapper receipts) {
        return new CustomerOwnership(receipts);
    }
    @Bean ReceptionAccess receptionAccess(AppointmentAccess access, ObjectProvider<MerchantOperatorAccessPort> owner) {
        return new ReceptionAccess(access, owner);
    }
    @Bean ReceptionIdentity receptionIdentity(ReceptionAccess access, ObjectProvider<GuestRegistrationPort> guests,
            ObjectProvider<CustomerIdentityPort> customers) { return new ReceptionIdentity(access, guests, customers); }
    @Bean ReceptionPreparation receptionPreparation(AppointmentPersistence db,
            BeautyMappers.ReceptionOperationMapper operations) { return new ReceptionPreparation(db, operations); }
    @Bean GuestRegistrationServiceImpl guestRegistrationServiceImpl(AppointmentPersistence db, ReceptionAccess access,
            ReceptionIdentity identity, ReceptionPreparation preparations, ObjectProvider<GuestRegistrationPort> owner,
            BeautyMappers.ReceptionOperationMapper operations, BeautyMappers.GuestClaimReceiptMapper receipts,
            @Qualifier("beautyOutboxPublisher") OutboxPublisher publisher) {
        return new GuestRegistrationServiceImpl(db, access, identity, preparations, owner, operations,
                receipts, publisher);
    }
    @Bean Clock beautyReceptionClock() { return Clock.systemUTC(); }
    @Bean ReceptionServiceImpl receptionServiceImpl(AppointmentPersistence db, AppointmentAccess access,
            BeautyMappers.ArrivalChallengeMapper challenges, @Qualifier("beautyReceptionClock") Clock clock) {
        return new ReceptionServiceImpl(db, access, challenges, clock);
    }
}
