param([Parameter(Mandatory)][string]$Image,[Parameter(Mandatory)][string]$Evidence,[string]$Jdk=$env:JAVA_HOME)
$Image=[IO.Path]::GetFullPath($Image);$Evidence=[IO.Path]::GetFullPath($Evidence)
$ErrorActionPreference='Stop';$project=Split-Path $PSScriptRoot -Parent
$fx=@(Get-ChildItem -LiteralPath ($Image+'\app') -Filter 'javafx-*-win.jar'|ForEach-Object FullName)
$deps=@(Get-ChildItem -LiteralPath ($Image+'\app') -Filter '*.jar'|Where-Object Name -NotLike 'javafx-*'|ForEach-Object FullName)
$cp=@(($project+'\target\test-classes'))+$deps
New-Item -ItemType Directory -Path $Evidence -Force|Out-Null
$ErrorActionPreference='Continue';& ($Jdk+'\bin\java.exe') --module-path ($fx -join ';') --add-modules javafx.controls -cp ($cp -join ';') byx.demo.DemoPackagedUiProbe $Evidence *> ($Evidence+'\ui.log');$code=$LASTEXITCODE;$ErrorActionPreference='Stop'
if($code){throw 'Packaged JAR visual automation failed; see ui.log'}
Get-Content -LiteralPath ($Evidence+'\packaged-ui-result.txt')