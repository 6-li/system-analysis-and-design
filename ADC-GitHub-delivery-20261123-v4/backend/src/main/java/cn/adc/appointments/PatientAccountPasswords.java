package cn.adc.appointments;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/** Account credential encoding only. Never grants patient identity or database authority. */
final class PatientAccountPasswords {
    private static final int ITERATIONS=600000;
    private final SecureRandom random=new SecureRandom();

    String hash(char[] password){
        if(!acceptable(password))throw new IllegalArgumentException("ACCOUNT_PASSWORD_LENGTH_REQUIRED");
        byte[] salt=new byte[16];random.nextBytes(salt);
        byte[] result=derive(password,salt);
        try{return "PBKDF2-SHA256$"+ITERATIONS+"$"+Base64.getEncoder().encodeToString(salt)+"$"+Base64.getEncoder().encodeToString(result);}
        finally{Arrays.fill(result,(byte)0);}
    }

    boolean matches(char[] password,String encoded){
        if(!acceptable(password)||encoded==null||encoded.length()>256)return false;
        try{
            String[] parts=encoded.split("\\$",-1);
            if(parts.length!=4||!"PBKDF2-SHA256".equals(parts[0])||!Integer.toString(ITERATIONS).equals(parts[1]))return false;
            byte[] salt=Base64.getDecoder().decode(parts[2]),expected=Base64.getDecoder().decode(parts[3]);
            if(salt.length!=16||expected.length!=32)return false;
            byte[] actual=derive(password,salt);
            try{return MessageDigest.isEqual(actual,expected);}
            finally{Arrays.fill(actual,(byte)0);Arrays.fill(expected,(byte)0);}
        }catch(IllegalArgumentException malformed){return false;}
    }

    private static boolean acceptable(char[] password){return password!=null&&password.length>=8&&password.length<=128;}
    private static byte[] derive(char[] password,byte[] salt){
        var spec=new PBEKeySpec(password,salt,ITERATIONS,256);
        try{return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();}
        catch(java.security.GeneralSecurityException unavailable){throw new IllegalStateException("ACCOUNT_PASSWORD_HASH_UNAVAILABLE");}
        finally{spec.clearPassword();}
    }
}
