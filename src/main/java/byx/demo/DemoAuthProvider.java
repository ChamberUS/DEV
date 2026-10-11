package byx.demo;
import java.util.Arrays;
/** Deliberately PUBLIC demo credentials. No backend or production authorization capability. */
public final class DemoAuthProvider {
 public record Session(String profile,String role){public Session{if(!profile.equals("DEMO_USER")||!role.equals("USER"))throw new IllegalArgumentException();}}
 private Session session;
 public boolean login(String user,char[] password){try{boolean ok="teste-byx".equals(user)&&Arrays.equals(password,"byx123123".toCharArray());session=ok?new Session("DEMO_USER","USER"):null;return ok;}finally{Arrays.fill(password,'\0');}}
 public Session session(){return session;} public void logout(){session=null;}
}