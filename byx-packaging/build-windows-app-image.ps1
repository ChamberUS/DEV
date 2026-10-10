# Local DEFAULT Panel QA only. Never launches the app or changes security policy.
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$OutputDirectory,
    [Parameter(Mandatory=$true)][string]$JdkHome,
    [Parameter(Mandatory=$true)][string]$MavenHome,
    [string]$GitExecutable = 'git.exe'
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ($env:OS -ne 'Windows_NT' -or ![Environment]::Is64BitOperatingSystem) { throw 'Windows x64 required.' }
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$output = [IO.Path]::GetFullPath($OutputDirectory).TrimEnd('\')
if ($output -eq $repo -or $output.StartsWith($repo + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Output must be outside the repository.' }
if (Test-Path -LiteralPath $output) { throw 'Output already exists; choose a new directory.' }
$java = Join-Path $JdkHome 'bin\java.exe'
$jpackage = Join-Path $JdkHome 'bin\jpackage.exe'
$maven = Join-Path $MavenHome 'bin\mvn.cmd'
foreach ($tool in @($java,$jpackage,$maven)) { if (!(Test-Path -LiteralPath $tool)) { throw "Missing tool: $tool" } }
if ($env:MAVEN_ARGS -or $env:MAVEN_OPTS -or $env:JAVA_TOOL_OPTIONS -or $env:JDK_JAVA_OPTIONS -or $env:_JAVA_OPTIONS) { throw 'Use a shell without injected Maven/JVM options.' }
$oldJavaHome = $env:JAVA_HOME
$oldPath = $env:PATH
try {
    $env:JAVA_HOME = [IO.Path]::GetFullPath($JdkHome)
    $env:PATH = "$env:JAVA_HOME\bin;$MavenHome\bin;$oldPath"
    $jpackageVersion = & $jpackage --version
    if ($LASTEXITCODE -ne 0 -or "$jpackageVersion".Trim() -ne '21.0.12.1') { throw 'Qualified jpackage version 21.0.12.1 required.' }
    $mavenVersion = & $maven -version
    if ($LASTEXITCODE -ne 0 -or ($mavenVersion -join "`n") -notmatch 'Apache Maven 3\.9\.9') { throw 'Maven 3.9.9 required.' }
    if (($mavenVersion -join "`n") -notmatch 'Java version: 21\.0\.12\.1') { throw 'Qualified Java 21.0.12.1 required.' }
    $head = & $GitExecutable -C $repo rev-parse HEAD
    if ($LASTEXITCODE -ne 0) { throw 'Cannot read Git provenance.' }
    $paths = & $GitExecutable -C $repo ls-files -- mvp-binance-panel/pom.xml mvp-binance-panel/src/main mvp-binance-panel/src/build-default mvp-binance-panel/src/wallet-build
    if ($LASTEXITCODE -ne 0 -or !$paths) { throw 'No tracked Panel build inputs.' }
    New-Item -ItemType Directory -Path $output | Out-Null
    $project = Join-Path $output 'build-project'
    $input = Join-Path $output 'input'
    New-Item -ItemType Directory -Path $project,$input | Out-Null
    $sourceManifest = foreach ($path in $paths) {
        $source = Join-Path $repo $path
        $relative = $path.Substring('mvp-binance-panel/'.Length)
        $target = Join-Path $project $relative
        New-Item -ItemType Directory -Path (Split-Path $target) -Force | Out-Null
        Copy-Item -LiteralPath $source -Destination $target
        [pscustomobject]@{ Path=$relative; SHA256=(Get-FileHash -LiteralPath $target).Hash }
    }
    $sourceManifest | ConvertTo-Json | Set-Content "$output\build-input-manifest.json" -Encoding UTF8
    [pscustomobject]@{HEAD="$head";Jpackage="$jpackageVersion";Maven=$mavenVersion;Profile='DEFAULT';Wallet='DISABLED'} | ConvertTo-Json | Set-Content "$output\provenance.json" -Encoding UTF8
    $mavenArgs = @('-f',"$project\pom.xml",'-Dmaven.test.skip=true','-Dbyx.panel.sources=build-default','-Dbyx.wallet.capability=DISABLED','package','org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy-dependencies','-DincludeScope=runtime',"-DoutputDirectory=$input")
    $mavenArgs | ConvertTo-Json | Set-Content "$output\maven-arguments.json" -Encoding UTF8
    & $maven @mavenArgs 2>&1 | Tee-Object -FilePath "$output\maven-package.log"
    if ($LASTEXITCODE -ne 0) { throw "Maven failed: $LASTEXITCODE. Evidence retained." }
    $lock = Get-Content (Join-Path $PSScriptRoot 'windows-runtime-dependencies.json') -Raw | ConvertFrom-Json
    $actual = @(Get-ChildItem $input -File -Filter '*.jar')
    if ($actual.Count -ne $lock.Count) { throw 'Runtime dependency inventory differs from reviewed lock.' }
    foreach ($dependency in $lock) {
        $file = Join-Path $input $dependency.File
        if (!(Test-Path $file) -or (Get-FileHash $file).Hash -ne $dependency.SHA256) { throw "Dependency drift: $($dependency.File). Review required; no automatic lock update." }
    }
    Copy-Item "$project\target\mvp-binance-panel-0.1.0.jar" $input
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $native = Join-Path $input 'native'
    New-Item -ItemType Directory -Path $native | Out-Null
    $nativeManifest = @()
    foreach ($name in @('javafx-graphics-21.0.5-win.jar','jna-5.17.0.jar','sqlite-jdbc-3.46.1.0.jar')) {
        $zip = [IO.Compression.ZipFile]::OpenRead((Join-Path $input $name))
        try {
            foreach ($entry in $zip.Entries) {
                $selected = ($name -like 'javafx*' -and $entry.FullName.EndsWith('.dll')) -or $entry.FullName -eq 'com/sun/jna/win32-x86-64/jnidispatch.dll' -or $entry.FullName -eq 'org/sqlite/native/Windows/x86_64/sqlitejdbc.dll'
                if (!$selected) { continue }
                $target = Join-Path $native $entry.Name
                [IO.Compression.ZipFileExtensions]::ExtractToFile($entry,$target,$false)
                $nativeManifest += [pscustomobject]@{Jar=$name;Entry=$entry.FullName;File=$entry.Name;SHA256=(Get-FileHash $target).Hash}
            }
        } finally { $zip.Dispose() }
    }
    $nativeManifest | ConvertTo-Json | Set-Content "$output\windows-native-inventory.json" -Encoding UTF8
    foreach ($required in @('glass.dll','prism_d3d.dll','javafx_font.dll','jnidispatch.dll','sqlitejdbc.dll')) {
        if (!(Test-Path (Join-Path $native $required))) { throw "Missing Windows native library: $required" }
    }
    $jar = [IO.Compression.ZipFile]::OpenRead("$input\mvp-binance-panel-0.1.0.jar")
    try {
        foreach ($resource in Get-ChildItem "$project\src\main\resources" -Recurse -File) {
            $rel = $resource.FullName.Substring(("$project\src\main\resources").Length+1).Replace('\','/')
            $entry = $jar.GetEntry($rel)
            if (!$entry) { throw "Missing resource: $rel" }
            $stream = $entry.Open(); $sha = [Security.Cryptography.SHA256]::Create()
            try { $hash = [BitConverter]::ToString($sha.ComputeHash($stream)).Replace('-','') } finally { $stream.Dispose(); $sha.Dispose() }
            if ($hash -ne (Get-FileHash $resource.FullName).Hash) { throw "Resource mismatch: $rel" }
        }
        $capability = $jar.GetEntry('panel/wallet-capability.txt')
        if (!$capability) { throw 'Missing wallet capability gate.' }
        $reader = New-Object IO.StreamReader($capability.Open())
        try { if ($reader.ReadToEnd().Trim() -ne 'DISABLED') { throw 'Wallet capability must be DISABLED.' } } finally { $reader.Dispose() }
        $forbidden = @($jar.Entries | Where-Object { $_.FullName -match '(^|/)(QaApp|PublicVisualQa).*\.class$|^panel/txview/|\.(db|sqlite|pem|p12|pfx|key|env|mobileprovision|log)$' })
        if ($forbidden.Count) { throw 'Unexpected QA, credential or runtime payload in application JAR.' }
    } finally { $jar.Dispose() }
    $options = @('-Dfile.encoding=UTF-8','-XX:+DisableAttachMechanism','--add-opens=java.base/sun.nio.ch=ALL-UNNAMED','--add-opens=java.base/java.io=ALL-UNNAMED','-Djava.library.path=$APPDIR/native','-Djna.boot.library.path=$APPDIR/native','-Djna.nosys=true','-Dorg.sqlite.lib.path=$APPDIR/native','-Dorg.sqlite.lib.name=sqlitejdbc.dll')
    $packageArgs = @('--type','app-image','--name','BYX-MVP','--dest',"$output\image",'--input',$input,'--main-jar','mvp-binance-panel-0.1.0.jar','--main-class','panel.app.Main','--app-version','0.1.0','--vendor','BYX-MVP','--description','BYX-MVP Windows DEFAULT public UI - local QA','--add-modules','java.base,java.desktop,java.naming,java.net.http,java.sql,java.logging,java.xml,java.management,java.scripting,java.security.jgss,jdk.jfr,jdk.unsupported,jdk.crypto.ec,jdk.charsets,jdk.zipfs','--verbose')
    foreach ($option in $options) { $packageArgs += @('--java-options',$option) }
    $packageArgs | ConvertTo-Json | Set-Content "$output\jpackage-arguments.json" -Encoding UTF8
    & $jpackage @packageArgs 2>&1 | Tee-Object -FilePath "$output\jpackage.log"
    if ($LASTEXITCODE -ne 0) { throw "jpackage failed: $LASTEXITCODE. Evidence retained." }
    $image = "$output\image\BYX-MVP"
    Get-ChildItem $image -Recurse -File | ForEach-Object { [pscustomobject]@{Path=$_.FullName.Substring($image.Length+1);Bytes=$_.Length;SHA256=(Get-FileHash $_.FullName).Hash} } | ConvertTo-Json | Set-Content "$output\app-image-sha256.json" -Encoding UTF8
    $signature = Get-AuthenticodeSignature "$image\BYX-MVP.exe"
    [pscustomobject]@{Status=$signature.Status.ToString();SignerPresent=($null -ne $signature.SignerCertificate);LauncherSHA256=(Get-FileHash "$image\BYX-MVP.exe").Hash} | ConvertTo-Json | Set-Content "$output\launcher-signature.json" -Encoding UTF8
    Write-Output "BUILT: $image (not launched; runtime qualification is separate)"
} finally {
    $env:JAVA_HOME = $oldJavaHome
    $env:PATH = $oldPath
}
