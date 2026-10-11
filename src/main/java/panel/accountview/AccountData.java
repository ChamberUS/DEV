package panel.accountview;
/** DEMO presentation preferences only. No user/auth/device/audit/privileged API exists. */
public interface AccountData {
 record Prefs(String motion,String density,boolean animatedIcons,String primaryWorkspace,boolean followSystemMotion){}
 Prefs prefs(); boolean adminSession(); String effectiveMotion(); boolean preferencesEditable(); void savePrefs(Prefs p);
}