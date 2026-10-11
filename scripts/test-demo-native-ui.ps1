param([Parameter(Mandatory)][string]$Executable,[Parameter(Mandatory)][string]$Report)
$ErrorActionPreference='Stop'
$Executable=[IO.Path]::GetFullPath($Executable);$Report=[IO.Path]::GetFullPath($Report)
Add-Type -AssemblyName UIAutomationClient;Add-Type -AssemblyName UIAutomationTypes
Add-Type -AssemblyName System.Windows.Forms;Add-Type -AssemblyName System.Drawing
Add-Type -TypeDefinition @"
using System;using System.Runtime.InteropServices;
public static class DemoAlpha2QaWindow {
 [StructLayout(LayoutKind.Sequential)] public struct RECT {public int Left,Top,Right,Bottom;}
 [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
 [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
 [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h,out RECT r);
 [DllImport("user32.dll")] public static extern bool IsZoomed(IntPtr h);
 [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr h);
 [DllImport("user32.dll")] public static extern int GetWindowLong(IntPtr h,int index);
 [DllImport("user32.dll")] public static extern uint GetDpiForWindow(IntPtr h);
 [DllImport("dwmapi.dll")] public static extern int DwmGetWindowAttribute(IntPtr h,uint attribute,out RECT rect,uint size);
 [DllImport("user32.dll")] public static extern IntPtr SetThreadDpiAwarenessContext(IntPtr context);
 [DllImport("user32.dll")] public static extern bool MoveWindow(IntPtr h,int x,int y,int width,int height,bool repaint);
 [DllImport("user32.dll")] public static extern IntPtr SendMessage(IntPtr h,uint msg,IntPtr w,IntPtr l);
}
"@
$oldDpiContext=[DemoAlpha2QaWindow]::SetThreadDpiAwarenessContext([IntPtr]::new(-4))
$checks=[Collections.Generic.List[string]]::new();$captures=[Collections.Generic.List[object]]::new();$languageControls=[Collections.Generic.List[object]]::new();$scrollActions=[Collections.Generic.List[object]]::new()
$launcher=$null;$owned=$null;$failure=$null;$root=$null;$windowMethod=$null;$resizeMethod=$null
$preference=[IO.Path]::Combine($env:USERPROFILE,'.byx-mvp-demo','presentation.properties')
$preferenceExisted=Test-Path -LiteralPath $preference -PathType Leaf
$priorPreferenceBytes=$null;$preferencesRestored=$false
$evidence=Join-Path (Split-Path -Parent $Report) ([IO.Path]::GetFileNameWithoutExtension($Report)+'-screenshots')
New-Item -ItemType Directory -Path $evidence -Force|Out-Null
function Nodes {
 $items=@($root.FindAll([Windows.Automation.TreeScope]::Descendants,[Windows.Automation.Condition]::TrueCondition))
 $condition=[Windows.Automation.PropertyCondition]::new([Windows.Automation.AutomationElement]::ProcessIdProperty,[int]$owned.Id)
 $items+=@([Windows.Automation.AutomationElement]::RootElement.FindAll([Windows.Automation.TreeScope]::Descendants,$condition))
 $seen=[Collections.Generic.HashSet[string]]::new()
 foreach($item in $items){if($item.Current.ProcessId -eq $owned.Id -and $seen.Add(($item.GetRuntimeId() -join ','))){$item}}
}
function NodeShown($node){
 if($node.Current.IsOffscreen -or $node.Current.BoundingRectangle.IsEmpty){return $false}
 $target=$node.Current.BoundingRectangle;$parent=$node
 for($depth=0;$depth -lt 35;$depth++){
  $parent=[Windows.Automation.TreeWalker]::RawViewWalker.GetParent($parent)
  if(!$parent -or $parent.Current.ProcessId -ne $owned.Id){break}
  $scroll=$null
  if($parent.TryGetCurrentPattern([Windows.Automation.ScrollPattern]::Pattern,[ref]$scroll) -and !$parent.Current.BoundingRectangle.Contains($target)){return $false}
 }
 return $true
}
function RevealNode($node){
 if(NodeShown $node){return $true}
 $item=$null
 if($node.TryGetCurrentPattern([Windows.Automation.ScrollItemPattern]::Pattern,[ref]$item)){try{$item.ScrollIntoView();Start-Sleep -Milliseconds 250}catch{}}
 $parent=$node
 for($depth=0;$depth -lt 35;$depth++){
  $parent=[Windows.Automation.TreeWalker]::RawViewWalker.GetParent($parent)
  if(!$parent -or $parent.Current.ProcessId -ne $owned.Id){break}
  $scroll=$null
  if(!$parent.TryGetCurrentPattern([Windows.Automation.ScrollPattern]::Pattern,[ref]$scroll)){continue}
  for($attempt=0;$attempt -lt 16;$attempt++){
   $target=$node.Current.BoundingRectangle;$viewport=$parent.Current.BoundingRectangle
   $horizontal=[Windows.Automation.ScrollAmount]::NoAmount;$vertical=[Windows.Automation.ScrollAmount]::NoAmount
   if($scroll.Current.HorizontallyScrollable){if($target.Right -gt $viewport.Right){$horizontal=[Windows.Automation.ScrollAmount]::SmallIncrement}elseif($target.Left -lt $viewport.Left){$horizontal=[Windows.Automation.ScrollAmount]::SmallDecrement}}
   if($scroll.Current.VerticallyScrollable){if($target.Bottom -gt $viewport.Bottom){$vertical=[Windows.Automation.ScrollAmount]::SmallIncrement}elseif($target.Top -lt $viewport.Top){$vertical=[Windows.Automation.ScrollAmount]::SmallDecrement}}
   if($horizontal -eq [Windows.Automation.ScrollAmount]::NoAmount -and $vertical -eq [Windows.Automation.ScrollAmount]::NoAmount){break}
   $scroll.Scroll($horizontal,$vertical);$scrollActions.Add([pscustomobject]@{method='Owned ancestor UIAutomation ScrollPattern.Scroll';automationId=$parent.Current.AutomationId;horizontal=$horizontal.ToString();vertical=$vertical.ToString()});Start-Sleep -Milliseconds 150
  }
 }
 return (NodeShown $node)
}
function ResetViewports([string]$pattern){
 $node=@(Nodes|Where-Object {$_.Current.Name -match $pattern}|Where-Object {NodeShown $_})|Select-Object -First 1
 if(!$node){throw ('Visible scroll reference absent: '+$pattern)}
 $parent=$node
 for($depth=0;$depth -lt 35;$depth++){
  $parent=[Windows.Automation.TreeWalker]::RawViewWalker.GetParent($parent)
  if(!$parent -or $parent.Current.ProcessId -ne $owned.Id){break}
  $scroll=$null
  if($parent.TryGetCurrentPattern([Windows.Automation.ScrollPattern]::Pattern,[ref]$scroll)){
   $horizontal=if($scroll.Current.HorizontallyScrollable){0}else{-1};$vertical=if($scroll.Current.VerticallyScrollable){0}else{-1}
   if($horizontal -ne -1 -or $vertical -ne -1){$scroll.SetScrollPercent($horizontal,$vertical);$scrollActions.Add([pscustomobject]@{method='Owned ancestor UIAutomation ScrollPattern.SetScrollPercent restores view origin';automationId=$parent.Current.AutomationId;horizontal=$horizontal;vertical=$vertical})}
  }
 }
 Start-Sleep -Milliseconds 300
}
function ComboValue($combo){
 # JavaFX Windows leaves ComboBox.Name empty and exposes accessibleText through ValuePattern.
 # Read values only from ComboBox controls; never inspect Edit/password field values.
 if($combo.Current.ControlType -ne [Windows.Automation.ControlType]::ComboBox){return $null}
 $value=$null;if($combo.TryGetCurrentPattern([Windows.Automation.ValuePattern]::Pattern,[ref]$value)){return $value.Current.Value}
 return $null
}
function ControlDiagnostics {
 Nodes|Where-Object {$_.Current.ControlType -ne [Windows.Automation.ControlType]::Text}|ForEach-Object {[pscustomobject]@{name=$_.Current.Name;controlType=$_.Current.ControlType.ProgrammaticName;automationId=$_.Current.AutomationId;offscreen=$_.Current.IsOffscreen;comboValue=(ComboValue $_)}}|ConvertTo-Json -Depth 3|Set-Content -LiteralPath ($Report+'.owned-controls.json') -Encoding UTF8
}
function Named([string]$name){return @(Nodes|Where-Object {$_.Current.Name -eq $name -and $_.Current.ControlType -ne [Windows.Automation.ControlType]::Text -and !$_.Current.IsOffscreen})}
function WaitText([string]$pattern){
 $until=(Get-Date).AddSeconds(15)
 do{foreach($match in @(Nodes|Where-Object {$_.Current.Name -match $pattern})){if(NodeShown $match){return};if(RevealNode $match){return}};Start-Sleep -Milliseconds 200}while((Get-Date) -lt $until)
 throw ('Visible UI text not observed: '+$pattern)
}
function InvokeName([string]$name){
 $candidates=@(Nodes|Where-Object {$_.Current.Name -eq $name -and $_.Current.ControlType -ne [Windows.Automation.ControlType]::Text -and $_.Current.IsEnabled})
 foreach($n in $candidates){
  if(!(RevealNode $n)){continue}
  $pattern=$null;if($n.TryGetCurrentPattern([Windows.Automation.InvokePattern]::Pattern,[ref]$pattern)){$pattern.Invoke();Start-Sleep -Milliseconds 400;return};if($n.TryGetCurrentPattern([Windows.Automation.TogglePattern]::Pattern,[ref]$pattern)){$pattern.Toggle();Start-Sleep -Milliseconds 400;return};if($n.TryGetCurrentPattern([Windows.Automation.SelectionItemPattern]::Pattern,[ref]$pattern)){$pattern.Select();Start-Sleep -Milliseconds 400;return}};throw ('UI actionable control absent: '+$name)}
function FocusOwned {
 try{$root.SetFocus()}catch{}
 [DemoAlpha2QaWindow]::SetForegroundWindow($owned.MainWindowHandle)|Out-Null;Start-Sleep -Milliseconds 200
 if([DemoAlpha2QaWindow]::GetForegroundWindow() -ne $owned.MainWindowHandle){$focusable=@(Nodes|Where-Object {$_.Current.IsKeyboardFocusable -and !$_.Current.IsOffscreen -and $_.Current.IsEnabled})|Select-Object -First 1;if($focusable){$focusable.SetFocus();Start-Sleep -Milliseconds 200}}
 if([DemoAlpha2QaWindow]::GetForegroundWindow() -ne $owned.MainWindowHandle){throw 'Owned DEMO window is not foreground; keyboard/capture stopped'}
}
function ChooseLanguage([string]$language){
 if($language -ne 'English' -and $language -ne 'Português (Brasil)'){throw 'Unsupported test language'}
 $combo=$null
 $candidates=@(Nodes|Where-Object {$_.Current.ControlType -eq [Windows.Automation.ControlType]::ComboBox -and $_.Current.IsEnabled}|Where-Object {(ComboValue $_) -match '^(Interface language|Idioma da interface)$'}|Sort-Object {$_.Current.BoundingRectangle.Top} -Descending)
 # Settings has both the global selector and its original General-section selector; exercise the lower Settings control.
 foreach($candidate in $candidates){if(RevealNode $candidate){$combo=$candidate;break}}
 if(!$combo){ControlDiagnostics;throw 'Original LanguageSelector ComboBox ValuePattern unavailable'}
 $label=ComboValue $combo;FocusOwned;$combo.SetFocus();$expand=$null;$method=$null
 if($combo.TryGetCurrentPattern([Windows.Automation.ExpandCollapsePattern]::Pattern,[ref]$expand)){
  $expand.Expand();Start-Sleep -Milliseconds 300
  InvokeName $language
  try{$expand.Collapse()}catch{}
  $method='UIAutomation ComboBox.ValuePattern identification and ExpandCollapse/SelectionItem selection'
 }else{
  if([DemoAlpha2QaWindow]::GetForegroundWindow() -ne $owned.MainWindowHandle -or ![Windows.Automation.AutomationElement]::Compare([Windows.Automation.AutomationElement]::FocusedElement,$combo)){throw 'Owned LanguageSelector is not focused; keyboard input stopped'}
  [Windows.Forms.SendKeys]::SendWait('%{DOWN}');Start-Sleep -Milliseconds 200
  if([DemoAlpha2QaWindow]::GetForegroundWindow() -ne $owned.MainWindowHandle){throw 'DEMO lost foreground; language keyboard input stopped'}
  if($language -eq 'English'){[Windows.Forms.SendKeys]::SendWait('{HOME}{ENTER}')}else{[Windows.Forms.SendKeys]::SendWait('{END}{ENTER}')}
  $method='UIAutomation ComboBox.ValuePattern identification and focused original ComboBox keyboard selection'
 }
 $languageControls.Add([pscustomobject]@{requested=$language;accessibleLabel=$label;automationId=$combo.Current.AutomationId;method=$method});Start-Sleep -Milliseconds 600
}
function Credentials([string]$password,[bool]$english=$false){
 $pairs=if($english){@(@('EMAIL OR USERNAME','teste-byx'),@('PASSWORD',$password))}else{@(@('E-MAIL OU USUÁRIO','teste-byx'),@('SENHA',$password))}
 foreach($pair in $pairs){$field=@((Named $pair[0])|Where-Object {$_.Current.ControlType -eq [Windows.Automation.ControlType]::Edit})|Select-Object -First 1
  if(!$field){throw ('Missing login field: '+$pair[0])};$value=$null
  if($field.TryGetCurrentPattern([Windows.Automation.ValuePattern]::Pattern,[ref]$value)){$value.SetValue($pair[1])}
  else{FocusOwned;$field.SetFocus();Start-Sleep -Milliseconds 150;if([DemoAlpha2QaWindow]::GetForegroundWindow() -ne $owned.MainWindowHandle){throw 'DEMO lost focus; password input stopped'};[Windows.Forms.SendKeys]::SendWait('^a');[Windows.Forms.SendKeys]::SendWait($pair[1])}
 };if($english){InvokeName 'Sign in'}else{InvokeName 'Entrar'}
}
function WindowRect {
 $r=[DemoAlpha2QaWindow+RECT]::new();if(![DemoAlpha2QaWindow]::GetWindowRect($owned.MainWindowHandle,[ref]$r)){throw 'Native window rect unavailable'}
 return [Drawing.Rectangle]::new($r.Left,$r.Top,$r.Right-$r.Left,$r.Bottom-$r.Top)
}
function WindowState([Windows.Automation.WindowVisualState]$state){
 $pattern=$null
 if($root.TryGetCurrentPattern([Windows.Automation.WindowPattern]::Pattern,[ref]$pattern)){$pattern.SetWindowVisualState($state);Set-Variable -Name windowMethod -Scope 1 -Value 'UIAutomation WindowPattern'}
 else{$command=switch($state){'Maximized'{0xF030};'Minimized'{0xF020};default{0xF120}};[DemoAlpha2QaWindow]::SendMessage($owned.MainWindowHandle,0x112,[IntPtr]::new($command),[IntPtr]::Zero)|Out-Null;Set-Variable -Name windowMethod -Scope 1 -Value 'Win32 WM_SYSCOMMAND native maximize/minimize/restore commands (WindowPattern unavailable)'}
 Start-Sleep -Milliseconds 650
}
function Capture([string]$name){
 FocusOwned;Start-Sleep -Milliseconds 1200
 if([DemoAlpha2QaWindow]::GetForegroundWindow() -ne $owned.MainWindowHandle){throw 'FOREGROUND_BUSY: owned EXE lost foreground before capture; no screenshot taken'}
 $rect=WindowRect;$frame=[DemoAlpha2QaWindow+RECT]::new();if([DemoAlpha2QaWindow]::DwmGetWindowAttribute($owned.MainWindowHandle,9,[ref]$frame,16) -eq 0 -and $frame.Right -gt $frame.Left -and $frame.Bottom -gt $frame.Top){$rect=[Drawing.Rectangle]::new($frame.Left,$frame.Top,$frame.Right-$frame.Left,$frame.Bottom-$frame.Top)};$screen=[Windows.Forms.Screen]::FromHandle($owned.MainWindowHandle)
 $rect=[Drawing.Rectangle]::Intersect($rect,$screen.WorkingArea)
 if($rect.Width -lt 100 -or $rect.Height -lt 100){throw 'Owned application capture rect invalid'}
 $bitmap=[Drawing.Bitmap]::new($rect.Width,$rect.Height);$graphics=[Drawing.Graphics]::FromImage($bitmap)
 try{$graphics.CopyFromScreen($rect.X,$rect.Y,0,0,$rect.Size);$file=Join-Path $evidence ('EXE-'+$name+'.png');$bitmap.Save($file,[Drawing.Imaging.ImageFormat]::Png)}finally{$graphics.Dispose();$bitmap.Dispose()}
 $captures.Add([pscustomobject]@{file=[IO.Path]::GetFileName($file);method='Actual packaged EXE, foreground owned visible DWM frame clipped to monitor working area';width=$rect.Width;height=$rect.Height;dpi=[DemoAlpha2QaWindow]::GetDpiForWindow($owned.MainWindowHandle)})
}
function LaunchOwned {
 Set-Variable -Name launcher -Scope 1 -Value (Start-Process -FilePath $Executable -WorkingDirectory $env:TEMP -PassThru);Set-Variable -Name owned -Scope 1 -Value $launcher
 $until=(Get-Date).AddSeconds(45)
 do{Start-Sleep -Milliseconds 300;if($owned.Id -eq $launcher.Id){$children=@(Get-CimInstance Win32_Process -Filter ('ParentProcessId='+$launcher.Id)|Where-Object ExecutablePath -ieq $Executable);if($children.Count -eq 1){Set-Variable -Name owned -Scope 1 -Value (Get-Process -Id $children[0].ProcessId)}};$owned.Refresh();if($owned.HasExited){throw 'Launcher exited'}}while($owned.MainWindowTitle -notlike 'BYX-MVP DEMO*' -and (Get-Date) -lt $until)
 if(!$owned.MainWindowHandle){throw 'Native JavaFX window unavailable'};Set-Variable -Name root -Scope 1 -Value ([Windows.Automation.AutomationElement]::FromHandle($owned.MainWindowHandle))
}
function CloseOwned {
 if(!$owned.CloseMainWindow() -or !$owned.WaitForExit(10000)){throw 'Native UI close failed'}
 if($launcher.Id -ne $owned.Id -and !$launcher.WaitForExit(10000)){throw 'Parent launcher close failed'}
 Set-Variable -Name owned -Scope 1 -Value $null;Set-Variable -Name launcher -Scope 1 -Value $null
}
try{
 if($preferenceExisted){$priorPreferenceBytes=[IO.File]::ReadAllBytes($preference);Remove-Item -LiteralPath $preference}
 LaunchOwned;WaitText '^E-MAIL OU USUÁRIO$';$checks.Add('NATIVE_EXE_FRESH_PROFILE_PT_BR_LOGIN')
 $original=WindowRect;$screen=[Windows.Forms.Screen]::FromHandle($owned.MainWindowHandle)
 if([DemoAlpha2QaWindow]::IsZoomed($owned.MainWindowHandle) -or [DemoAlpha2QaWindow]::IsIconic($owned.MainWindowHandle)){throw 'Startup is maximized or minimized'}
 $style=[DemoAlpha2QaWindow]::GetWindowLong($owned.MainWindowHandle,-16)
 if(($style -band 0x40000) -eq 0 -or ($style -band 0x20000) -eq 0 -or ($style -band 0x10000) -eq 0 -or ($style -band 0xC00000) -ne 0xC00000){throw 'Native resize/minimize/maximize/caption styles missing'}
 if(!$screen.WorkingArea.Contains($original)){throw 'Initial EXE window exceeds physical monitor work area'}
 $checks.Add('NATIVE_EXE_NORMAL_DECORATED_RESIZABLE_INSIDE_WORK_AREA');Capture 'login-PT-BR'
 ChooseLanguage 'English';WaitText '^EMAIL OR USERNAME$';$checks.Add('NATIVE_EXE_LOGIN_LANGUAGE_EN');Capture 'login-EN'
 ChooseLanguage 'Português (Brasil)';WaitText '^E-MAIL OU USUÁRIO$';$checks.Add('NATIVE_EXE_LOGIN_LANGUAGE_BACK_PT_BR')
 Credentials 'wrong';WaitText 'Falha ao entrar|Usuário ou senha incorretos';$checks.Add('NATIVE_EXE_WRONG_PASSWORD_REJECTED')
 Credentials 'byx123123';WaitText 'USER / DEMO';WaitText 'ETHUSDT';$scrollBefore=$scrollActions.Count;WaitText '^(Livro de ofertas|Negociações recentes)$';$checks.Add('NATIVE_EXE_PUBLIC_LOGIN_USER_DEMO');$checks.Add('NATIVE_EXE_DESK_SIMULATED_DATA')
 if($scrollActions.Count -gt $scrollBefore){Capture 'desk-details-DARK-PT-BR-scrolled';ResetViewports '^(Livro de ofertas|Negociações recentes)$'};Capture 'desk-DARK-PT-BR'
 $afterLogin=WindowRect;if($afterLogin.Size -ne $original.Size){throw 'Login changed native window geometry'};$checks.Add('NATIVE_EXE_LOGIN_PRESERVES_GEOMETRY')
 InvokeName 'Mercados e portfólio';WaitText 'Mercados|Saldo|Portfólio';$checks.Add('NATIVE_EXE_MARKETS_PT_BR');Capture 'markets-PT-BR'
 InvokeName 'Configurações';WaitText 'Preferências visuais desta DEMO offline';$checks.Add('NATIVE_EXE_SETTINGS_PT_BR');Capture 'settings-PT-BR'
 InvokeName 'Seção das configurações: Aparência';InvokeName 'Claro'
 if(!((Named 'Claro'|Select-Object -First 1).GetCurrentPattern([Windows.Automation.SelectionItemPattern]::Pattern).Current.IsSelected)){throw 'Light theme radio not selected'};$checks.Add('NATIVE_EXE_LIGHT_THEME')
 InvokeName 'Mesa de negociação';WaitText 'ETHUSDT';Capture 'desk-LIGHT-PT-BR'
 InvokeName 'Configurações';InvokeName 'Seção das configurações: Aparência';InvokeName 'Escuro'
 if(!((Named 'Escuro'|Select-Object -First 1).GetCurrentPattern([Windows.Automation.SelectionItemPattern]::Pattern).Current.IsSelected)){throw 'Dark theme radio not selected'};$checks.Add('NATIVE_EXE_DARK_THEME')
 InvokeName 'Compacta';InvokeName 'Salvar alterações';WaitText 'Preferências visuais salvas localmente|Configurações salvas';$checks.Add('NATIVE_EXE_DENSITY_SAVED')
 InvokeName 'REDUCED';InvokeName 'Salvar alterações';$checks.Add('NATIVE_EXE_MOTION_SAVED')
 InvokeName 'Mesa de negociação';WaitText 'ETHUSDT';$beforeResize=WindowRect
 $resize=$null
 if($root.TryGetCurrentPattern([Windows.Automation.TransformPattern]::Pattern,[ref]$resize) -and $resize.Current.CanResize){$resize.Resize([Math]::Floor($beforeResize.Width*.84),[Math]::Floor($beforeResize.Height*.9));$resizeMethod='UIAutomation TransformPattern.Resize'}
 else{if(![DemoAlpha2QaWindow]::MoveWindow($owned.MainWindowHandle,$beforeResize.X,$beforeResize.Y,[Math]::Floor($beforeResize.Width*.84),[Math]::Floor($beforeResize.Height*.9),$true)){throw 'Native resize failed'};$resizeMethod='Win32 MoveWindow against owned resizable native window (TransformPattern unavailable)'}
 Start-Sleep -Milliseconds 650;$small=WindowRect
 if($small.Width -ge $beforeResize.Width -or $small.Height -ge $beforeResize.Height){throw 'EXE window did not shrink'};$checks.Add('NATIVE_EXE_SHRINK_WINDOW');Capture 'small-restored-PT-BR'
 [DemoAlpha2QaWindow]::MoveWindow($owned.MainWindowHandle,$small.X+5,$small.Y+5,$small.Width,$small.Height,$true)|Out-Null;Start-Sleep -Milliseconds 350;$moved=WindowRect
 if($moved.X -ne $small.X+5 -or $moved.Y -ne $small.Y+5){throw 'Native window movement failed'};$checks.Add('NATIVE_EXE_MOVE_WINDOW')
 WindowState ([Windows.Automation.WindowVisualState]::Maximized)
 if(![DemoAlpha2QaWindow]::IsZoomed($owned.MainWindowHandle)){throw 'Maximize failed'};$checks.Add('NATIVE_EXE_MAXIMIZE');Capture 'maximized-PT-BR'
 InvokeName 'Mercados e portfólio';if(![DemoAlpha2QaWindow]::IsZoomed($owned.MainWindowHandle)){throw 'Navigation changed maximized state'};$checks.Add('NATIVE_EXE_NAVIGATION_PRESERVES_MAXIMIZED_STATE')
 WindowState ([Windows.Automation.WindowVisualState]::Normal);$restored=WindowRect
 if([DemoAlpha2QaWindow]::IsZoomed($owned.MainWindowHandle) -or [Math]::Abs($restored.Width-$small.Width) -gt 2 -or [Math]::Abs($restored.Height-$small.Height) -gt 2){throw 'Restore did not recover small size'};$checks.Add('NATIVE_EXE_RESTORE_SMALL_SIZE')
 WindowState ([Windows.Automation.WindowVisualState]::Minimized);if(![DemoAlpha2QaWindow]::IsIconic($owned.MainWindowHandle)){throw 'Minimize failed'};$checks.Add('NATIVE_EXE_MINIMIZE')
 WindowState ([Windows.Automation.WindowVisualState]::Normal);if([DemoAlpha2QaWindow]::IsIconic($owned.MainWindowHandle)){throw 'Unminimize failed'};$checks.Add('NATIVE_EXE_UNMINIMIZE')
 InvokeName 'Configurações';InvokeName 'Seção das configurações: Geral';ChooseLanguage 'English';WaitText 'Settings';$checks.Add('NATIVE_EXE_SETTINGS_LANGUAGE_EN')
 InvokeName 'Markets & Portfolio';WaitText 'Markets|Portfolio';$checks.Add('NATIVE_EXE_MARKETS_LANGUAGE_EN')
 InvokeName 'Trading Desk';$scrollBefore=$scrollActions.Count;WaitText '^(Order book|Recent trades)$';$checks.Add('NATIVE_EXE_DESK_LANGUAGE_EN')
 if($scrollActions.Count -gt $scrollBefore){Capture 'desk-details-EN-scrolled';ResetViewports '^(Order book|Recent trades)$'};Capture 'desk-EN'
 InvokeName 'Settings';InvokeName 'Settings section Appearance';InvokeName 'Light'
 InvokeName 'Sign out';WaitText '^EMAIL OR USERNAME$';$checks.Add('NATIVE_EXE_LOGOUT_PRESERVES_EN');$logoutRect=WindowRect
 if($logoutRect.Size -ne $restored.Size){throw 'Logout changed window size'};$checks.Add('NATIVE_EXE_LOGOUT_PRESERVES_WINDOW_SIZE')
 if(!(Test-Path -LiteralPath $preference)){throw 'DEMO presentation preference file not written'};$saved=[IO.File]::ReadAllText($preference)
 if($saved -notmatch 'language=en' -or $saved -notmatch 'theme=LIGHT' -or $saved -notmatch 'density=COMPACT' -or $saved -notmatch 'motion=REDUCED' -or $saved -match 'teste-byx|byx123123|DEMO_USER'){throw 'Presentation-only persistence values invalid'};$checks.Add('NATIVE_EXE_PRESENTATION_ONLY_PERSISTENCE')
 CloseOwned;$checks.Add('NATIVE_EXE_UI_CLOSED');LaunchOwned;WaitText '^EMAIL OR USERNAME$';$checks.Add('NATIVE_EXE_REOPEN_PRESERVES_EN_REQUIRES_LOGIN')
 if([DemoAlpha2QaWindow]::IsZoomed($owned.MainWindowHandle)){throw 'Reopen maximized unexpectedly'};$checks.Add('NATIVE_EXE_REOPEN_NORMAL_WINDOW')
 Credentials 'byx123123' $true;InvokeName 'Settings';InvokeName 'Settings section Appearance'
 if(!((Named 'Light'|Select-Object -First 1).GetCurrentPattern([Windows.Automation.SelectionItemPattern]::Pattern).Current.IsSelected)){throw 'Theme not restored'};$checks.Add('NATIVE_EXE_REOPEN_RESTORES_LIGHT_THEME');Capture 'restart-EN-LIGHT'
 CloseOwned
}catch{$failure=$_.Exception.Message;$failureStack=$_.ScriptStackTrace;if($root -and $owned -and !$owned.HasExited){try{ControlDiagnostics}catch{}}}finally{
 if($owned -and !$owned.HasExited){$owned.CloseMainWindow()|Out-Null;if(!$owned.WaitForExit(5000)){Stop-Process -Id $owned.Id}}
 if($launcher -and !$launcher.HasExited){if(!$launcher.WaitForExit(5000)){Stop-Process -Id $launcher.Id}}
 try{if($preferenceExisted){[IO.File]::WriteAllBytes($preference,$priorPreferenceBytes)}elseif(Test-Path -LiteralPath $preference){Remove-Item -LiteralPath $preference};$preferencesRestored=$true}catch{if(!$failure){$failure='Could not restore original DEMO presentation preferences'}}
 if($oldDpiContext -ne [IntPtr]::Zero){[DemoAlpha2QaWindow]::SetThreadDpiAwarenessContext($oldDpiContext)|Out-Null}
 [pscustomobject]@{status=if($failure){'FAIL_OR_UNAVAILABLE'}else{'PASS'};pass=$checks.Count;checks=@($checks);failure=$failure;failureStack=$failureStack;exeSha256=(Get-FileHash -LiteralPath $Executable -Algorithm SHA256).Hash.ToLower();method='Windows UI Automation against actual packaged EXE; no test classes, runtime flags or launcher configuration changes';nativeWindowMethod=$windowMethod;nativeResizeMethod=$resizeMethod;languageControls=@($languageControls);nativeScrollActions=@($scrollActions);screenshots=@($captures);originalDemoPresentationRestored=$preferencesRestored;physicalDisplayModes='Only the connected Windows display and its actual scaling; no system display settings changed';humanVisualUat='NOT_EXECUTED'}|ConvertTo-Json -Depth 6|Set-Content -LiteralPath $Report -Encoding UTF8
}
if($failure){throw ('NATIVE_UI_GATE: '+$failure)}
'NATIVE_EXE_UI_AUTOMATION_PASS'
