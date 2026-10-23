package ai.neargo.shop.food.policy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.OpenCheckReq;
import ai.neargo.shop.food.dto.SeatReq;
import ai.neargo.shop.food.dto.TableVersionReq;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;

/** Normalize the exact existing open-check contract before freezing a durable seating attempt. */
public final class QueueSeatingPolicy {
    private QueueSeatingPolicy() { }
    public static OpenCheckReq normalize(OpenCheckReq request,int party) {
        if(request==null || request.partySize()==null || request.partySize()!=party
                || !"PREPAY".equals(request.payMode()) || request.reservationNo()!=null
                || request.serviceEmployeeNo()!=null || request.sharingMode()==null || !Set.of("EXCLUSIVE","SHARED").contains(request.sharingMode())
                || request.tableNos()==null || request.tableNos().isEmpty() || request.tableNos().size()>100
                || request.tableVersions()==null || request.tableVersions().size()!=request.tableNos().size()
                || request.seats()==null || request.seats().size()>1000 || request.seats().size()>party) throw bad();
        var tables=new HashSet<String>();
        for(var number:request.tableNos()) if(!key(number,32) || !tables.add(number)) throw bad();
        var versions=new HashSet<String>();
        for(var version:request.tableVersions()) {
            if(version==null || !tables.contains(version.tableNo()) || !versions.add(version.tableNo())
                    || version.version()==null || version.version()<0) throw bad();
        }
        var labels=new HashSet<String>();
        for(var seat:request.seats()) {
            if(seat==null || seat.seatNo()!=null || seat.guestRef()!=null || !key(seat.label(),32)
                    || !labels.add(seat.label().trim())) throw bad();
        }
        return new OpenCheckReq(tables.stream().sorted().toList(),party,"PREPAY",null,
                request.tableVersions().stream().sorted(Comparator.comparing(TableVersionReq::tableNo)).toList(),
                request.sharingMode(),request.seats().stream().map(s -> new SeatReq(null,s.label().trim(),null))
                        .sorted(Comparator.comparing(SeatReq::label)).toList(),null);
    }
    private static boolean key(String value,int max) { return value!=null && !value.isBlank() && value.length()<=max; }
    private static BizException bad() { return BizException.of(ErrorCode.BAD_REQUEST); }
}
