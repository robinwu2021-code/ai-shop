package ai.neargo.shop.food.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.food.dto.MenuPublicationSnapshot;
import ai.neargo.shop.food.dto.ReservationContracts.TransitionReq;
import ai.neargo.shop.food.dto.ServiceFormContracts.AttendeeVO;
import ai.neargo.shop.food.dto.ServiceFormContracts.BatchReq;
import ai.neargo.shop.food.dto.ServiceFormContracts.BookReq;
import ai.neargo.shop.food.dto.ServiceFormContracts.EnterReq;
import ai.neargo.shop.food.dto.ServiceFormContracts.FormVO;
import ai.neargo.shop.food.dto.ServiceFormContracts.Kind;
import ai.neargo.shop.food.dto.ServiceFormContracts.OrganizationReq;
import ai.neargo.shop.food.dto.ServiceFormContracts.OrganizationVO;
import ai.neargo.shop.food.dto.ServiceFormContracts.ReviseReq;
import ai.neargo.shop.food.dto.ServiceFormContracts.VersionReq;
import ai.neargo.shop.food.dto.ServiceSlotContracts.ClaimReq;
import ai.neargo.shop.food.entity.FoodBusinessOperationRow;
import ai.neargo.shop.food.mapper.FoodBusinessOperationsMapper;
import ai.neargo.shop.food.mapper.ServiceFormMapper;
import ai.neargo.shop.food.policy.CartInputPolicy;
import ai.neargo.shop.food.policy.FoodCommandFingerprint;
import ai.neargo.shop.food.policy.MenuPublicationDocuments;
import ai.neargo.shop.food.policy.PublicationDocuments;
import ai.neargo.shop.food.service.ReservationService;
import ai.neargo.shop.food.service.ServiceFormService;
import ai.neargo.shop.food.service.ServiceSlotLifecycle;
import ai.neargo.shop.food.service.ServiceSlotService;
import ai.neargo.shop.spi.user.StoreCardQueryPort;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Service admissions and original-order associations only. No credit, deposits or invented orders.
 */
public class ServiceFormServiceImpl implements ServiceFormService {
    private final ServiceFormMapper mapper;
    private final FoodBusinessOperationsMapper operations;
    private final TransactionTemplate tx;
    private final BusinessCommandJournal journal;
    private final ServiceSlotService slots;
    private final ServiceSlotLifecycle capacity;
    private final Supplier<ReservationService> reservations;
    private final StoreCardQueryPort stores;
    private final ObjectMapper json;
    private final Clock clock;

    public ServiceFormServiceImpl(
            ServiceFormMapper mapper,
            FoodBusinessOperationsMapper operations,
            PlatformTransactionManager manager,
            ServiceSlotService slots,
            ServiceSlotLifecycle capacity,
            Supplier<ReservationService> reservations,
            StoreCardQueryPort stores,
            ObjectMapper json,
            Clock clock) {
        this.mapper = mapper;
        this.operations = operations;
        this.tx = new TransactionTemplate(manager);
        this.slots = slots;
        this.capacity = capacity;
        this.reservations = reservations;
        this.stores = stores;
        this.json = json;
        this.clock = clock;
        this.journal = new BusinessCommandJournal(operations, json);
    }

    @Override
    public OrganizationVO registerOrganization(
            String e, String store, String actor, OrganizationReq request, String key) {
        scope(e, store, actor);
        if (request == null) bad();
        text(request.name(), 128);
        text(request.contactRef(), 64);
        text(request.externalRef(), 64);
        return tx.execute(
                t ->
                        journal.execute(
                                key,
                                command("REGISTER_SERVICE_ORGANIZATION", e, store),
                                actor,
                                PricingSnapshotHash.of(request),
                                OrganizationVO.class,
                                () -> {
                                    String no = BizKey.next("FSOR");
                                    mapper.registerOrganization(
                                            e,
                                            store,
                                            no,
                                            request.name(),
                                            request.contactRef(),
                                            request.externalRef(),
                                            actor);
                                    return new OrganizationVO(
                                            no,
                                            request.name(),
                                            request.contactRef(),
                                            request.externalRef(),
                                            "ACTIVE");
                                }));
    }

