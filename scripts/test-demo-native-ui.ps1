param([Parameter(Mandatory)][string]$Executable,[Parameter(Mandatory)][string]$Report)
$ErrorActionPreference='Stop';$Executable=[IO.Path]::GetFullPath($Executable)
Add-Type -AssemblyName UIAutomationClient;Add-Type -AssemblyName UIAutomationTypes;Add-Type -AssemblyName System.Windows.Forms
Add-Type -TypeDefinition 'using System;using System.Runtime.InteropServices;public static class DemoQaWindow{[DllImport("user32.dll")]public static extern bool SetForegroundWindow(IntPtr h);[DllImport("user32.dll")]public static extern IntPtr GetForegroundWindow();}'
$checks=[Collections.Generic.List[string]]::new();$launcher=$null;$owned=$null;$failure=$null;$root=$null
function Nodes {return $root.FindAll([Windows.Automation.TreeScope]::Descendants,[Windows.Automation.Condition]::TrueCondition)}
function Named([string]$name){return @(Nodes|Where-Object {$_.Current.Name -eq $name -and $_.Current.ControlType -ne [Windows.Automation.ControlType]::Text})}
function WaitText([string]$pattern){$until=(Get-Date).AddSeconds(15);do{if(@(Nodes|Where-Object {$_.Current.Name -match $pattern}).Count){return};Start-Sleep -Milliseconds 200}while((Get-Date) -lt $until);throw ('UI text not observed: '+$pattern)}
function InvokeName([string]$name){$matches=Named $name;foreach($n in $matches){$pattern=$null;if($n.TryGetCurrentPattern([Windows.Automation.InvokePattern]::Pattern,[ref]$pattern)){$pattern.Invoke();Start-Sleep -Milliseconds 350;return};if($n.TryGetCurrentPattern([Windows.Automation.TogglePattern]::Pattern,[ref]$pattern)){$pattern.Toggle();Start-Sleep -Milliseconds 350;return};if($n.TryGetCurrentPattern([Windows.Automation.SelectionItemPattern]::Pattern,[ref]$pattern)){$pattern.Select();Start-Sleep -Milliseconds 350;return}};throw ('UI actionable control absent: '+$name)}
function Credentials([string]$password){foreach($pair in @(@('EMAIL OR USERNAME','teste-byx'),@('PASSWORD',$password))){$nodes=Named $pair[0];$field=@($nodes|Where-Object {$_.Current.ControlType -eq [Windows.Automation.ControlType]::Edit})|Select-Object -First 1;if(!$field){throw ('Missing login field: '+$pair[0])};$value=$null;if($field.TryGetCurrentPattern([Windows.Automation.ValuePattern]::Pattern,[ref]$value)){$value.SetValue($pair[1])}else{[DemoQaWindow]::SetForegroundWindow($owned.MainWindowHandle)|Out-Null;$field.SetFocus();Start-Sleep -Milliseconds 150;if([DemoQaWindow]::GetForegroundWindow() -ne $owned.MainWindowHandle){throw 'Owned DEMO window is not focused; keyboard test stopped'};[Windows.Forms.SendKeys]::SendWait('^a');[Windows.Forms.SendKeys]::SendWait($pair[1])}};InvokeName 'Sign in'}
try{
$launcher=Start-Process -FilePath $Executable -WorkingDirectory $env:TEMP -PassThru;$owned=$launcher;$until=(Get-Date).AddSeconds(30)
do{Start-Sleep -Milliseconds 300;if($owned.Id -eq $launcher.Id){$children=@(Get-CimInstance Win32_Process -Filter ('ParentProcessId='+$launcher.Id)|Where-Object ExecutablePath -ieq $Executable);if($children.Count -eq 1){$owned=Get-Process -Id $children[0].ProcessId}};$owned.Refresh();if($owned.HasExited){throw 'Launcher exited'}}while($owned.MainWindowTitle -notlike 'BYX-MVP DEMO*' -and (Get-Date) -lt $until)
if(!$owned.MainWindowHandle){throw 'Native JavaFX window unavailable'};$root=[Windows.Automation.AutomationElement]::FromHandle($owned.MainWindowHandle)
WaitText '^EMAIL OR USERNAME$';$checks.Add('NATIVE_EXE_ORIGINAL_LOGIN_ACCESSIBLE')
Credentials 'wrong';WaitText 'Sign-in failed|Login ou senha incorretos';$checks.Add('NATIVE_EXE_WRONG_PASSWORD_REJECTED')
Credentials 'byx123123';WaitText 'USER / DEMO';WaitText 'ETHUSDT';$checks.Add('NATIVE_EXE_PUBLIC_LOGIN_USER_DEMO');$checks.Add('NATIVE_EXE_DESK_SIMULATED_DATA')
InvokeName 'Markets & Portfolio';WaitText 'Portfolio|Balance|Markets';$checks.Add('NATIVE_EXE_MARKETS_NAVIGATION')
InvokeName 'Settings';WaitText 'Preferences for this account';$checks.Add('NATIVE_EXE_SETTINGS_NAVIGATION')
InvokeName 'Settings section Appearance';InvokeName 'Light';if(!((Named 'Light'|Select-Object -First 1).GetCurrentPattern([Windows.Automation.SelectionItemPattern]::Pattern).Current.IsSelected)){throw 'Light theme radio not selected'};$checks.Add('NATIVE_EXE_LIGHT_THEME');InvokeName 'Dark';if(!((Named 'Dark'|Select-Object -First 1).GetCurrentPattern([Windows.Automation.SelectionItemPattern]::Pattern).Current.IsSelected)){throw 'Dark theme radio not selected'};$checks.Add('NATIVE_EXE_DARK_THEME');InvokeName 'Compact';InvokeName 'Save changes';WaitText 'Settings saved';$checks.Add('NATIVE_EXE_DENSITY_SAVED')
InvokeName 'REDUCED';InvokeName 'Save changes';$checks.Add('NATIVE_EXE_MOTION_SAVED')
InvokeName 'Logout';WaitText '^EMAIL OR USERNAME$';$checks.Add('NATIVE_EXE_LOGOUT_LOGIN_RETURN')
if(!$owned.CloseMainWindow() -or !$owned.WaitForExit(10000)){throw 'Native UI close failed'};$checks.Add('NATIVE_EXE_UI_CLOSED');$owned=$null
}catch{$failure=$_.Exception.Message}finally{
if($owned -and !$owned.HasExited){$owned.CloseMainWindow()|Out-Null;if(!$owned.WaitForExit(5000)){Stop-Process -Id $owned.Id}}
if($launcher -and !$launcher.HasExited){if(!$launcher.WaitForExit(5000)){Stop-Process -Id $launcher.Id}}
[pscustomobject]@{status=if($failure){'FAIL_OR_UNAVAILABLE'}else{'PASS'};pass=$checks.Count;checks=@($checks);failure=$failure;exeSha256=(Get-FileHash -LiteralPath $Executable -Algorithm SHA256).Hash.ToLower();method='Windows UI Automation against actual packaged EXE; no test classes injected';humanVisualUat='NOT_EXECUTED'}|ConvertTo-Json -Depth 5|Set-Content -LiteralPath $Report -Encoding UTF8
}
if($failure){throw ('NATIVE_UI_GATE: '+$failure)}
'NATIVE_EXE_UI_AUTOMATION_PASS'