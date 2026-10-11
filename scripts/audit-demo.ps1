param([string]$Project=(Split-Path $PSScriptRoot -Parent),[string]$Image,[Parameter(Mandatory)][string]$Report)
$Project=[IO.Path]::GetFullPath($Project);if($Image){$Image=[IO.Path]::GetFullPath($Image)}
$ErrorActionPreference='Stop';Add-Type -AssemblyName System.IO.Compression.FileSystem
$findings=[Collections.Generic.List[string]]::new()
$files=Get-ChildItem -LiteralPath $Project -Recurse -File|Where-Object {$_.FullName -notmatch '\\(\.git|target|dist|\.tools)\\'}
foreach($file in $files){
$r=$file.FullName.Substring($Project.Length+1).Replace('\','/')
if($r -match '(^|/)(\.DS_Store|\.env|id_rsa|id_ed25519)$' -or $file.Extension -match '^\.(db|sqlite|sqlite3|p12|pfx|pem|key|csv|parquet|exe|dll|zip|jar)$'){$findings.Add('Forbidden source artifact: '+$r)}
if($file.Extension -notmatch '^\.(png|ttf)$'){
$t=[IO.File]::ReadAllText($file.FullName)
if($t -match '(gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,}|AKIA[A-Z0-9]{16}|-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----|eyJ[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,})'){$findings.Add('Credential pattern: '+$r)}
if($t -match '(C:\\Users\\Business|C:\\src\\BYX-artifacts|179\.118\.192\.12|192\.168\.15\.13)'){$findings.Add('Owner-local content: '+$r)}
}
}
$source=Get-ChildItem ($Project+'\src\main\java') -Filter '*.java' -Recurse
foreach($file in $source){$t=[IO.File]::ReadAllText($file.FullName);if($t -match '(ProcessBuilder|Runtime\.getRuntime\(\)\.exec|java\.net\.(http|Socket|ServerSocket)|HttpClient|WebSocket|java\.sql|ServiceMain|System\.getenv\()'){$findings.Add('Operational source capability: '+$file.Name)}}
$provenance=Get-Content ($Project+'\SOURCE-PROVENANCE.json') -Raw|ConvertFrom-Json
foreach($p in $provenance){if((Get-FileHash -LiteralPath ($Project+'\'+$p.path) -Algorithm SHA256).Hash.ToLower() -cne $p.snapshotSha256){$findings.Add('Provenance mismatch: '+$p.path)}}
$jar=$Project+'\target\byx-mvp-demo-0.1.0.jar';if($Image){$jar=$Image+'\app\byx-mvp-demo-0.1.0.jar'}
if(Test-Path -LiteralPath $jar){
$z=[IO.Compression.ZipFile]::OpenRead($jar)
try{foreach($entry in $z.Entries){$p=$entry.FullName;if($p -match '\.class$'){$base=($p -replace '\$.*\.class$','.class') -replace '\.class$','.java';if(!(Test-Path -LiteralPath ($Project+'\src\main\java\'+$base))){$findings.Add('Class outside source allowlist: '+$p)};if($p -match '^(panel/(auth/|security/|localservice/|service/|adapter/|ipc/|txview/)|byx/(service|signer|custody)/)'){$findings.Add('Privileged payload: '+$p)}};if($p -match '(\.DS_Store|\.sqlite|\.pem|\.pfx|\.csv|\.parquet)$'){$findings.Add('Forbidden JAR entry: '+$p)}}}finally{$z.Dispose()}
}
if($Image){
$lock=Get-Content ($Project+'\DEPENDENCIES.json') -Raw|ConvertFrom-Json
foreach($dep in $lock){if((Get-FileHash -LiteralPath ($Image+'\app\'+$dep.file) -Algorithm SHA256).Hash.ToLower() -cne $dep.sha256){$findings.Add('Dependency mismatch: '+$dep.file)}}
if(@(Get-ChildItem ($Image+'\app') -Filter '*.jar').Count -ne (@($lock).Count+1)){$findings.Add('Extra JAR payload')}
$cfg=[IO.File]::ReadAllText($Image+'\app\BYX-MVP.cfg');if($cfg -notmatch 'app.mainclass=byx.demo.DemoMain'){$findings.Add('Incorrect entrypoint')}
foreach($p in @('runtime\bin\server\jvm.dll','runtime\lib\modules','runtime\legal\java.base\LICENSE','app\native\glass.dll','app\licenses\JavaFX-21-LICENSE.txt')){if(!(Test-Path -LiteralPath ($Image+'\'+$p))){$findings.Add('Missing runtime/license: '+$p)}}
foreach($f in Get-ChildItem $Image -Recurse -File){if($f.Name -match '(ServiceMain|signer|custody|\.DS_Store)' -or $f.Extension -match '^\.(sqlite|db|pfx|pem|key|csv|parquet)$'){$findings.Add('Forbidden distribution payload: '+$f.Name)}}
}
[pscustomobject]@{status=if($findings.Count){'FAIL'}else{'PASS'};sourceFiles=@($files).Count;mainJavaFiles=@($source).Count;intentionalPublicCredential='teste-byx / byx123123';findings=@($findings);scope='source, original-file hashes, payload class allowlist, locked dependencies, runtime, licenses; not a vulnerability guarantee'}|ConvertTo-Json -Depth 5|Set-Content -LiteralPath $Report -Encoding UTF8
if($findings.Count){throw ('Audit failed: '+($findings -join '; '))}
'PUBLICATION_AUDIT_PASS'