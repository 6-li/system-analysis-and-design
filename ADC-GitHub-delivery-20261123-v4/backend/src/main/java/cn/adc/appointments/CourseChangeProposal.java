package cn.adc.appointments;

import cn.adc.shared.audit.AuditDocuments;
import cn.adc.shared.audit.CommandRecovery.Rejection;
import cn.adc.shared.resource.ResourceLockCoordinator.Version;
import cn.adc.duration.DurationPolicyService.Basis;
import cn.adc.duration.DurationRepository;
import cn.adc.shared.config.*;
import tools.jackson.databind.JsonNode;
import java.sql.*;
import java.time.*;

/** Non-reserving preview only; AppointmentChangeService independently revalidates the complete write. */
final class CourseChangeProposal {
    static InitialBookingReview.AcceptedScheme require(Connection c,String source,long patient,JsonNode scheme,
        ClinicDirectoryProvider clinics)throws SQLException {
        CourseChangeSource.require(c,source,patient,null);
        var ref=CourseMiniBookingContext.parseExisting(source);
        long appointment;
        try(var p=c.prepareStatement("SELECT submitted_content->>'originalAppointmentId' FROM adc_mini.preliminary_request WHERE request_id=?")){
            p.setObject(1,ref.requestId());try(var r=p.executeQuery()){if(!r.next())throw new Rejection("MINI_CHANGE_SOURCE_STALE");appointment=Long.parseLong(r.getString(1));}
        }
        var repo=new AppointmentRepository();
        var original=repo.findAppointment(c,appointment,81001).orElseThrow(()->new Rejection("STALE_APPOINTMENT"));
        if(!"待到诊".equals(original.status())||original.cancellation()!=null||original.noShow()!=null||repo.hasVisit(c,original))throw new Rejection("APPOINTMENT_NOT_CHANGEABLE");
        var old=AppointmentEvidenceCodec.readStructured(original.terms().treatmentRequest()).basis();
        var input=scheme.path("durationBasis");
        var basis=new Basis(InitialBookingReview.id(input,"primaryProjectId"),input.path("projectVersion").asInt(),input.path("adoptedDurationMinutes").asInt());
        if(old.projectId()==basis.projectId()){
            if(!"PRESERVED_ORIGINAL".equals(input.path("basisMode").asText())||!old.equals(basis))throw new Rejection("INVALID_DURATION_BASIS");
        }else if(!"CHANGED_PROJECT_CURRENT".equals(input.path("basisMode").asText())||!basis.equals(InitialBookingReview.currentBasis(new DurationRepository().current(c,basis.projectId()),basis.projectId())))throw new Rejection("INVALID_DURATION_BASIS");
        var targetRef=scheme.path("effectiveScheduleRef");
        var version=new Version(InitialBookingReview.id(targetRef,"scheduleId"),targetRef.path("versionNo").asInt());
        var target=InitialBookingReview.schedule(c,version);
        if(target.clinic()!=81001||!"EFFECTIVE".equals(target.status()))throw new Rejection("EFFECTIVE_SCHEDULE_REQUIRED");
        var interval=InitialBookingReview.interval(clinics,AuditDocuments.text(scheme,"startsAt"),AuditDocuments.text(scheme,"endsAt"),target,basis.minutes());
        var plan=new InitialBookingReview.AcceptedScheme(patient,version,81001,target.dentist(),interval.start(),interval.end(),AuditDocuments.text(scheme,"treatmentRequest"),basis);
        for(var overlap:repo.overlaps(c,target.dentist(),plan.startsAt(),plan.endsAt()))
            if(overlap.appointmentId()!=appointment&&!FormalBookingService.released(c,overlap,CommittedBookingRelease::confirms,Clock.systemUTC()))throw new Rejection("APPOINTMENT_CAPACITY_CONFLICT");
        return plan;
    }
}
