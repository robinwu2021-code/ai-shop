param([ValidateSet('app-image','exe','msi')][string]$Type='app-image')
$ErrorActionPreference='Stop'
$AgentRoot=Split-Path -Parent $PSScriptRoot
$Target=Join-Path $AgentRoot 'target'
$Jar=Get-ChildItem $Target -Filter 'print-agent-*.jar' | Where-Object {$_.Name -notmatch '(sources|tests|javadoc)'} | Select-Object -First 1
if (-not $Jar) { throw 'Run the unified Maven package stage before packaging the Windows agent.' }
$InputDir=Join-Path $Target 'installer-input'
New-Item -ItemType Directory -Force $InputDir | Out-Null
Copy-Item $Jar.FullName $InputDir -Force
Copy-Item (Join-Path $Target 'lib') $InputDir -Recurse -Force
jpackage --type $Type --name 'AiShopPrintAgent' --app-version '1.0.0' --input $InputDir --main-jar $Jar.Name --main-class ai.neargo.shop.print.agent.AgentMain --java-options '-Dfile.encoding=UTF-8' --dest (Join-Path $Target 'installer') --win-console
if ($LASTEXITCODE -ne 0) { throw 'Windows agent packaging failed. EXE/MSI requires the matching WiX toolset.' }
