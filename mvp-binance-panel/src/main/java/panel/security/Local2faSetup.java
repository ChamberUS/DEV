package panel.security;

import java.io.Console;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

public final class Local2faSetup {
    public static void main(String[] args) {
        Console console=System.console();
        if(console==null){System.err.println("Run setup-local-2fa.sh in an interactive terminal.");System.exit(1);}
        char[] resend=null,twilio=null;
        try {
            resend=console.readPassword("Resend API Key: ");
            String account=console.readLine("Twilio Account SID: ");
            String key=console.readLine("Twilio API Key SID: ");
            twilio=console.readPassword("Twilio API Secret: ");
            String service=console.readLine("Twilio Verify Service SID (leave blank if not created): ");
            String from=console.readLine("Resend From Address [onboarding@resend.dev]: ");
            if(resend==null||twilio==null||account==null||key==null||service==null||from==null)throw new IllegalArgumentException();
            account=account.trim();key=key.trim();service=service.trim();from=from.isBlank()?"onboarding@resend.dev":from.trim();
            if(resend.length==0||twilio.length==0||!ProviderConfig.sid(account,"AC")||!ProviderConfig.sid(key,"SK")
                    ||(!service.isEmpty()&&!ProviderConfig.sid(service,"VA"))||!from.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))throw new IllegalArgumentException();
            SecretStore store=new MacOsKeychainSecretStore(true);
            store.write(SecretStore.RESEND,resend);store.write(SecretStore.TWILIO,twilio);
            Path dir=ProviderConfig.FILE.getParent();Files.createDirectories(dir);Files.setPosixFilePermissions(dir,PosixFilePermissions.fromString("rwx------"));
            if(Files.isSymbolicLink(ProviderConfig.FILE))throw new IllegalStateException();
            if(!Files.exists(ProviderConfig.FILE))Files.createFile(ProviderConfig.FILE,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.setPosixFilePermissions(ProviderConfig.FILE,PosixFilePermissions.fromString("rw-------"));
            Properties config=new Properties();config.setProperty("twilio.accountSid",account);config.setProperty("twilio.apiKeySid",key);
            config.setProperty("twilio.verifyServiceSid",service);config.setProperty("resend.fromAddress",from);
            try(var out=Files.newOutputStream(ProviderConfig.FILE)){config.store(out,"Non-secret local provider configuration");}
            Path securityFile=dir.resolve("security.properties");
            if(Files.isSymbolicLink(securityFile))throw new IllegalStateException();
            Properties security=new Properties();
            if(Files.exists(securityFile)){try(var in=Files.newInputStream(securityFile)){security.load(in);}}
            else Files.createFile(securityFile,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.setPosixFilePermissions(securityFile,PosixFilePermissions.fromString("rw-------"));
            security.setProperty("security.dev.mode","false");
            security.remove("security.admin.trustedIpv6");
            try(var out=Files.newOutputStream(securityFile)){security.store(out,"Local security configuration");}
            console.printf("Setup saved. Secrets are in macOS Keychain. Restart the app.\n");
            if(service.isEmpty())console.printf("Twilio Verify: NOT CONFIGURED — Missing Verify Service SID\n");
        } catch(Exception|LinkageError e) {console.printf("Setup could not complete. Check input and macOS Keychain access. No secrets were printed.\n");System.exit(1);}
        finally {if(resend!=null)Arrays.fill(resend,'\0');if(twilio!=null)Arrays.fill(twilio,'\0');}
    }
}
