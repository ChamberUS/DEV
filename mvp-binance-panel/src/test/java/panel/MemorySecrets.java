package panel;

import java.util.*;
import panel.security.SecretStore;

final class MemorySecrets implements SecretStore {
    final Map<String,char[]> values=new HashMap<>();
    boolean unavailable;
    public Optional<char[]> read(String name){if(unavailable)throw new Unavailable();return Optional.ofNullable(values.get(name)).map(char[]::clone);}
    public void write(String name,char[] value){if(unavailable)throw new Unavailable();values.put(name,value.clone());}
    public void delete(String name){values.remove(name);}
    public String toString(){return "MemorySecrets[redacted]";}
}
