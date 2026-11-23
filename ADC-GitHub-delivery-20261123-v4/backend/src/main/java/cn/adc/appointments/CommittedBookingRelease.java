package cn.adc.appointments;
import java.sql.Connection;import java.sql.SQLException;
/** Uses the existing UC04/UC06 committed fact verifiers; status text alone never releases capacity. */
final class CommittedBookingRelease {
    static boolean hasVisit(Connection c,long appointment)throws SQLException {
        try(var p=c.prepareStatement("SELECT 1 FROM adc.visit WHERE appointment_id=?")){p.setLong(1,appointment);try(var r=p.executeQuery()){return r.next();}}
    }
    static boolean confirms(Connection c,AppointmentRepository.Overlap overlap)throws SQLException {
        return switch(overlap.status()){
            case "已取消" -> AppointmentCancellationService.confirmsCommittedRelease(c,overlap);
            case "已确认未到诊" -> ArrivalNoShowService.confirmsCommittedRelease(c,overlap);
            default -> false;
        };
    }
}
