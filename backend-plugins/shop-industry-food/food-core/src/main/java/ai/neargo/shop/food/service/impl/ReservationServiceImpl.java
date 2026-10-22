package ai.neargo.shop.food.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.config.ConditionalOnFoodEnabled;
import ai.neargo.shop.food.config.FoodPersistenceConfiguration.PersistenceEnabled;
import ai.neargo.shop.food.dto.ListReservationsQuery;
import ai.neargo.shop.food.dto.ReservationVO;
import ai.neargo.shop.food.entity.FnbReservation;
import ai.neargo.shop.food.mapper.FoodMappers.ReservationMapper;
import ai.neargo.shop.food.service.ReservationService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Independent reservation read side; no synthetic creation/cancellation or deposit status. */
@Service
@ConditionalOnFoodEnabled
@Conditional(PersistenceEnabled.class)
public class ReservationServiceImpl implements ReservationService {
    private final ReservationMapper reservations;
    public ReservationServiceImpl(ReservationMapper reservations) { this.reservations=reservations; }

    @Override
    @Transactional(transactionManager="transactionManager",readOnly=true)
    public PageData<ReservationVO> listReservations(String entity,String store,ListReservationsQuery query) {
        if(entity==null || entity.isBlank() || entity.length()>32 || store==null || store.isBlank() || store.length()>32
                || query==null || !timestamp(query.from()) || !timestamp(query.to()) || !query.from().isBefore(query.to()))
            throw BizException.of(ErrorCode.BAD_REQUEST);
        int page=query.page()==null?1:query.page();int size=query.size()==null?20:query.size();
        if(page<1 || size<1 || size>100 || query.status()!=null
                && !Set.of("HELD","CONFIRMED","ARRIVED","CANCELLED","NO_SHOW").contains(query.status()))
            throw BizException.of(ErrorCode.BAD_REQUEST);
        var filter=Wrappers.<FnbReservation>lambdaQuery().eq(FnbReservation::getEntityNo,entity)
                .eq(FnbReservation::getStoreNo,store).lt(FnbReservation::getStartAt,local(query.to()))
                .gt(FnbReservation::getEndAt,local(query.from()))
                .eq(query.status()!=null,FnbReservation::getStatus,query.status())
                .orderByAsc(FnbReservation::getStartAt).orderByAsc(FnbReservation::getReservationNo);
        var result=reservations.selectPage(new Page<FnbReservation>(page,size),filter);
        return PageData.of(result.getRecords().stream().map(row -> new ReservationVO(row.getReservationNo(),row.getPartySize(),
                row.getStartAt().toInstant(ZoneOffset.UTC),row.getEndAt().toInstant(ZoneOffset.UTC),row.getStatus(),
                row.getDepositRef(),row.getCheckNo(),row.getVersion())).toList(),result.getTotal(),page,size);
    }
    private static boolean timestamp(Instant at) {
        return at!=null && at.getNano()%1_000_000==0 && !at.isBefore(Instant.parse("1000-01-01T00:00:00Z"))
                && !at.isAfter(Instant.parse("9999-12-31T23:59:59.999Z"));
    }
    private static LocalDateTime local(Instant at) { return LocalDateTime.ofInstant(at,ZoneOffset.UTC); }
}