    @Override
    public FormVO book(String e, String store, String actor, BookReq request, String key) {
        scope(e, store, actor);
        if (request == null
                || request.kind() == null
                || request.guaranteedPersons() < 1
                || request.guaranteedPersons() > 1000
                || request.maxEntriesPerPerson() < 1
                || request.maxEntriesPerPerson() > 100
                || request.startsAt() == null
                || request.endsAt() == null
                || !request.startsAt().isBefore(request.endsAt())
                || !request.endsAt().isAfter(clock.instant())) bad();
        text(request.reason(), 255);
        millis(request.startsAt());
        millis(request.endsAt());
        return tx.execute(
                t ->
                        journal.execute(
                                key,
                                command("BOOK_SERVICE_FORM", e, store),
                                actor,
                                PricingSnapshotHash.of(request),
                                FormVO.class,
                                () -> {
                                    if (request.kind() == Kind.BANQUET
                                            && request.reservationNo() == null) bad();
                                    if (request.reservationNo() != null) {
                                        var reservation =
                                                one(
                                                        mapper.reservation(
                                                                e,
                                                                store,
                                                                request.reservationNo(),
                                                                true));
                                        if (!Set.of("HELD", "CONFIRMED")
                                                        .contains(reservation.text("status"))
                                                || reservation.number("party_size")
                                                        != request.guaranteedPersons()
                                                || !request.startsAt()
                                                        .equals(reservation.instant("start_at"))
                                                || !request.endsAt()
                                                        .equals(reservation.instant("end_at")))
                                            conflict();
                                    }
                                    organization(
                                            e,
                                            store,
                                            request.kind(),
                                            request.organizationNo(),
                                            request.contactRef());
                                    String menu =
                                            menu(
                                                    e,
                                                    store,
                                                    request.kind(),
                                                    request.menuNo(),
                                                    request.menuRevisionNo());
                                    String no = BizKey.next("FSFM");
                                    if (request.slotNo() != null) {
                                        var slot = slots.get(e, store, request.slotNo());
                                        if (!request.startsAt().equals(slot.startsAt())
                                                || request.endsAt().isAfter(slot.endsAt())
                                                || !Set.of("PERSON", "ORDER").contains(slot.unit()))
                                            conflict();
                                        slots.reserve(
                                                e,
                                                store,
                                                actor,
                                                new ClaimReq(
                                                        slot.slotNo(),
                                                        no,
                                                        "PERSON".equals(slot.unit())
                                                                ? BigDecimal.valueOf(
                                                                        request.guaranteedPersons())
                                                                : BigDecimal.ONE),
                                                "SERVICE_FORM_CAPACITY:" + no);
                                    }
                                    mapper.book(
                                            e,
                                            store,
                                            no,
                                            request.kind().name(),
                                            request.reservationNo(),
                                            request.slotNo(),
                                            request.menuNo(),
                                            request.menuRevisionNo(),
                                            menu,
                                            request.guaranteedPersons(),
                                            local(request.startsAt()),
                                            local(request.endsAt()),
                                            request.organizationNo(),
                                            request.contactRef(),
                                            request.maxEntriesPerPerson(),
                                            actor);
                                    audit(
                                            e,
                                            store,
                                            actor,
                                            no,
                                            "SERVICE_FORM_BOOK",
                                            request.reason(),
                                            key,
                                            PricingSnapshotHash.of(request));
                                    return get(e, store, no);
                                }));
    }

    @Override
    public FormVO get(String e, String store, String no) {
        return view(one(mapper.form(e, store, no, false)));
    }

