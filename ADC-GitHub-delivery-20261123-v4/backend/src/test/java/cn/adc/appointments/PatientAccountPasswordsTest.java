package cn.adc.appointments;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PatientAccountPasswordsTest {
    @Test void passwordUsesIndependentSaltAndExactVerification(){
        var passwords=new PatientAccountPasswords();var value="SYNTHETIC-course-password".toCharArray();
        String first=passwords.hash(value),second=passwords.hash(value);
        assertNotEquals(first,second);assertFalse(first.contains("SYNTHETIC-course-password"));
        assertTrue(passwords.matches(value,first));assertFalse(passwords.matches("SYNTHETIC-wrong-password".toCharArray(),first));
        assertTrue(passwords.matches(value,second));
    }
    @Test void invalidEncodingOrUnboundedWorkFactorsAreRejected(){
        var passwords=new PatientAccountPasswords();var value="SYNTHETIC-password".toCharArray();
        for(String encoded:new String[]{"plain text","PBKDF2-SHA256$1$AAAA$AAAA","PBKDF2-SHA256$999999999$AAAA$AAAA","PBKDF2-SHA256$600000$!$!"})assertFalse(passwords.matches(value,encoded));
        assertFalse(passwords.matches(value,null));assertThrows(IllegalArgumentException.class,()->passwords.hash("short".toCharArray()));
        assertThrows(IllegalArgumentException.class,()->passwords.hash(new char[129]));
    }
}
