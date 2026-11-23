package cn.adc.appointments;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.List;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class ConsentProjectScopesTest{
 ConsentProjectScopes scopes()throws Exception{return new ConsentProjectScopes(new CourseClinicalCatalog(Path.of("../tools/deploy/course-clinical-catalog-v2.json").toAbsolutePath()));}
 @Test void explicitMultiProjectSelectionHasStableOrderAndHash()throws Exception{var s=scopes();var a=s.selected(List.of("COURSE_EXAM","COURSE_CLEANING"),s.catalog.version());var b=s.selected(List.of("COURSE_CLEANING","COURSE_EXAM"),s.catalog.version());assertEquals(a,b);assertEquals(2,a.size());assertEquals(ConsentProjectScopes.hash(a),ConsentProjectScopes.hash(b));assertNotEquals(ConsentProjectScopes.hash(a),ConsentProjectScopes.hash(s.selected(List.of("COURSE_EXAM"),s.catalog.version())));}
 @Test void unknownDuplicateEmptyAndStaleCatalogAreRejected()throws Exception{var s=scopes();for(var keys:List.of(List.<String>of(),List.of("COURSE_EXAM","COURSE_EXAM"),List.of("UNKNOWN")))assertThrows(IllegalArgumentException.class,()->s.selected(keys,s.catalog.version()));assertThrows(IllegalArgumentException.class,()->s.selected(List.of("COURSE_EXAM"),"old"));}
 @Test void agreedProjectAndExactUnitRequiredWithoutComparingFreeText()throws Exception{var s=scopes();var c=mock(Connection.class);var p=mock(PreparedStatement.class);var r=mock(ResultSet.class);when(c.prepareStatement(anyString())).thenReturn(p);when(p.executeQuery()).thenReturn(r);when(r.next()).thenReturn(true);when(r.getString(1)).thenReturn("同意");when(r.getString(2)).thenReturn("次");assertDoesNotThrow(()->s.require(c,1,2,"COURSE_CLEANING",true,"次"));assertThrows(cn.adc.shared.audit.CommandRecovery.Rejection.class,()->s.require(c,1,2,"COURSE_CLEANING",true,"颗"));when(r.getString(1)).thenReturn("拒绝");assertThrows(cn.adc.shared.audit.CommandRecovery.Rejection.class,()->s.require(c,1,2,"COURSE_CLEANING",true,"次"));assertDoesNotThrow(()->s.require(c,1,2,"COURSE_CLEANING",false,null));}
 @Test void missingExplicitScopeIsNotWidened()throws Exception{var s=scopes();var c=mock(Connection.class);var p=mock(PreparedStatement.class);var r=mock(ResultSet.class);when(c.prepareStatement(anyString())).thenReturn(p);when(p.executeQuery()).thenReturn(r);when(r.next()).thenReturn(false);assertThrows(cn.adc.shared.audit.CommandRecovery.Rejection.class,()->s.require(c,1,2,"COURSE_EXAM",true,"次"));}
}