    @Override
    public FormVO revise(
            String e, String store, String actor, String no, ReviseReq request, String key) {
        scope(e, store, actor);
        if (request == null
                || request.guaranteedPersons() < 1
                || request.guaranteedPersons() > 1000) bad();
        text(request.reason(), 255);
        return tx.execute(
                t ->
                        journal.execute(
                                key,
                                command("REVISE_SERVICE_FORM", e, store),
                                actor,
                                PricingSnapshotHash.of(List.of(no, request)),
                                FormVO.class,
                                () -> {
                                    var row = one(mapper.form(e, store, no, true));
                                    booked(row, request.version());
                                    if (row.text("reservation_no") != null) {
                                        var source =
                                                one(
                                                        mapper.reservation(
                                                                e,
                                                                store,
                                                                row.text("reservation_no"),
                                                                false));
                                        if (!Set.of("HELD", "CONFIRMED")
                                                        .contains(source.text("status"))
                                                || source.number("party_size")
                                                        != request.guaranteedPersons()) conflict();
                                    }
                                    String menu =
                                            menu(
                                                    e,
                                                    store,
                                                    Kind.valueOf(row.text("kind")),
                                                    request.menuNo(),
                                                    request.menuRevisionNo());
                                    if (row.text("slot_no") != null) {
                                        var slot = slots.get(e, store, row.text("slot_no"));
                                        capacity.resizeSource(
                                                e,
                                                store,
                                                actor,
                                                no,
                                                "PERSON".equals(slot.unit())
                                                        ? BigDecimal.valueOf(
                                                                request.guaranteedPersons())
                                                        : BigDecimal.ONE);
                                    }
                                    if (mapper.revise(
                                                    e,
                                                    store,
                                                    no,
                                                    request.version(),
                                                    request.guaranteedPersons(),
                                                    request.menuNo(),
                                                    request.menuRevisionNo(),
                                                    menu,
                                                    actor)
                                            != 1) conflict();
                                    audit(
                                            e,
                                            store,
                                            actor,
                                            no,
                                            "SERVICE_FORM_REVISE",
                                            request.reason(),
                                            key,
                                            PricingSnapshotHash.of(request));
                                    return get(e, store, no);
                                }));
    }

    @Override
    public FormVO enter(
            String e, String store, String actor, String no, EnterReq request, String key) {
        scope(e, store, actor);
        if (request == null
                || request.guests() == null
                || request.guests().isEmpty()
                || request.guests().size() > 1000) bad();
        text(request.reason(), 255);
        return tx.execute(
                t ->
                        journal.execute(
                                key,
                                command("ENTER_SERVICE_FORM", e, store),
                                actor,
                                PricingSnapshotHash.of(List.of(no, request)),
                                FormVO.class,
                                () -> {
                                    var check =
                                            one(mapper.check(e, store, request.checkNo(), true));
                                    if (check.number("version") != request.checkVersion()
                                            || !"OPEN".equals(check.text("status"))
                                            || !"OPEN".equals(check.text("service_state"))
                                            || check.number("party_size")
                                                    != request.guests().size()) conflict();
                                    var row = one(mapper.form(e, store, no, true));
                                    booked(row, request.version());
                                    window(row);
                                    if (row.text("reservation_no") != null) {
                                        var source =
                                                one(
                                                        mapper.reservation(
                                                                e,
                                                                store,
                                                                row.text("reservation_no"),
                                                                false));
                                        if (!"ARRIVED".equals(source.text("status"))
                                                || !request.checkNo()
                                                        .equals(source.text("check_no")))
                                            conflict();
                                    }
                                    organization(
                                            e,
                                            store,
                                            Kind.valueOf(row.text("kind")),
                                            row.text("organization_no"),
                                            row.text("contact_ref"));
                                    var guestRefs = new HashSet<String>();
                                    var seats = new HashSet<String>();
                                    for (var guest : request.guests()) {
                                        if (guest == null
                                                || !Set.of("ADULT", "CHILD", "OTHER")
                                                        .contains(guest.personClass())) bad();
                                        text(guest.guestRef(), 64);
                                        if (!guestRefs.add(guest.guestRef())) bad();
                                        if (guest.seatNo() != null
                                                && (!seats.add(guest.seatNo())
                                                        || mapper.seatCount(
                                                                        e,
                                                                        request.checkNo(),
                                                                        guest.seatNo())
                                                                != 1)) conflict();
                                    }
                                    if (mapper.enter(
                                                    e,
                                                    store,
                                                    no,
                                                    request.version(),
                                                    request.checkNo(),
                                                    request.guests().size(),
                                                    actor)
                                            != 1) conflict();
                                    for (var guest : request.guests())
                                        mapper.addAttendee(
                                                e,
                                                store,
                                                BizKey.next("FSAT"),
                                                no,
                                                guest.guestRef(),
                                                guest.seatNo(),
                                                guest.personClass(),
                                                actor);
                                    if (mapper.advanceCheck(
                                                    e,
                                                    store,
                                                    request.checkNo(),
                                                    request.checkVersion())
                                            != 1) conflict();
                                    var claims = operations.capacityBySource(e, store, no);
                                    if (!claims.isEmpty()) {
                                        var claim = claims.getFirst();
                                        slots.transition(
                                                e,
                                                store,
                                                actor,
                                                claim.text("claim_no"),
                                                claim.number("version"),
                                                "CONSUME",
                                                "SERVICE_FORM_ENTER:" + no);
                                    }
                                    audit(
                                            e,
                                            store,
                                            actor,
                                            no,
                                            "SERVICE_FORM_ENTER",
                                            request.reason(),
                                            key,
                                            PricingSnapshotHash.of(request));
                                    return get(e, store, no);
                                }));
    }

