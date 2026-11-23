package cn.adc.scheduling;

import cn.adc.shared.audit.AuditDocuments;
import tools.jackson.databind.node.ArrayNode;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;

/** Both the read view and lock-protected recheck must supply a complete single-arrangement snapshot. */
final class ScheduleStateToken {
    private static final String SCHEME = "uc21-v1";
    private static final DateTimeFormatter MICROS = new DateTimeFormatterBuilder().appendInstant(6).toFormatter();

    record Version(long scheduleId, int versionNo, long clinicId, long dentistId, Instant startsAt,
                   Instant endsAt, String status, String coordination, String qualification,
                   String conflict, String incompleteReason, Instant activatedAt) {}

    static String of(Version basis, Collection<Version> versions) {
        var seen = new HashSet<Integer>();
        var document = AuditDocuments.JSON.createArrayNode();
        document.add(SCHEME).add(Long.toString(basis.scheduleId())).add(basis.versionNo());
        var ordered = document.addArray();
        boolean matched = false;
        for (Version v : versions.stream().sorted(Comparator.comparingInt(Version::versionNo)).toList()) {
            if (v.scheduleId() != basis.scheduleId() || !seen.add(v.versionNo())) {
                throw new IllegalArgumentException("INCONSISTENT_SCHEDULE_SNAPSHOT");
            }
            if (v.equals(basis)) matched = true;
            var fields = ordered.addArray();
            fields.add(Long.toString(v.scheduleId())).add(v.versionNo()).add(Long.toString(v.clinicId()))
                    .add(Long.toString(v.dentistId()));
            addTime(fields, v.startsAt());
            addTime(fields, v.endsAt());
            fields.add(v.status()).add(v.coordination()).add(v.qualification()).add(v.conflict())
                    .add(v.incompleteReason());
            addTime(fields, v.activatedAt());
        }
        if (!matched) throw new IllegalArgumentException("SCHEDULE_BASIS_MISSING_FROM_SNAPSHOT");
        return SCHEME + "." + AuditDocuments.hash(document);
    }

    private static void addTime(ArrayNode fields, Instant value) {
        if (value == null) fields.addNull();
        else {
            if (value.getNano() % 1000 != 0) throw new IllegalArgumentException("TIME_PRECISION_EXCEEDS_MICROSECONDS");
            fields.add(MICROS.format(value));
        }
    }

    private ScheduleStateToken() {}
}
