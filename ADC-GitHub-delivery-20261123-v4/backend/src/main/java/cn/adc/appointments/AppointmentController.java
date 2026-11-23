package cn.adc.appointments;

import cn.adc.shared.audit.CommandRecovery;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

import static cn.adc.shared.audit.AuditDocuments.JSON;

/** Internal appointment response adapter, deliberately not a registered Spring controller. */
final class AppointmentController {
    record Response(int status, JsonNode body) {}
    private final FormalBookingService service;
    private final AppointmentReadService reads;
    private final AppointmentChangeService changes;
    private final AppointmentCancellationService cancellations;
    private final ArrivalNoShowService attendance;

    AppointmentController(FormalBookingService service) {
        this(service, null);
    }

    AppointmentController(FormalBookingService service, AppointmentReadService reads) {
        this(service, reads, null);
    }

    AppointmentController(FormalBookingService service, AppointmentReadService reads, AppointmentChangeService changes) {
        this(service, reads, changes, null);
    }

    AppointmentController(FormalBookingService service, AppointmentReadService reads, AppointmentChangeService changes,
                          AppointmentCancellationService cancellations) {
        this(service, reads, changes, cancellations, null);
    }

    AppointmentController(FormalBookingService service, AppointmentReadService reads, AppointmentChangeService changes,
                          AppointmentCancellationService cancellations, ArrivalNoShowService attendance) {
        this.service = service;
        this.reads = reads;
        this.changes = changes;
        this.cancellations = cancellations;
        this.attendance = attendance;
    }

    Response attendance(ArrivalNoShowService.Action action, long appointmentId, long clinic, String key, JsonNode input) {
        try {
            if (attendance == null) return error(503, "ATTENDANCE_UNAVAILABLE", null);
            return attendanceResponse(action, attendance.submit(action, appointmentId, clinic, UUID.fromString(key), input));
        } catch (SecurityException e) {
            return error("AUTHENTICATION_REQUIRED".equals(e.getMessage()) ? 401 : 403, "ATTENDANCE_ACCESS_DENIED", null);
        } catch (IllegalArgumentException e) {
            return error(400, "INVALID_ATTENDANCE_INPUT", null);
        }
    }

    static Response attendanceResponse(ArrivalNoShowService.Action action, CommandRecovery.Receipt receipt) {
        return response(receipt, action.uc, action.operation,
                action == ArrivalNoShowService.Action.ARRIVAL ? "已确认到诊。" : "已确认未到诊。", "ATTENDANCE");
    }

    Response cancel(long appointmentId, long clinic, String key, JsonNode input) {
        try {
            if (cancellations == null) return error(503, "CANCELLATION_UNAVAILABLE", null);
            var receipt = cancellations.cancel(appointmentId, clinic, UUID.fromString(key), input);
            return response(receipt, "UC04", AppointmentCancellationService.OPERATION, "预约已取消。", "CANCELLATION");
        } catch (SecurityException e) {
            return error("AUTHENTICATION_REQUIRED".equals(e.getMessage()) ? 401 : 403, "CANCELLATION_ACCESS_DENIED", null);
        } catch (IllegalArgumentException e) {
            return error(400, "INVALID_CANCELLATION_INPUT", null);
        }
    }

    Response change(long appointmentId, long originClinic, long targetClinic, String key, JsonNode input) {
        try {
            if (changes == null) return error(503, "CHANGE_UNAVAILABLE", null);
            return response(changes.change(appointmentId, originClinic, targetClinic, UUID.fromString(key), input), true);
        } catch (SecurityException e) {
            return error("AUTHENTICATION_REQUIRED".equals(e.getMessage()) ? 401 : 403, "CHANGE_ACCESS_DENIED", null);
        } catch (IllegalArgumentException e) {
            return error(400, "INVALID_CHANGE_INPUT", null);
        }
    }

    Response support(long clinic, String appointmentId) {
        return read(clinic, appointmentId, false);
    }

    Response detail(long clinic, String appointmentId) {
        return read(clinic, appointmentId, true);
    }

    private Response read(long clinic, String id, boolean detail) {
        long appointment;
        try {
            if (id == null || !id.matches("-?[0-9]+")) throw new NumberFormatException();
            appointment = Long.parseLong(id);
        } catch (NumberFormatException e) {
            return detail ? error(404, "APPOINTMENT_NOT_FOUND", null) : error(400, "INVALID_APPOINTMENT_ID", null);
        }
        try {
            if (reads == null) return error(503, "APPOINTMENT_READ_UNAVAILABLE", null);
            var data = detail ? reads.detail(clinic, appointment) : reads.support(clinic, appointment);
            return data.map(body -> new Response(200, body)).orElseGet(() -> error(404, "APPOINTMENT_NOT_FOUND", null));
        } catch (SecurityException e) {
            int status = !detail && "AUTHENTICATION_REQUIRED".equals(e.getMessage()) ? 401 : 403;
            return error(status, "APPOINTMENT_READ_DENIED", null);
        } catch (cn.adc.shared.db.JdbcTransactions.Failure e) {
            return error(503, "APPOINTMENT_READ_UNAVAILABLE", null);
        }
    }