    @Override
    public FormVO leave(
            String e, String store, String actor, String no, VersionReq request, String key) {
        scope(e, store, actor);
        if (request == null) bad();
        text(request.reason(), 255);
        return tx.execute(
                t ->
                        journal.execute(
                                key,
                                command("LEAVE_SERVICE_FORM", e, store),
                                actor,
                                PricingSnapshotHash.of(List.of(no, request)),
                                FormVO.class,
                                () -> {
                                    var row = one(mapper.form(e, store, no, true));
                                    if (row.number("version") != request.version()
                                            || !"ACTIVE".equals(row.text("status"))) conflict();
                                    mapper.closeAttendees(e, store, no, actor);
                                    if (mapper.leave(e, store, no, request.version(), actor) != 1)
                                        conflict();
                                    audit(
                                            e,
                                            store,
                                            actor,
                                            no,
                                            "SERVICE_FORM_LEAVE",
                                            request.reason(),
                                            key,
                                            row.text("check_no"));
                                    return get(e, store, no);
                                }));
    }

    @Override
    public FormVO cancel(
            String e, String store, String actor, String no, VersionReq request, String key) {
        return cancel(e, store, actor, no, request, key, "CANCELLED");
    }

    @Override
    public FormVO noShow(
            String e, String store, String actor, String no, VersionReq request, String key) {
        return cancel(e, store, actor, no, request, key, "NO_SHOW");
    }

