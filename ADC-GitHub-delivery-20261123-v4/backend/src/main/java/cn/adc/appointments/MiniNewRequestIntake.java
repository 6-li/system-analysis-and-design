package cn.adc.appointments;

import cn.adc.shared.audit.AuditDocuments;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;

/** Preliminary, unbound applicant intake. The persistence adapter owns receipt and audit atomicity. */
final class MiniNewRequestIntake {
    record Receipt(String payloadHash, String requestId) {}

    interface Store {
        Receipt saveOnce(String account, String key, String payloadHash, JsonNode input);
        default Receipt saveChange(String account, String key, String payloadHash, JsonNode input) {
            throw new SecurityException("MINI_CHANGE_REQUEST_UNAVAILABLE");
        }
        default Receipt saveCancel(String account, String key, String payloadHash, JsonNode input) {
            throw new SecurityException("MINI_CANCEL_REQUEST_UNAVAILABLE");
        }
        Optional<Receipt> find(String account, String key);
    }

    private static final Set<String> REQUEST_FIELDS = Set.of("type", "actingMode", "identity", "intendedClinicId",
            "preferred", "treatmentNeed", "bookingIntent");
    private final MiniRequestSession sessions;
    private final Store store;
    @FunctionalInterface interface SelectionReview {void requireAvailable(String bearer,JsonNode input);}
    private final SelectionReview selectionReview;

    MiniNewRequestIntake(MiniRequestSession sessions, Store store) {
        this(sessions,store,null);
    }
    MiniNewRequestIntake(MiniRequestSession sessions, Store store,SelectionReview selectionReview) {
        this.sessions = java.util.Objects.requireNonNull(sessions);
        this.store = java.util.Objects.requireNonNull(store);
        this.selectionReview=selectionReview;
    }

    JsonNode submit(String authorization, String key, JsonNode input) {
        var account = sessions.account(authorization);
        requireKey(key);
        var snapshot = input.deepCopy();
        var type = snapshot.path("type").asText();
        if ("CHANGE".equals(type) || "CANCEL".equals(type)) validateExistingAppointmentRequest(snapshot, type);
        else validate(snapshot);
        String hash = AuditDocuments.hash(snapshot);
        if(snapshot.has("selectedScheme")){
            var existing=store.find(account,key);
            if(existing.isPresent()){
                if(!hash.equals(existing.orElseThrow().payloadHash()))throw new IllegalArgumentException("MINI_SUBMISSION_CONFLICT");
                return received(key,existing.orElseThrow());
            }
            if(selectionReview==null)throw new IllegalStateException("MINI_SELECTION_REVIEW_UNAVAILABLE");
            selectionReview.requireAvailable(authorization,snapshot);
        }
        var receipt = switch (type) {
            case "CHANGE" -> store.saveChange(account, key, hash, snapshot);
            case "CANCEL" -> store.saveCancel(account, key, hash, snapshot);
            default -> store.saveOnce(account, key, hash, snapshot);
        };
        if (!hash.equals(receipt.payloadHash())) throw new IllegalArgumentException("MINI_SUBMISSION_CONFLICT");
        return received(key, receipt);
    }

    JsonNode recover(String authorization, String key, String operation) {
        var account = sessions.account(authorization);
        requireKey(key);
        if (!"op008".equals(operation)) throw new IllegalArgumentException("MINI_OPERATION_REQUIRED");
        return store.find(account, key).map(receipt -> received(key, receipt))
                .orElseGet(() -> envelope(key).put("receiptStatus", "UNKNOWN").put("message", "接收结果待核对"));
    }