    Response create(long clinic, String key, JsonNode input) {
        try {
            return response(service.book(clinic, UUID.fromString(key), input), false);
        } catch (SecurityException e) {
            return error("AUTHENTICATION_REQUIRED".equals(e.getMessage()) ? 401 : 403, "BOOKING_ACCESS_DENIED", null);
        } catch (IllegalArgumentException e) {
            return error(400, "INVALID_BOOKING_INPUT", null);
        }
    }

    static Response response(CommandRecovery.Receipt r, boolean change) {
        return change
                ? response(r, "UC03", "APPOINTMENT_CHANGE", "改约已保存。", "CHANGE")
                : response(r, "UC02", "APPOINTMENT_CREATE", "预约已保存。", "BOOKING");
    }

    private static Response response(CommandRecovery.Receipt r, String uc, String operation, String message, String errorPrefix) {
        boolean change = "UC03".equals(uc);
        boolean update = !"UC02".equals(uc);
        var body = JSON.createObjectNode().put("requestReference", r.requestReference()).put("ucCode", uc)
                .put("operationKind", operation).put("existingResult", r.existingResult())
                .put("auditPersistence", r.auditPersistence().name());
        if ("UNKNOWN".equals(r.businessOutcome())) {
            return new Response(202, body.put("status", "UNKNOWN").put("businessOutcome", "UNKNOWN").put("message", "提交结果待核对，请勿重新提交。"));
        }
        if ("SUCCEEDED".equals(r.businessOutcome())) {
            var appointment = r.result() == null ? "" : r.result().path("appointmentId").asText();
            var changeId = r.result() == null ? "" : r.result().path("appointmentChangeId").asText();
            var finding = r.result() == null ? "" : r.result().path("finding").asText();
            boolean pending = ("UC05".equals(uc) || "UC06".equals(uc)) && "PENDING_VERIFICATION".equals(finding);
            var visitId = r.result() == null ? "" : r.result().path("visitId").asText();
            if (("UC05".equals(uc) && !pending && (!"ARRIVAL_VERIFIED".equals(finding) || !visitId.matches("-?[0-9]+")))
                    || ("UC06".equals(uc) && !pending && !"NO_SHOW_VERIFIED".equals(finding))
                    || (change && !changeId.matches("-?[0-9]+")) || !appointment.matches("-?[0-9]+") || r.successEventId() == null
                    || !r.successEventId().matches("-?[0-9]+")) {
                return new Response(202, body.put("status", "UNKNOWN").put("businessOutcome", "UNKNOWN").put("message", "提交结果待核对，请勿重新提交。"));
            }
            var links = body.putObject("links").put("appointmentId", appointment)
                    .put("originalUcSuccessEventId", r.successEventId());
            if (change) links.put("appointmentChangeId", changeId);
            if ("UC05".equals(uc) && !pending) links.put("visitId", visitId);
            if (pending) return new Response(200, body.put("status", "PERSISTED_INCOMPLETE")
                    .put("businessOutcome", "PENDING_VERIFICATION").put("message", "核实记录已保存。"));
            return new Response(200, body.put("status", "COMPLETED").put("businessOutcome", "SUCCEEDED").put("message", message));
        }
        if ("REJECTED".equals(r.businessOutcome())) {
            var reason = r.reason();
            if (reason == null) return error(422, errorPrefix + "_REJECTED", r.requestReference());
            if (update && "APPOINTMENT_NOT_FOUND".equals(reason)) return error(404, reason, r.requestReference());
            if ((update && "STALE_APPOINTMENT".equals(reason)) || "REQUEST_PAYLOAD_CONFLICT".equals(reason)) return error(409, reason, r.requestReference());
            if (reason.endsWith("PERMISSION_DENIED")) return error(403, reason, r.requestReference());
            var stale = BookingReviewFeedback.forRejectedReview(reason);
            if (stale.isPresent()) {
                var feedback = stale.get();
                return error(feedback.status(), reason, feedback.body().message(), r.requestReference());
            }
            if ("APPOINTMENT_CAPACITY_CONFLICT".equals(reason) || "EFFECTIVE_SCHEDULE_REQUIRED".equals(reason)
                    || "ACCEPTANCE_RECONFIRMATION_REQUIRED".equals(reason) || "CURRENT_DURATION_REQUIRED".equals(reason)) {
                return error(409, reason, r.requestReference());
            }
            return error(422, reason, r.requestReference());
        }
        return error(503, errorPrefix + "_UNAVAILABLE", r.requestReference());
    }

    private static Response error(int status, String code, String reference) {
        return error(status, code, code, reference);
    }

    private static Response error(int status, String code, String message, String reference) {
        var body = JSON.createObjectNode().put("code", code).put("message", message);
        if (reference != null) body.put("requestReference", reference);
        return new Response(status, body);
    }
}
