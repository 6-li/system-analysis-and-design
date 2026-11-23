package cn.adc.appointments;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

class CourseMiniReadConfigurationTest {
    @Test void usesOnlyExplicitCurrentGrantsAndObservesRevocationAndBrokenDocuments(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("synthetic-grants.json");
        Files.writeString(file, """
                {"schemaVersion":1,"grants":[{"account":"SYNTHETIC-course-mini","authorizationId":"TEST-self",
                 "patientId":"100000","kind":"SELF","permissions":["APPOINTMENT_READ"],"version":1},
                 {"account":"SYNTHETIC-guardian","authorizationId":"TEST-guardian","patientId":"100000",
                  "kind":"GUARDIAN","permissions":["APPOINTMENT_READ","VISIT_HISTORY_READ"],"version":2}]}
                """);
        var scope = new CourseMiniReadConfiguration.FileScope(file);
        assertTrue(scope.mayRead(null, "SYNTHETIC-course-mini", 100000));
        assertFalse(scope.mayRead(null, "SYNTHETIC-other", 100000));
        assertFalse(scope.mayRead(null, "SYNTHETIC-course-mini", 100001));
        assertEquals(MiniAppointmentReadController.Authorization.Kind.GUARDIAN,
                scope.authorizations(null, "SYNTHETIC-guardian").getFirst().kind());
        assertFalse(scope.authorizations(null, "SYNTHETIC-course-mini").getFirst().permissions().contains("VISIT_HISTORY_READ"));
        Files.writeString(file, "{\"schemaVersion\":1,\"grants\":[]}");
        assertFalse(scope.mayRead(null, "SYNTHETIC-course-mini", 100000));
        Files.writeString(file, "malformed synthetic document");
        assertEquals("58000", assertThrows(SQLException.class,
                () -> scope.mayRead(null, "SYNTHETIC-course-mini", 100000)).getSQLState());
    }
}