    private static void validate(JsonNode input) {
        var allowed=new java.util.HashSet<>(REQUEST_FIELDS);allowed.add("selectedScheme");allowed.add("patientConfirmed");
        if (!input.isObject() || !input.propertyNames().containsAll(REQUEST_FIELDS) || !allowed.containsAll(input.propertyNames())) {
            throw new IllegalArgumentException("MINI_PRELIMINARY_FIELDS_REQUIRED");
        }
        if (!"NEW".equals(AuditDocuments.text(input, "type")) || !"SELF".equals(AuditDocuments.text(input, "actingMode"))) {
            throw new IllegalArgumentException("MINI_PRELIMINARY_SELF_ONLY");
        }
        AuditDocuments.text(input, "intendedClinicId");
        AuditDocuments.text(input, "treatmentNeed");
        validateIdentityAndPreference(input);
        MiniSelectedScheme.validate(input);
        var intent = input.get("bookingIntent");
        AuditDocuments.fields(intent, "visitType");
        if (!Set.of("INITIAL", "FOLLOW_UP").contains(AuditDocuments.text(intent, "visitType"))) {
            throw new IllegalArgumentException("MINI_VISIT_TYPE_INVALID");
        }
    }

    private static void validateExistingAppointmentRequest(JsonNode input, String type) {
        boolean change = "CHANGE".equals(type);
        var required = new java.util.HashSet<>(Set.of("type", "actingMode", "identity", "patientId", "originalAppointmentId"));
        if (change) required.add("preferred");
        var reason = change ? "changeReason" : "cancellationReason";
        if (!input.propertyNames().containsAll(required)
                || input.propertyNames().stream().anyMatch(field -> !required.contains(field) && !reason.equals(field) && !(change && Set.of("selectedScheme","patientConfirmed","expectedAppointmentToken").contains(field)))
                || !"SELF".equals(input.path("actingMode").asText())) {
            throw new IllegalArgumentException("MINI_APPOINTMENT_REQUEST_SELF_FIELDS_REQUIRED");
        }
        for (var field : Set.of("patientId", "originalAppointmentId")) {
            long id = InitialBookingReview.id(input, field);
            if (id <= 0 || !Long.toString(id).equals(AuditDocuments.text(input, field))) {
                throw new IllegalArgumentException("MINI_CHANGE_ID_INVALID");
            }
        }
        if (input.has(reason)) AuditDocuments.text(input, reason);
        validateIdentity(input);
        if (change) {validatePreference(input);if(input.has("selectedScheme"))MiniChangeSelection.validate(input);}
    }

    private static void validateIdentityAndPreference(JsonNode input) {
        validateIdentity(input);
        validatePreference(input);
    }

    private static void validateIdentity(JsonNode input) {
        var identity = input.get("identity");
        AuditDocuments.fields(identity, "patientIdentification", "applicantInformation", "contactInformation");
        for (var field : identity.propertyNames()) AuditDocuments.text(identity, field);
    }

    private static void validatePreference(JsonNode input) {
        var preferred = input.get("preferred");
        if (!preferred.isObject() || preferred.propertyNames().isEmpty()
                || !Set.of("date", "periodDescription").containsAll(preferred.propertyNames())) {
            throw new IllegalArgumentException("MINI_PREFERENCE_REQUIRED");
        }
        if (preferred.has("date")) {
            try { LocalDate.parse(AuditDocuments.text(preferred, "date")); }
            catch (java.time.DateTimeException invalid) { throw new IllegalArgumentException("MINI_DATE_INVALID", invalid); }
        }
        if (preferred.has("periodDescription")) AuditDocuments.text(preferred, "periodDescription");
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("MINI_SUBMISSION_KEY_REQUIRED");
    }

    private static JsonNode received(String key, Receipt receipt) {
        if (receipt.requestId() == null || receipt.requestId().isBlank()) throw new IllegalStateException("MINI_RECEIPT_UNCONFIRMED");
        return envelope(key).put("requestId", receipt.requestId()).put("receiptStatus", "CONFIRMED_RECEIVED")
                .put("message", "已接收预约需求");
    }

    private static tools.jackson.databind.node.ObjectNode envelope(String key) {
        return AuditDocuments.JSON.createObjectNode().put("operation", "op008").put("submissionKey", key);
    }
}
