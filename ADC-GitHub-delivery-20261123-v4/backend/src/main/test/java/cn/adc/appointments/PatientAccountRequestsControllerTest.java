package cn.adc.appointments;

import cn.adc.shared.db.JdbcTransactions;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.time.Clock;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PatientAccountRequestsControllerTest {
    @Test void missingSessionCannotReachDatabaseAndErrorsAreNotCached() {
        var source=mock(DataSource.class);
        var sessions=new MiniRequestSession(code->Optional.empty(),
                new WeChatCourseSessionConfiguration.ExpiringSessions(Clock.systemUTC()));
        var controller=new PatientAccountRequestsController(sessions,new JdbcTransactions(source));
        assertThrows(SecurityException.class,()->controller.list(null,null));
        verifyNoInteractions(source);
        assertEquals(401,controller.denied().getStatusCode().value());
        assertEquals("no-store",controller.denied().getHeaders().getCacheControl());
    }
}
