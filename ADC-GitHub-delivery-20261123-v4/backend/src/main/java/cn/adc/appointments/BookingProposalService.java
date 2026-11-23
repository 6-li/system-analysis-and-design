package cn.adc.appointments;

import cn.adc.duration.DurationRepository;
import cn.adc.shared.audit.CommandRecovery.Rejection;
import cn.adc.shared.config.ClinicDirectoryProvider;
import cn.adc.shared.config.DentistEligibilityProvider;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.resource.ResourceLockCoordinator.Version;
import cn.adc.shared.security.StaffAuthorization;
import cn.adc.shared.time.ClinicTimePolicy;
import tools.jackson.databind.JsonNode;
import java.time.*;
import java.util.*;

/** Read-only, non-reserving preview. A later write MUST still use InitialBookingReview. No HTTP entry. */
final class BookingProposalService {
    record Window(Version ref, long clinicId, long dentistId, Instant startsAt, Instant endsAt) {}
    private final JdbcTransactions tx;
    private final StaffAuthorization auth;
    private final ClinicDirectoryProvider clinics;
    private final DentistEligibilityProvider dentists;
    private final FormalBookingService.ReleaseEvidence release;
    private final Clock clock;
    BookingProposalService(JdbcTransactions tx, StaffAuthorization auth, ClinicDirectoryProvider clinics,
                           DentistEligibilityProvider dentists, FormalBookingService.ReleaseEvidence release, Clock clock) {
        this.tx=Objects.requireNonNull(tx);this.auth=Objects.requireNonNull(auth);this.clinics=Objects.requireNonNull(clinics);
        this.dentists=Objects.requireNonNull(dentists);this.release=Objects.requireNonNull(release);this.clock=Objects.requireNonNull(clock);
    }
    List<JsonNode> projects(long clinic) {
        var actor=auth.require("UC02",Set.of(clinic)).actor();
        return tx.execute(c->{var result=new DurationRepository().current(c);requireActor(actor,clinic);return result;});
    }
    List<Window> windows(long clinic, LocalDate date) {
        var actor=auth.require("UC02",Set.of(clinic)).actor();var directory=clinics.find(clinic);
        if(directory==null||directory.id()!=clinic||directory.zone()==null)throw new Rejection("VERIFIED_CLINIC_REQUIRED");
        directory.provenance().requireUsable();
        var start=date.atStartOfDay(directory.zone()).toInstant();var end=date.plusDays(1).atStartOfDay(directory.zone()).toInstant();
        return tx.execute(c->{
            var result=new ArrayList<Window>();
            try(var p=c.prepareStatement("""
                SELECT schedule_id,schedule_version_no,dentist_id,starts_at,ends_at
                FROM adc.reception_schedule_version
                WHERE clinic_id=? AND activation_status='EFFECTIVE' AND starts_at < ? AND ? < ends_at
                ORDER BY starts_at,dentist_id,schedule_id,schedule_version_no
                """)){
                p.setLong(1,clinic);p.setObject(2,end.atOffset(ZoneOffset.UTC));p.setObject(3,start.atOffset(ZoneOffset.UTC));
                try(var r=p.executeQuery()){while(r.next())result.add(new Window(new Version(r.getLong(1),r.getInt(2)),clinic,r.getLong(3),r.getObject(4,OffsetDateTime.class).toInstant(),r.getObject(5,OffsetDateTime.class).toInstant()));}
            }
            requireActor(actor,clinic);return List.copyOf(result);
        });
    }
    InitialBookingReview.AcceptedScheme propose(long clinic,long patient,Version version,long project,
                                                String localStart,ZoneOffset offset,String need) {
        if(patient<=0||project<=0||need==null||need.isBlank())throw new IllegalArgumentException("BOOKING_DEMAND_REQUIRED");
        var actor=auth.require("UC02",Set.of(clinic)).actor();
        return tx.execute(c->{
            try(var p=c.prepareStatement("SELECT 1 FROM adc.patient WHERE patient_id=?")){
                p.setLong(1,patient);try(var r=p.executeQuery()){if(!r.next())throw new Rejection("PATIENT_NOT_FOUND");}
            }
            var row=new DurationRepository().current(c,project);
            var basis=InitialBookingReview.currentBasis(row,project);
            var schedule=InitialBookingReview.schedule(c,version);
            if(schedule.clinic()!=clinic)throw new SecurityException("SCHEDULE_SCOPE_DENIED");
            if(!"EFFECTIVE".equals(schedule.status()))throw new Rejection("EFFECTIVE_SCHEDULE_REQUIRED");
            var directory=clinics.find(clinic);
            if(directory==null||directory.id()!=clinic||directory.zone()==null)throw new Rejection("VERIFIED_CLINIC_REQUIRED");
            directory.provenance().requireUsable();
            var time=new ClinicTimePolicy(directory.provenance().version(),Map.of(clinic,directory.zone().getId())).resolve(clinic,localStart,offset,basis.minutes());
            InitialBookingReview.interval(clinics,time.start().atOffset(time.startOffset()).toString(),time.end().atOffset(time.endOffset()).toString(),schedule,basis.minutes());
            var scheme=new InitialBookingReview.AcceptedScheme(patient,version,clinic,schedule.dentist(),time.start(),time.end(),need.trim(),basis);
            FormalBookingService.requireDentist(dentists,scheme);
            for(var overlap:new AppointmentRepository().overlaps(c,scheme.dentistId(),scheme.startsAt(),scheme.endsAt()))
                if(!FormalBookingService.released(c,overlap,release,clock))throw new Rejection("APPOINTMENT_CAPACITY_CONFLICT");
            requireActor(actor,clinic);return scheme;
        });
    }
    JsonNode calendar(long clinic,LocalDate date){
        var actor=auth.require("UC02",Set.of(clinic)).actor();var directory=clinics.find(clinic);
        if(directory==null||directory.id()!=clinic||directory.zone()==null)throw new Rejection("VERIFIED_CLINIC_REQUIRED");directory.provenance().requireUsable();
        var first=date.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        var lo=first.atStartOfDay(directory.zone()).toInstant();var hi=first.plusDays(7).atStartOfDay(directory.zone()).toInstant();
        return tx.execute(c->{
            var body=cn.adc.shared.audit.AuditDocuments.JSON.createObjectNode().put("clinicId",Long.toString(clinic)).put("timeZone",directory.zone().getId()).put("coverage","COMPLETE").put("reservesCapacity",false);
            var windows=body.putArray("windows");
            try(var p=c.prepareStatement("SELECT schedule_id,schedule_version_no,dentist_id,starts_at,ends_at FROM adc.reception_schedule_version WHERE clinic_id=? AND activation_status='EFFECTIVE' AND starts_at<? AND ?<ends_at ORDER BY starts_at,dentist_id,schedule_id")){
                p.setLong(1,clinic);p.setObject(2,hi.atOffset(ZoneOffset.UTC));p.setObject(3,lo.atOffset(ZoneOffset.UTC));
                try(var r=p.executeQuery()){while(r.next())windows.addObject().put("scheduleId",Long.toString(r.getLong(1))).put("versionNo",r.getInt(2)).put("dentistId",Long.toString(r.getLong(3))).put("clinicId",Long.toString(clinic)).put("startsAt",r.getObject(4,OffsetDateTime.class).toString()).put("endsAt",r.getObject(5,OffsetDateTime.class).toString());}
            }
            var busy=body.putArray("busy");
            try(var p=c.prepareStatement("""
              SELECT v.dentist_id,a.starts_at,a.ends_at,a.appointment_id,v.clinic_id,a.appointment_status,
                CASE WHEN a.appointment_status='已取消' THEN a.cancellation_verification WHEN a.appointment_status='已确认未到诊' THEN a.no_show_verification END,
                EXISTS(SELECT 1 FROM adc.visit f WHERE f.appointment_id=a.appointment_id)
              FROM adc.appointment a JOIN adc.reception_schedule_version v
                ON (v.schedule_id,v.schedule_version_no)=(a.schedule_id,a.schedule_version_no)
              WHERE a.starts_at<? AND ?<a.ends_at AND EXISTS(
                SELECT 1 FROM adc.reception_schedule_version w WHERE w.dentist_id=v.dentist_id
                  AND w.clinic_id=? AND w.activation_status='EFFECTIVE' AND w.starts_at<? AND ?<w.ends_at)
              ORDER BY a.starts_at,a.appointment_id
              """)){
                p.setObject(1,hi.atOffset(ZoneOffset.UTC));p.setObject(2,lo.atOffset(ZoneOffset.UTC));p.setLong(3,clinic);p.setObject(4,hi.atOffset(ZoneOffset.UTC));p.setObject(5,lo.atOffset(ZoneOffset.UTC));
                try(var r=p.executeQuery()){while(r.next()){
                    var end=r.getObject(3,OffsetDateTime.class);var overlap=new AppointmentRepository.Overlap(r.getLong(4),r.getLong(5),r.getString(6),r.getString(7),end.toInstant(),r.getBoolean(8));
                    if(FormalBookingService.released(c,overlap,release,clock))continue;
                    boolean local=r.getLong(5)==clinic;
                    var item=busy.addObject().put("kind","APPOINTMENT").put("dentistId",Long.toString(r.getLong(1))).put("startsAt",r.getObject(2,OffsetDateTime.class).toString()).put("endsAt",end.toString()).put("statusLabel",local?"已预约":"跨店占用");
                    if(local)item.put("appointmentId",Long.toString(r.getLong(4))).put("clinicId",Long.toString(clinic));
                }}
            }
            requireActor(actor,clinic);return body;
        });
    }
    private void requireActor(String actor,long clinic){if(!actor.equals(auth.require("UC02",Set.of(clinic)).actor()))throw new SecurityException("REQUEST_ACTOR_CHANGED");}
}
