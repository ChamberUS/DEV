param([Parameter(Mandatory)][string]$Zip,[Parameter(Mandatory)][string]$Destination,[Parameter(Mandatory)][string]$Report)
$Destination=[IO.Path]::GetFullPath($Destination);$Zip=[IO.Path]::GetFullPath($Zip);$Report=[IO.Path]::GetFullPath($Report)
$ErrorActionPreference='Stop';Add-Type -AssemblyName System.IO.Compression.FileSystem
if(Test-Path -LiteralPath $Destination){throw 'QA destination must not exist'}
[IO.Compression.ZipFile]::ExtractToDirectory($Zip,$Destination)
$exe=$Destination+'\BYX-MVP.exe';$checks=[Collections.Generic.List[string]]::new();$owned=$null;$launcher=$null;$failure=$null;$rounds=@()
$oldJava=$env:JAVA_HOME;$oldPath=$env:PATH
try{
$env:JAVA_HOME=$null;$env:PATH=$env:SystemRoot+'\System32;'+$env:SystemRoot
for($round=1;$round -le 2;$round++){
$launcher=Start-Process -FilePath $exe -WorkingDirectory $env:TEMP -RedirectStandardOutput ($Report+'.stdout') -RedirectStandardError ($Report+'.stderr') -PassThru;$owned=$launcher
$deadline=(Get-Date).AddSeconds(45)
do{Start-Sleep -Milliseconds 500;if($owned.Id -eq $launcher.Id){$child=@(Get-CimInstance Win32_Process -Filter ('ParentProcessId='+$launcher.Id)|Where-Object {$_.ExecutablePath -ieq $exe});if($child.Count -gt 1){throw 'Unexpected multiple launcher children'};if($child.Count -eq 1){$owned=Get-Process -Id $child[0].ProcessId}};$owned.Refresh();if($owned.HasExited){throw 'Launcher/JVM exited before JavaFX stage appeared'}}while((!$owned.MainWindowHandle -or $owned.MainWindowTitle -notlike 'BYX-MVP DEMO*') -and (Get-Date) -lt $deadline)
if(!$owned.MainWindowHandle -or $owned.MainWindowTitle -notlike 'BYX-MVP DEMO*'){throw 'JavaFX stage/login startup not confirmed'}
$checks.Add('ROUND_'+$round+'_EXE_JAVAFX_STAGE_STARTED')
$modulePaths=@($owned.Modules|ForEach-Object FileName)
$jvm=@($modulePaths|Where-Object {$_ -ieq ($Destination+'\runtime\bin\server\jvm.dll')});if($jvm.Count -ne 1){throw 'Bundled runtime not loaded'};$checks.Add('ROUND_'+$round+'_BUNDLED_JVM_NO_EXTERNAL_JAVA')
$glass=@($modulePaths|Where-Object {$_ -ieq ($Destination+'\app\native\glass.dll')});if($glass.Count -ne 1){throw 'Packaged JavaFX native library not loaded'};$checks.Add('ROUND_'+$round+'_BUNDLED_JAVAFX_NATIVE')
$network=@();$children=@();$until=(Get-Date).AddSeconds(5)
do{
$network+=@(Get-NetTCPConnection -ErrorAction SilentlyContinue|Where-Object OwningProcess -eq $owned.Id)
$network+=@(Get-NetUDPEndpoint -ErrorAction SilentlyContinue|Where-Object OwningProcess -eq $owned.Id)
$children+=@(Get-CimInstance Win32_Process -Filter ('ParentProcessId='+$owned.Id))
Start-Sleep -Milliseconds 500
}while((Get-Date) -lt $until)
if($network.Count){throw 'Unexpected TCP/UDP activity owned by DEMO'};if($children.Count){throw 'DEMO spawned a subprocess'}
$checks.Add('ROUND_'+$round+'_NO_OBSERVED_TCP_UDP_OR_CHILD_PROCESS')
$rounds+=@{pid=$owned.Id;title=$owned.MainWindowTitle;runtime=$jvm[0];javafxNative=$glass[0];networkObservationSeconds=5}
if(!$owned.CloseMainWindow()){throw 'JavaFX window did not accept close'};if(!$owned.WaitForExit(15000)){throw 'DEMO did not exit after close'};$checks.Add('ROUND_'+$round+'_CLOSED_NORMALLY');if($launcher.Id -ne $owned.Id -and !$launcher.WaitForExit(10000)){throw 'Parent launcher did not exit'};$owned=$null;$launcher=$null
}
}catch{$failure=$_.Exception.Message}finally{
$env:JAVA_HOME=$oldJava;$env:PATH=$oldPath
if($owned -and !$owned.HasExited){Stop-Process -Id $owned.Id -ErrorAction SilentlyContinue};if($launcher -and !$launcher.HasExited){Stop-Process -Id $launcher.Id -ErrorAction SilentlyContinue}
[pscustomobject]@{status=if($failure){'BLOCKED_OR_FAILED'}else{'PASS'};pass=$checks.Count;checks=@($checks);rounds=$rounds;failure=$failure;launcher=($Destination+'\BYX-MVP.exe');sha256=(Get-FileHash -LiteralPath $exe -Algorithm SHA256).Hash.ToLower();signature=(Get-AuthenticodeSignature -LiteralPath $exe).Status.ToString();visualUat='NOT_EXECUTED';physicalInternetDisconnect='NOT_EXECUTED';note='Stage presence, packaged native/JVM module paths, close/reopen and sampled process-owned endpoints; not human visual UAT or proof of all-time network absence'}|ConvertTo-Json -Depth 6|Set-Content -LiteralPath $Report -Encoding UTF8
}
if($failure){throw ('PACKAGED_LAUNCHER_GATE: '+$failure)}
'PACKAGED_LAUNCHER_SMOKE_PASS'