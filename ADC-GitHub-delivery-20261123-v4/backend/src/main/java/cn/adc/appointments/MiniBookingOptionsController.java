package cn.adc.appointments;

import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.resource.ResourceLockCoordinator.Version;
import cn.adc.duration.DurationPolicyService.Basis;
import cn.adc.shared.audit.AuditDocuments;
import cn.adc.shared.time.StrictInputPolicy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import java.time.*;
import java.util.*;

/** Anonymous occupancy projection. Never exposes other patients and never reserves capacity. */
@RestController
@ConditionalOnProperty(name="adc.mini.booking-options-enabled",havingValue="true")
final class MiniBookingOptionsController {
    interface Directory { ZoneId zone(long clinic); boolean dentist(long clinic,long dentist); }
    private final MiniRequestSession sessions; private final JdbcTransactions tx; private final Directory directory;
    MiniBookingOptionsController(MiniRequestSession sessions,
        @org.springframework.beans.factory.annotation.Qualifier("miniReadTransactions") JdbcTransactions tx,Directory directory){this.sessions=sessions;this.tx=tx;this.directory=directory;}
    @GetMapping("/api/v1/mini/booking-options")
    ResponseEntity<JsonNode> read(@RequestHeader(value="Authorization",required=false)String bearer,
            @RequestParam String clinicId,@RequestParam String date,@RequestParam(required=false)String projectId){
        return options(bearer,clinicId,date,projectId,null);
    }
    @GetMapping("/api/v1/mini/booking-change-options")
    ResponseEntity<JsonNode> change(@RequestHeader(value="Authorization",required=false)String bearer,
        @RequestParam String clinicId,@RequestParam String date,@RequestParam(required=false)String projectId,@RequestParam String originalAppointmentId){
        return options(bearer,clinicId,date,projectId,id(originalAppointmentId));
    }
    private ResponseEntity<JsonNode> options(String bearer,String clinicId,String date,String projectId,Long originalId){
        String account=sessions.account(bearer);long clinic=id(clinicId);var day=LocalDate.parse(date);var zone=directory.zone(clinic);
        var lo=day.atStartOfDay(zone).toInstant();var hi=day.plusDays(1).atStartOfDay(zone).toInstant();
        return response(200,tx.execute(c->{
            var body=AuditDocuments.JSON.createObjectNode().put("clinicId",clinicId).put("date",date).put("timeZone",zone.getId()).put("reservesCapacity",false);
            AppointmentRepository.Appointment original=null;Basis frozen=null;
            if(originalId!=null){
                if(!RequestDecisions.ready(c))throw new SecurityException("CHANGE_SELECTION_NOT_ENABLED");
                original=new AppointmentRepository().findAppointment(c,originalId,clinic).orElseThrow(()->new SecurityException("ORIGINAL_APPOINTMENT_DENIED"));
                if(!new JdbcPatientAccountScope().mayRead(c,account,original.patientId())||!"待到诊".equals(original.status())||new AppointmentRepository().hasVisit(c,original))throw new SecurityException("ORIGINAL_APPOINTMENT_DENIED");
                frozen=AppointmentEvidenceCodec.readStructured(original.terms().treatmentRequest()).basis();
                body.put("expectedAppointmentToken",AppointmentReadService.token(original));
            }
            var dentistList=body.putArray("dentists");
            try(var p=c.prepareStatement("SELECT DISTINCT dentist_id FROM adc.reception_schedule_version WHERE clinic_id=? AND activation_status='EFFECTIVE' ORDER BY dentist_id")){
                p.setLong(1,clinic);try(var r=p.executeQuery()){while(r.next()){long doctor=r.getLong(1);if(!directory.dentist(clinic,doctor))throw new IllegalStateException("DENTIST_DIRECTORY_UNAVAILABLE");dentistList.addObject().put("dentistId",Long.toString(doctor)).put("dentistLabel","牙医 "+doctor);}}
            }
            var projects=body.putArray("projects");Basis basis=null;
            // Explicit projection is essential: row_to_json(t) would require all columns, exceeding this role's five-column grant.
            try(var p=c.prepareStatement("SELECT project_id,duration_version_no,project_name,duration_minutes FROM adc.project_duration_version WHERE publication_status='CURRENT' ORDER BY project_id");var r=p.executeQuery()){
                while(r.next()){long project=r.getLong(1);int version=r.getInt(2),minutes=r.getInt(4);
                    projects.addObject().put("projectId",Long.toString(project)).put("projectName",r.getString(3)).put("projectVersion",version).put("minutes",minutes);
                    if(projectId!=null&&id(projectId)==project)basis=new Basis(project,version,minutes);}
            }
            if(frozen!=null){
                if(projectId!=null&&id(projectId)==frozen.projectId())basis=frozen;
                boolean listed=false;for(var item:projects)if(item.path("projectId").asText().equals(Long.toString(frozen.projectId())))listed=true;
                if(!listed)projects.addObject().put("projectId",Long.toString(frozen.projectId())).put("projectName","原预约项目").put("projectVersion",frozen.versionNo()).put("minutes",frozen.minutes());
            }
            var slots=body.putArray("slots");
            if(projectId!=null){
                if(basis==null)throw new IllegalArgumentException("CURRENT_PROJECT_REQUIRED");
                var windows=new ArrayList<MiniAvailabilityPlanner.Window>();
                try(var p=c.prepareStatement("SELECT schedule_id,schedule_version_no,dentist_id,starts_at,ends_at FROM adc.reception_schedule_version WHERE clinic_id=? AND activation_status='EFFECTIVE' AND starts_at<? AND ?<ends_at ORDER BY starts_at,schedule_id,schedule_version_no")){
                    p.setLong(1,clinic);p.setObject(2,hi.atOffset(ZoneOffset.UTC));p.setObject(3,lo.atOffset(ZoneOffset.UTC));
                    try(var r=p.executeQuery()){while(r.next()){long dentist=r.getLong(3);if(!directory.dentist(clinic,dentist))throw new IllegalStateException("DENTIST_DIRECTORY_UNAVAILABLE");
                        var start=r.getObject(4,OffsetDateTime.class).toInstant();var end=r.getObject(5,OffsetDateTime.class).toInstant();
                        windows.add(new MiniAvailabilityPlanner.Window(new Version(r.getLong(1),r.getInt(2)),clinic,dentist,start.isBefore(lo)?lo:start,end.isAfter(hi)?hi:end,"EFFECTIVE"));}}
                }
                var occupied=new ArrayList<MiniAvailabilityPlanner.Occupancy>();
                try(var p=c.prepareStatement("SELECT v.dentist_id,a.starts_at,a.ends_at,a.appointment_id,v.clinic_id,a.appointment_status,CASE WHEN a.appointment_status='已取消' THEN a.cancellation_verification WHEN a.appointment_status='已确认未到诊' THEN a.no_show_verification END, EXISTS(SELECT 1 FROM adc.visit f WHERE f.appointment_id=a.appointment_id) FROM adc.appointment a JOIN adc.reception_schedule_version v ON (v.schedule_id,v.schedule_version_no)=(a.schedule_id,a.schedule_version_no) WHERE a.starts_at<? AND ?<a.ends_at")){
                    p.setObject(1,hi.atOffset(ZoneOffset.UTC));p.setObject(2,lo.atOffset(ZoneOffset.UTC));try(var r=p.executeQuery()){while(r.next()){
                        if(originalId!=null&&r.getLong(4)==originalId)continue;
                        var start=r.getObject(2,OffsetDateTime.class).toInstant();var end=r.getObject(3,OffsetDateTime.class).toInstant();
                        var overlap=new AppointmentRepository.Overlap(r.getLong(4),r.getLong(5),r.getString(6),r.getString(7),end,r.getBoolean(8));
                        if(!FormalBookingService.released(c,overlap,CommittedBookingRelease::confirms,Clock.systemUTC()))occupied.add(new MiniAvailabilityPlanner.Occupancy(r.getLong(1),start,end));
                    }}
                }
                for(var slot:MiniAvailabilityPlanner.plan(windows,occupied,basis)){
                    var item=slots.addObject().put("dentistId",Long.toString(slot.dentist())).put("dentistLabel","牙医 "+slot.dentist())
                        .put("startsAt",slot.start().atZone(zone).toOffsetDateTime().toString()).put("endsAt",slot.end().atZone(zone).toOffsetDateTime().toString())
                        .put("availabilityStatus",slot.available()?"AVAILABLE":"FULL");
                    item.putObject("effectiveScheduleRef").put("scheduleId",Long.toString(slot.schedule().scheduleId())).put("versionNo",slot.schedule().versionNo());
                    item.putObject("durationBasis").put("schemaVersion",1).put("basisMode",originalId==null?"INITIAL_CURRENT":basis.projectId()==frozen.projectId()?"PRESERVED_ORIGINAL":"CHANGED_PROJECT_CURRENT").put("primaryProjectId",Long.toString(basis.projectId())).put("projectVersion",basis.versionNo()).put("adoptedDurationMinutes",basis.minutes());
                }
            }
            if(!account.equals(sessions.account(bearer)))throw new SecurityException("MINI_SESSION_REQUIRED");return body;
        }));
    }
    private static long id(String value){if(value==null||!value.matches("[1-9][0-9]{0,18}"))throw new IllegalArgumentException();long id=Long.parseLong(value);if(id<=0)throw new IllegalArgumentException();return id;}
    void requireAvailable(String bearer,JsonNode input){
        var selected=input.path("selectedScheme");
        ResponseEntity<JsonNode> reply;
        try{
            if("CHANGE".equals(input.path("type").asText())){
                // The course clinic is server scoped; exclusion is authorized against the bound original appointment.
                reply=options(bearer,"81001",input.path("preferred").path("date").asText(),selected.path("durationBasis").path("primaryProjectId").asText(),id(input.path("originalAppointmentId").asText()));
                if(!reply.getBody().path("expectedAppointmentToken").asText().equals(input.path("expectedAppointmentToken").asText()))throw new cn.adc.shared.audit.CommandRecovery.Rejection("MINI_SELECTED_SLOT_UNAVAILABLE");
            }else reply=read(bearer,input.path("intendedClinicId").asText(),input.path("preferred").path("date").asText(),selected.path("durationBasis").path("primaryProjectId").asText());
        }
        catch(IllegalArgumentException e){if("CURRENT_PROJECT_REQUIRED".equals(e.getMessage()))throw new cn.adc.shared.audit.CommandRecovery.Rejection("MINI_SELECTED_SLOT_UNAVAILABLE");throw e;}
        for(var slot:reply.getBody().path("slots"))if("AVAILABLE".equals(slot.path("availabilityStatus").asText())
                &&slot.path("effectiveScheduleRef").equals(selected.path("effectiveScheduleRef"))
                &&slot.path("durationBasis").equals(selected.path("durationBasis"))
                &&slot.path("startsAt").asText().equals(selected.path("startsAt").asText())
                &&slot.path("endsAt").asText().equals(selected.path("endsAt").asText()))return;
        throw new cn.adc.shared.audit.CommandRecovery.Rejection("MINI_SELECTED_SLOT_UNAVAILABLE");
    }
    @ExceptionHandler(SecurityException.class) ResponseEntity<JsonNode> denied(){return error(401,"MINI_SESSION_REQUIRED");}
    @ExceptionHandler({IllegalArgumentException.class,DateTimeException.class,org.springframework.web.bind.ServletRequestBindingException.class}) ResponseEntity<JsonNode> invalid(){return error(400,"INVALID_BOOKING_QUERY");}
    @ExceptionHandler({JdbcTransactions.Failure.class,IllegalStateException.class}) ResponseEntity<JsonNode> unavailable(){return error(503,"BOOKING_SOURCE_UNAVAILABLE");}
    private static ResponseEntity<JsonNode> error(int status,String code){return response(status,AuditDocuments.JSON.createObjectNode().put("code",code));}
    private static ResponseEntity<JsonNode> response(int status,JsonNode body){return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);}
}
