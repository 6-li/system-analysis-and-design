package cn.adc.quarterly;
import cn.adc.shared.db.JdbcTransactions;
import cn.adc.shared.security.StaffAuthorization;
import javax.sql.DataSource;
import java.sql.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class QuarterlyRecordsServiceTest {
    static StaffAuthorization auth(String actor,String role,Set<String> actions,Set<Long> scope){return new StaffAuthorization(()->Optional.of(actor),()->Map.of(actor,new StaffAuthorization.Grant("TEST",Set.of(role),actions,scope,false)));}
    static ResultSet query(Connection c,String fragment,boolean... values)throws Exception{
        var p=mock(PreparedStatement.class);var r=mock(ResultSet.class);when(c.prepareStatement(contains(fragment))).thenReturn(p);when(p.executeQuery()).thenReturn(r);
        if(values.length>0){Boolean[] rest=new Boolean[values.length-1];for(int i=1;i<values.length;i++)rest[i-1]=values[i];when(r.next()).thenReturn(values[0],rest);}return r;
    }
    @Test void recordsRespectExistingRoleAndWholeClinicScopeBeforeOpeningDatabase(){
        var ds=mock(DataSource.class);var tx=new JdbcTransactions(ds);
        var finance=new QuarterlyRecordsService(tx,auth("TEST finance","HQ_FINANCE",Set.of("UC20"),Set.of(81001L,81002L)));
        assertEquals("UC20",finance.action(false,Set.of(81001L,81002L)));
        assertThrows(SecurityException.class,()->finance.preview(1,Set.of(81001L)));
        var operations=new QuarterlyRecordsService(tx,auth("TEST operations","HQ_OPERATIONS",Set.of("UC17","UC19"),Set.of(81001L)));
        assertEquals("UC17",operations.action(true,Set.of(81001L)));
        assertThrows(SecurityException.class,()->operations.list(true,"2026-Q4",Set.of(81001L,81002L),Long.MAX_VALUE,20));
        verifyNoInteractions(ds);
    }
    @Test void previewHistoryUsesExactScopeAndStableBoundedPagination()throws Exception{
        var c=mock(Connection.class);var r=query(c,"SELECT s.statistic_id",true,true,false);when(r.getLong(1)).thenReturn(42L,41L);when(r.getString(2)).thenReturn("达到汇总要求");when(r.getBigDecimal(3)).thenReturn(new BigDecimal("330.00"));
        var result=QuarterlyRecordsService.list(c,true,"2026-Q4",Set.of(81002L,81001L),100L,1);
        assertEquals(1,result.path("items").size());assertEquals("42",result.path("nextBeforeId").asText());assertEquals("330.00",result.path("items").get(0).path("receivable").asText());assertEquals("81001",result.path("clinics").get(0).asText());
        var sql=org.mockito.ArgumentCaptor.forClass(String.class);verify(c).prepareStatement(sql.capture());assertTrue(sql.getValue().contains("count(*)"));assertTrue(sql.getValue().contains("NOT IN (?,?)"));assertTrue(sql.getValue().contains("ORDER BY s.statistic_id DESC LIMIT ?"));
        var p=c.prepareStatement(sql.getValue());verify(p).setLong(2,100L);verify(p).setInt(3,2);verify(p).setLong(4,81001L);verify(p).setLong(5,81002L);verify(p).setInt(6,2);
    }
    @Test void latestCalculationDirectoryUsesSameVersionFinancialReview()throws Exception{
        var c=mock(Connection.class);var r=query(c,"SELECT v.calculation_id",true,false);when(r.getLong(1)).thenReturn(77L);when(r.getInt(2)).thenReturn(2);when(r.getLong(3)).thenReturn(42L);when(r.getBoolean(8)).thenReturn(true);when(r.getBigDecimal(5)).thenReturn(new BigDecimal("315.00"));
        var result=QuarterlyRecordsService.list(c,false,"2026-Q4",Set.of(81001L),Long.MAX_VALUE,20);
        assertTrue(result.path("items").get(0).path("reviewed").asBoolean());assertEquals(2,result.path("items").get(0).path("version").asInt());
        var sql=org.mockito.ArgumentCaptor.forClass(String.class);verify(c).prepareStatement(sql.capture());assertTrue(sql.getValue().contains("newer.calculation_version_no>v.calculation_version_no"));assertTrue(sql.getValue().contains("r.calculation_version_no=v.calculation_version_no"));
    }
    @Test void frozenPreviewCanBeFoundWithoutOriginalBrowserReceipt()throws Exception{
        var c=mock(Connection.class);var r=query(c,"SELECT quarter_code,aggregation_readiness,receivable_amount",true,false);when(r.getString(1)).thenReturn("2026-Q4");when(r.getString(13)).thenReturn("{\"rules\":\"TEST frozen\"}");when(r.getBigDecimal(3)).thenReturn(new BigDecimal("330.00"));
        var performance=query(c,"SELECT dentist_id,performance_amount FROM adc.quarterly_dentist_performance_stat",true,false);when(performance.getLong(1)).thenReturn(79001L);when(performance.getBigDecimal(2)).thenReturn(new BigDecimal("330.00"));query(c,"SELECT issue_id,clinic_id",false);
        var result=QuarterlyRecordsService.preview(c,42,Set.of(81001L,81002L));assertEquals("42",result.path("statisticId").asText());assertEquals("330.00",result.path("performance").get(0).path("amount").asText());assertEquals(0,result.path("issues").size());
    }
    @Test void calculationDetailPreservesRequestedVersionAndItsOwnScope()throws Exception{
        var c=mock(Connection.class);var r=query(c,"SELECT statistic_id,calculation_basis",true,false);when(r.getLong(1)).thenReturn(42L);when(r.getString(2)).thenReturn("{\"quarter\":\"2026-Q4\"}");when(r.getBigDecimal(3)).thenReturn(new BigDecimal("315.00"));
        var scope=query(c,"SELECT clinic_id FROM adc.quarterly_statistic_clinic",true,false);when(scope.getLong(1)).thenReturn(81001L);
        var performance=query(c,"SELECT dentist_id,performance_amount FROM adc.quarterly_dentist_performance_calc",true,false);when(performance.getLong(1)).thenReturn(79001L);when(performance.getBigDecimal(2)).thenReturn(new BigDecimal("330.00"));
        var versions=query(c,"SELECT v.calculation_version_no,v.generated_at",true,true,false);when(versions.getInt(1)).thenReturn(2,1);
        var result=QuarterlyRecordsService.calculation(c,77,1,Set.of(81001L));assertEquals(1,result.path("version").asInt());assertEquals(2,result.path("versions").size());assertEquals("330.00",result.path("performance").get(0).path("amount").asText());
    }
    @Test void historyQueryRunsInReadOnlyTransactionAndDistinguishesEmptyFromFailure()throws Exception{
        var ds=mock(DataSource.class);var c=mock(Connection.class);when(ds.getConnection()).thenReturn(c);var statement=mock(Statement.class);when(c.createStatement()).thenReturn(statement);query(c,"SELECT s.statistic_id",false);
        var service=new QuarterlyRecordsService(new JdbcTransactions(ds),auth("TEST operations","HQ_OPERATIONS",Set.of("UC17"),Set.of(81001L)));
        assertEquals(0,service.list(true,"2026-Q4",Set.of(81001L),Long.MAX_VALUE,20).path("items").size());verify(statement).execute("SET TRANSACTION READ ONLY");verify(c).commit();
        assertThrows(IllegalArgumentException.class,()->service.list(true,"2026-Q4",Set.of(81001L),Long.MAX_VALUE,51));
    }
}