    private FormVO cancel(
            String e,
            String store,
            String actor,
            String no,
            VersionReq request,
            String key,
            String target) {
        scope(e, store, actor);
        if (request == null) bad();
        text(request.reason(), 255);
        var intention =
                tx.execute(
                        t ->
                                journal.execute(
                                        key,
                                        command("CANCEL_SERVICE_FORM_" + target, e, store),
                                        actor,
                                        PricingSnapshotHash.of(List.of(no, request)),
                                        FormVO.class,
                                        () -> {
                                            var row = one(mapper.form(e, store, no, true));
                                            booked(row, request.version());
                                            if ("NO_SHOW".equals(target)
                                                    && clock.instant()
                                                            .isBefore(row.instant("ends_at")))
                                                conflict();
                                            if (mapper.cancelIntent(
                                                            e,
                                                            store,
                                                            no,
                                                            request.version(),
                                                            target,
                                                            request.reason(),
                                                            actor)
                                                    != 1) conflict();
                                            audit(
                                                    e,
                                                    store,
                                                    actor,
                                                    no,
                                                    "SERVICE_FORM_CANCEL_INTENT",
                                                    request.reason(),
                                                    key,
                                                    target);
                                            return get(e, store, no);
                                        }));
        if (!"CANCELLING".equals(get(e, store, no).status())) return get(e, store, no);
        if (intention.reservationNo() != null) {
            var source = one(mapper.reservation(e, store, intention.reservationNo(), false));
            if (!target.equals(source.text("status"))) {
                var transition = new TransitionReq(source.number("version"), request.reason());
                if ("NO_SHOW".equals(target))
                    reservations
                            .get()
                            .noShow(
                                    e,
                                    store,
                                    actor,
                                    intention.reservationNo(),
                                    transition,
                                    "SERVICE_FORM_CANCEL:" + no);
                else
                    reservations
                            .get()
                            .cancel(
                                    e,
                                    store,
                                    actor,
                                    intention.reservationNo(),
                                    transition,
                                    "SERVICE_FORM_CANCEL:" + no);
            }
            var actual = one(mapper.reservation(e, store, intention.reservationNo(), false));
            if (!target.equals(actual.text("status"))) conflict();
        }
        return tx.execute(
                t -> {
                    var row = one(mapper.form(e, store, no, true));
                    if ("CANCELLING".equals(row.text("status"))) {
                        capacity.releaseCart(e, store, actor, no);
                        mapper.cancelled(
                                e,
                                store,
                                no,
                                target,
                                intention.reservationNo() == null
                                        ? "LOCAL_CAPACITY_RELEASED"
                                        : target,
                                actor);
                    }
                    return get(e, store, no);
                });
    }

    /** The original reservation owner invokes this inside its cancellation/no-show transaction. */
    public void reservationTransition(
            String e, String store, String reservation, String target, String actor) {
        for (var row : mapper.reservationForms(e, store, reservation)) {
            capacity.releaseCart(e, store, actor, row.text("form_no"));
            mapper.cancelled(e, store, row.text("form_no"), target, target, actor);
        }
    }

    @Override
    public FormVO bindBatch(
            String e, String store, String actor, String no, BatchReq request, String key) {
        scope(e, store, actor);
        if (request == null) bad();
        text(request.reason(), 255);
        return tx.execute(
                t ->
                        journal.execute(
                                key,
                                command("BIND_SERVICE_FORM_BATCH", e, store),
                                actor,
                                PricingSnapshotHash.of(List.of(no, request)),
                                FormVO.class,
                                () -> {
                                    var row = one(mapper.form(e, store, no, true));
                                    if (row.number("version") != request.version()
                                            || !"ACTIVE".equals(row.text("status"))) conflict();
                                    var batch = one(mapper.batch(e, store, request.batchNo()));
                                    var lines = mapper.batchLineOwners(e, store, request.batchNo());
                                    if (lines.isEmpty()
                                            || lines.stream()
                                                    .anyMatch(
                                                            l ->
                                                                    !row.text("check_no")
                                                                            .equals(
                                                                                    l.text(
                                                                                            "current_check_no"))))
                                        conflict();
                                    if (row.text("menu_no") != null) {
                                        var original =
                                                json.readTree(batch.text("frozen_request"))
                                                        .path("facts")
                                                        .path("payload")
                                                        .path("cart")
                                                        .path("lines");
                                        if (!original.isArray() || original.isEmpty()) conflict();
                                        for (var line : original)
                                            if (!row.text("menu_no")
                                                            .equals(line.path("menuNo").asText())
                                                    || !row.text("menu_revision_no")
                                                            .equals(
                                                                    line.path("menuRevisionNo")
                                                                            .asText())) conflict();
                                    }
                                    String hash = PricingSnapshotHash.of(batch);
                                    mapper.bindBatch(e, store, no, request.batchNo(), hash, actor);
                                    var binding =
                                            one(mapper.batchBinding(e, store, request.batchNo()));
                                    if (!no.equals(binding.text("form_no"))
                                            || !hash.equals(binding.text("source_hash")))
                                        conflict();
                                    if (mapper.advance(e, store, no, request.version(), actor) != 1)
                                        conflict();
                                    audit(
                                            e,
                                            store,
                                            actor,
                                            no,
                                            "SERVICE_FORM_BIND_BATCH",
                                            request.reason(),
                                            key,
                                            hash);
                                    return get(e, store, no);
                                }));
    }

