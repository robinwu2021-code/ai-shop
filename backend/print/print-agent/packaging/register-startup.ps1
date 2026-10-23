param([Parameter(Mandatory=$true)][string]$Executable,[Parameter(Mandatory=$true)][string]$Configuration)
$ErrorActionPreference='Stop'
$AgentExecutable=(Resolve-Path $Executable).Path
$AgentConfiguration=(Resolve-Path $Configuration).Path
# DPAPI credentials belong to this user. Do not run the paired agent as LocalSystem.
$AgentUser=[System.Security.Principal.WindowsIdentity]::GetCurrent().Name
$Action=New-ScheduledTaskAction -Execute $AgentExecutable -Argument ('"'+$AgentConfiguration+'"')
$Trigger=New-ScheduledTaskTrigger -AtLogOn -User $AgentUser
$Principal=New-ScheduledTaskPrincipal -UserId $AgentUser -LogonType Interactive -RunLevel Limited
$Settings=New-ScheduledTaskSettingsSet -MultipleInstances IgnoreNew -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1)
Register-ScheduledTask -TaskName 'AiShopPrintAgent' -Action $Action -Trigger $Trigger -Principal $Principal -Settings $Settings -Description 'Order printing agent; runs only under its paired Windows user.' -Force | Out-Null
