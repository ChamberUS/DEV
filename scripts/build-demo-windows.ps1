param([string]$Jdk=$env:JAVA_HOME,[string]$Maven='mvn.cmd',[string]$Wix,[Parameter(Mandatory)][string]$Output,[switch]$Offline,[switch]$IncludeInstaller)
$Output=[IO.Path]::GetFullPath($Output)
$ErrorActionPreference='Stop'
$project=Split-Path $PSScriptRoot -Parent
if(!$Jdk -or !(Test-Path -LiteralPath ($Jdk+'\bin\jpackage.exe'))){throw 'JDK 21 with jpackage required'}
$ErrorActionPreference='Continue';$version=& ($Jdk+'\bin\java.exe') -version 2>&1|Out-String;$ErrorActionPreference='Stop'
if($version -notmatch 'version "21\.' -or $version -notmatch '64-Bit'){throw 'Java 21 x64 required'}
if($IncludeInstaller -and (!(Test-Path -LiteralPath ($Wix+'\candle.exe')) -or !(Test-Path -LiteralPath ($Wix+'\light.exe')))){throw 'Isolated WiX 3 required'}
if(Test-Path -LiteralPath $Output){throw 'Output must be a new directory'}
New-Item -ItemType Directory -Path $Output |Out-Null
$oldPath=$env:PATH;$oldJava=$env:JAVA_HOME
try{
$env:JAVA_HOME=$Jdk;$env:PATH=$Jdk+'\bin;'+$env:PATH;if($IncludeInstaller){$env:PATH=$Wix+';'+$env:PATH}
Push-Location $project
try{
$argsMvn=@('-B','org.apache.maven.plugins:maven-clean-plugin:3.2.0:clean','package','org.apache.maven.plugins:maven-dependency-plugin:3.7.0:copy-dependencies','-DincludeScope=runtime',('-DoutputDirectory='+$Output+'\runtime-deps'));if($Offline){$argsMvn=@('-o')+$argsMvn}else{$argsMvn+=@('-Dmaven.repo.local='+$Output+'\maven-cache')}
$ErrorActionPreference='Continue';& $Maven @argsMvn *> ($Output+'\build.log');$code=$LASTEXITCODE;$ErrorActionPreference='Stop';if($code){throw 'Maven tests/package failed'}
$lock=Get-Content -LiteralPath ($project+'\DEPENDENCIES.json') -Raw|ConvertFrom-Json
foreach($dep in $lock){$p=$Output+'\runtime-deps\'+$dep.file;if(!(Test-Path -LiteralPath $p) -or (Get-FileHash -LiteralPath $p -Algorithm SHA256).Hash.ToLower() -cne $dep.sha256){throw ('Dependency hash gate failed: '+$dep.file)}}
if(@(Get-ChildItem ($Output+'\runtime-deps') -Filter '*.jar').Count -ne @($lock).Count){throw 'Unexpected dependency'}
$inputDir=$Output+'\input';New-Item -ItemType Directory -Path $inputDir |Out-Null
Copy-Item -LiteralPath ($project+'\target\byx-mvp-demo-0.1.0.jar') -Destination $inputDir
Get-ChildItem -LiteralPath ($Output+'\runtime-deps') -Filter '*.jar'|ForEach-Object {Copy-Item -LiteralPath $_.FullName -Destination $inputDir}
Copy-Item -LiteralPath ($project+'\licenses') -Destination $inputDir -Recurse
Copy-Item -LiteralPath ($project+'\THIRD-PARTY-NOTICES.md') -Destination $inputDir
$native=$inputDir+'\native';New-Item -ItemType Directory -Path $native |Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
$z=[IO.Compression.ZipFile]::OpenRead($inputDir+'\javafx-graphics-21.0.5-win.jar')
try{foreach($entry in $z.Entries){if($entry.Name.EndsWith('.dll')){[IO.Compression.ZipFileExtensions]::ExtractToFile($entry,($native+'\'+$entry.Name),$false)}}}finally{$z.Dispose()}
& ($Jdk+'\bin\jlink.exe') --add-modules java.base,java.desktop,java.logging,java.xml,java.management,java.naming,jdk.unsupported --strip-debug --no-header-files --no-man-pages --output ($Output+'\runtime');if($LASTEXITCODE){throw 'jlink failed'}
& ($Jdk+'\bin\jpackage.exe') --type app-image --dest ($Output+'\image') --name BYX-MVP --input $inputDir --main-jar byx-mvp-demo-0.1.0.jar --main-class byx.demo.DemoMain --app-version 0.1.0 --vendor ChamberUS --description 'Offline USER DEMO; simulated data; no real operations' --runtime-image ($Output+'\runtime') --java-options '-Djava.library.path=$APPDIR/native' --java-options '-Djavafx.cachedir=$APPDIR/native' --java-options '-XX:+DisableAttachMechanism';if($LASTEXITCODE){throw 'App image failed'}
$artifacts=@('BYX-MVP-Demo-Windows-x64-Portable.zip')
if($IncludeInstaller){
& ($Jdk+'\bin\jpackage.exe') --type exe --dest ($Output+'\installer') --name BYX-MVP --app-version 0.1.0 --vendor ChamberUS --app-image ($Output+'\image\BYX-MVP') --win-per-user-install --win-shortcut --win-menu --win-dir-chooser;if($LASTEXITCODE){throw 'Installer failed'}
$setup=@(Get-ChildItem ($Output+'\installer') -Filter '*.exe');if($setup.Count -ne 1){throw 'Exactly one installer expected'}
Copy-Item -LiteralPath $setup[0].FullName -Destination ($Output+'\BYX-MVP-Demo-Windows-x64-Setup.exe');$artifacts+=@('BYX-MVP-Demo-Windows-x64-Setup.exe')
}
[IO.Compression.ZipFile]::CreateFromDirectory(($Output+'\image\BYX-MVP'),($Output+'\BYX-MVP-Demo-Windows-x64-Portable.zip'))
$sum=foreach($name in $artifacts){(Get-FileHash -Algorithm SHA256 -LiteralPath ($Output+'\'+$name)).Hash.ToLower()+'  '+$name}
[IO.File]::WriteAllLines(($Output+'\SHA256SUMS.txt'),$sum,[Text.UTF8Encoding]::new($false))
Get-ChildItem -LiteralPath ($Output+'\image\BYX-MVP') -Recurse -File|ForEach-Object {[pscustomobject]@{path=$_.FullName.Substring(($Output+'\image\BYX-MVP\').Length);bytes=$_.Length;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLower()}}|ConvertTo-Json -Depth 4|Set-Content -LiteralPath ($Output+'\distribution-inventory.json') -Encoding UTF8
}finally{Pop-Location}
}finally{$env:PATH=$oldPath;$env:JAVA_HOME=$oldJava}