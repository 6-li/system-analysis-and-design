package cn.adc.appointments;

import cn.adc.shared.db.JdbcTransactions;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MiniPatientProfileControllerTest {
    @Test void verifiedSelfReadsOnlyItsOwnArchive() throws Exception {
        var f=new Fixture();
        when(f.scope.authorizations(f.c,"SYNTHETIC-account")).thenReturn(List.of(f.grant));
        var p=mock(PreparedStatement.class); var r=mock(ResultSet.class);
        when(f.c.prepareStatement(anyString())).thenReturn(p); when(p.executeQuery()).thenReturn(r);
        when(r.next()).thenReturn(true,false); when(r.getLong(1)).thenReturn(71L);
        when(r.getString(2)).thenReturn("SYNTHETIC 本人"); when(r.getString(3)).thenReturn("SYNTHETIC identity");
        when(r.getString(4)).thenReturn("self@example.invalid");
        f.mvc().perform(get("/api/v1/mini/me").header("Authorization","Bearer SYNTHETIC-token"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.status").value("VERIFIED")).andExpect(jsonPath("$.patientId").value("71"))
                .andExpect(jsonPath("$.patientName").value("SYNTHETIC 本人")).andExpect(jsonPath("$.allergies").doesNotExist());
        verify(p).setLong(1,71L);
    }
    @Test void guardianAndUnlinkedAccountsNeverBecomeSelf() throws Exception {
        var f=new Fixture();
        when(f.scope.authorizations(f.c,"SYNTHETIC-account")).thenReturn(List.of(
                new MiniAppointmentReadController.Authorization("guardian",72,
                        MiniAppointmentReadController.Authorization.Kind.GUARDIAN,List.of(MiniPatientProfileController.PERMISSION),1)));
        f.mvc().perform(get("/api/v1/mini/me").header("Authorization","Bearer SYNTHETIC-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UNLINKED"));
        verify(f.c,never()).prepareStatement(anyString());
    }
    @Test void revokedGrantDiscardsAlreadyReadProfile() throws Exception {
        var f=new Fixture();
        when(f.scope.authorizations(f.c,"SYNTHETIC-account")).thenReturn(List.of(f.grant),List.of());
        var p=mock(PreparedStatement.class); var r=mock(ResultSet.class);
        when(f.c.prepareStatement(anyString())).thenReturn(p); when(p.executeQuery()).thenReturn(r);
        when(r.next()).thenReturn(true,false);
        f.mvc().perform(get("/api/v1/mini/me").header("Authorization","Bearer SYNTHETIC-token"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.patientName").doesNotExist());
    }
    static class Fixture {
        final Connection c=mock(Connection.class);
        final MiniAppointmentReadController.PatientScope scope=mock(MiniAppointmentReadController.PatientScope.class);
        final MiniAppointmentReadController.Authorization grant=new MiniAppointmentReadController.Authorization(
                "self",71,MiniAppointmentReadController.Authorization.Kind.SELF,List.of(MiniPatientProfileController.PERMISSION),1);
        final MiniPatientProfileController controller;
        Fixture() throws Exception {
            var ds=mock(DataSource.class); when(ds.getConnection()).thenReturn(c);
            when(c.createStatement()).thenReturn(mock(Statement.class));
            var sessions=mock(MiniRequestSession.Sessions.class);
            when(sessions.activeAccount("SYNTHETIC-token")).thenReturn(Optional.of("SYNTHETIC-account"));
            controller=new MiniPatientProfileController(new MiniRequestSession(code -> Optional.empty(),sessions),new JdbcTransactions(ds),scope);
        }
        org.springframework.test.web.servlet.MockMvc mvc() { return MockMvcBuilders.standaloneSetup(controller).build(); }
    }
}