    @Override
    public List<AttendeeVO> attendees(String e, String store, String no) {
        get(e, store, no);
        return mapper.attendees(e, store, no, false).stream().map(this::attendee).toList();
    }

    @Override
    public AttendeeVO admit(
            String e,
            String store,
            String actor,
            String no,
            String guest,
            VersionReq req,
            String key) {
        return admission(e, store, actor, no, guest, req, key, true);
    }

    @Override
    public AttendeeVO exit(
            String e,
            String store,
            String actor,
            String no,
            String guest,
            VersionReq req,
            String key) {
        return admission(e, store, actor, no, guest, req, key, false);
    }

    private AttendeeVO admission(
            String e,
            String store,
            String actor,
            String no,
            String guest,
            VersionReq req,
            String key,
            boolean enter) {
        scope(e, store, actor);
        if (req == null) bad();
        text(req.reason(), 255);
        return tx.execute(
                t ->
                        journal.execute(
                                key,
                                command(
                                        enter
                                                ? "READMIT_SERVICE_ATTENDEE"
                                                : "EXIT_SERVICE_ATTENDEE",
                                        e,
                                        store),
                                actor,
                                PricingSnapshotHash.of(List.of(no, guest, req)),
                                AttendeeVO.class,
                                () -> {
                                    var form = one(mapper.form(e, store, no, true));
                                    if (!"ACTIVE".equals(form.text("status"))) conflict();
                                    if (enter) window(form);
                                    var row =
                                            mapper.attendees(e, store, no, true).stream()
                                                    .filter(
                                                            a ->
                                                                    guest.equals(
                                                                            a.text("attendee_no")))
                                                    .findFirst()
                                                    .orElseThrow(
                                                            () ->
                                                                    BizException.of(
                                                                            ErrorCode.NOT_FOUND));
                                    if (row.number("version") != req.version()
                                            || !(enter ? "OUT" : "IN").equals(row.text("status"))
                                            || enter
                                                    && row.number("entry_count")
                                                            >= form.number(
                                                                    "max_entries_per_person"))
                                        conflict();
                                    if (mapper.attendeeTransition(
                                                    e,
                                                    store,
                                                    no,
                                                    guest,
                                                    req.version(),
                                                    enter ? "IN" : "OUT",
                                                    enter,
                                                    actor)
                                            != 1) conflict();
                                    audit(
                                            e,
                                            store,
                                            actor,
                                            no,
                                            enter
                                                    ? "SERVICE_ATTENDEE_ADMIT"
                                                    : "SERVICE_ATTENDEE_EXIT",
                                            req.reason(),
                                            key,
                                            guest);
                                    return attendee(
                                            mapper.attendees(e, store, no, false).stream()
                                                    .filter(
                                                            a ->
                                                                    guest.equals(
                                                                            a.text("attendee_no")))
                                                    .findFirst()
                                                    .orElseThrow());
                                }));
    }

