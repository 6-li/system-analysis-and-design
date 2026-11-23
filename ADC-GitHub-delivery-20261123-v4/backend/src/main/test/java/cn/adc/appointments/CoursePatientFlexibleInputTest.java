package cn.adc.appointments;
import org.junit.jupiter.api.Test;import org.mockito.MockMakers;
import java.sql.*;import static org.mockito.Mockito.*;import static org.junit.jupiter.api.Assertions.*;
import cn.adc.shared.audit.AuditDocuments;
class CoursePatientFlexibleInputTest {
 @Test void normalSyntheticIdentityAndHumanReadableBasisAreMatchedToOriginalRequest()throws Exception{
  var c=mock(Connection.class,withSettings().mockMaker(MockMakers.PROXY));var p=mock(PreparedStatement.class,withSettings().mockMaker(MockMakers.PROXY));var r=mock(ResultSet.class,withSettings().mockMaker(MockMakers.PROXY));
  when(c.prepareStatement(anyString())).thenReturn(p);when(p.executeQuery()).thenReturn(r);when(r.next()).thenReturn(true);
  var input=AuditDocuments.parse("{\"identityInformation\":\"li\",\"source\":{\"requestId\":\"00000000-0000-4000-8000-000000000101\",\"identityMatchBasis\":\"课程模拟：已核对本次申请人与就诊人资料\"}}");
  assertDoesNotThrow(()->CoursePatientIntakeConfiguration.verifyFixtureSource(c,"SYNTHETIC reception",81001,null,input));
  verify(p).setString(3,"li");verify(p).setString(2,"81001");
  when(r.next()).thenReturn(false);assertThrows(SecurityException.class,()->CoursePatientIntakeConfiguration.verifyFixtureSource(c,"SYNTHETIC reception",81001,null,input));
 }
 @Test void wrongRoleStillCannotVerifyEvenWithReadableBasis()throws Exception{
  var c=mock(Connection.class,withSettings().mockMaker(MockMakers.PROXY));var input=AuditDocuments.parse("{\"identityInformation\":\"li\",\"source\":{\"identityMatchBasis\":\"课程模拟核验\"}}");
  assertThrows(SecurityException.class,()->CoursePatientIntakeConfiguration.verifyFixtureSource(c,"SYNTHETIC dentist",81001,null,input));verifyNoInteractions(c);
 }
}