    private String menu(String e, String store, Kind kind, String no, String revision) {
        if (no == null && revision == null) {
            if (kind == Kind.BUFFET || kind == Kind.BANQUET) bad();
            return null;
        }
        CartInputPolicy.key(no);
        CartInputPolicy.key(revision);
        var row = one(mapper.menu(e, revision));
        if (!no.equals(row.text("config_no"))
                || !PublicationDocuments.hash(json, row.text("definition_snapshot"))
                        .equals(row.text("content_hash"))) conflict();
        MenuPublicationDocuments.validate(json, no, row.text("definition_snapshot"));
        var body = json.readValue(row.text("definition_snapshot"), MenuPublicationSnapshot.class);
        if (!e.equals(body.menu().entityNo()) || !store.equals(body.menu().storeNo())) conflict();
        return row.text("definition_snapshot");
    }

    private void organization(String e, String store, Kind kind, String org, String contact) {
        if (kind != Kind.CORPORATE) {
            if (org != null) bad();
            return;
        }
        if (org == null || contact == null) bad();
        var row = one(mapper.organization(e, store, org));
        if (!contact.equals(row.text("contact_ref"))) conflict();
    }

    private void window(FoodBusinessOperationRow row) {
        if (clock.instant().isBefore(row.instant("starts_at"))
                || !clock.instant().isBefore(row.instant("ends_at"))) conflict();
    }

    private void booked(FoodBusinessOperationRow row, long version) {
        if (row.number("version") != version || !"BOOKED".equals(row.text("status"))) conflict();
    }

    private void scope(String e, String store, String actor) {
        CartInputPolicy.key(e);
        CartInputPolicy.key(store);
        CartInputPolicy.key(actor);
        var card = stores.cards(List.of(store)).get(store);
        if (card == null || !e.equals(card.entityNo()) || !card.active())
            throw BizException.of(ErrorCode.FORBIDDEN);
    }

    private FormVO view(FoodBusinessOperationRow r) {
        return new FormVO(
                r.text("form_no"),
                Kind.valueOf(r.text("kind")),
                r.text("status"),
                r.text("reservation_no"),
                r.text("slot_no"),
                r.text("menu_no"),
                r.text("menu_revision_no"),
                (int) r.number("guaranteed_persons"),
                r.get("actual_persons") == null
                        ? null
                        : ((Number) r.get("actual_persons")).intValue(),
                r.text("check_no"),
                r.instant("starts_at"),
                r.instant("ends_at"),
                r.text("organization_no"),
                r.text("contact_ref"),
                (int) r.number("max_entries_per_person"),
                r.text("source_transition"),
                r.number("version"));
    }

    private AttendeeVO attendee(FoodBusinessOperationRow r) {
        return new AttendeeVO(
                r.text("attendee_no"),
                r.text("guest_ref"),
                r.text("seat_no"),
                r.text("person_class"),
                r.text("status"),
                (int) r.number("entry_count"),
                r.number("version"));
    }

    private void audit(
            String e,
            String store,
            String actor,
            String no,
            String action,
            String reason,
            String key,
            String hash) {
        operations.businessOperationsInsertActionLog(
                e,
                store,
                BizKey.next("FSAC"),
                no,
                action,
                actor,
                reason,
                PricingSnapshotHash.of(key),
                PricingSnapshotHash.of(hash),
                actor,
                actor);
    }

    private static String command(String op, String e, String store) {
        return FoodCommandFingerprint.scope(op, e, store);
    }

    private static FoodBusinessOperationRow one(List<FoodBusinessOperationRow> rows) {
        if (rows.size() != 1) throw BizException.of(ErrorCode.NOT_FOUND);
        return rows.getFirst();
    }

    private static LocalDateTime local(Instant time) {
        return LocalDateTime.ofInstant(time, ZoneOffset.UTC);
    }

    private static void millis(Instant time) {
        if (time.getNano() % 1_000_000 != 0) bad();
    }

    private static void text(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max) bad();
    }

    private static void bad() {
        throw BizException.of(ErrorCode.BAD_REQUEST);
    }

    private static void conflict() {
        throw BizException.of(ErrorCode.CONFLICT);
    }
}
