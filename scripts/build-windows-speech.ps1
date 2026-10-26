param([string]$OutputPath = "$PSScriptRoot/../build/KinuvoSpeech.exe")
$ErrorActionPreference = 'Stop'
$OutputPath = [IO.Path]::GetFullPath($OutputPath)
New-Item -ItemType Directory -Force (Split-Path $OutputPath) | Out-Null
$framework = "$env:WINDIR/Microsoft.NET/Framework64/v4.0.30319"
Add-Type -AssemblyName System.Speech, System.Web.Extensions
$speechAssembly = [System.Speech.Recognition.SpeechRecognitionEngine].Assembly.Location
$jsonAssembly = [System.Web.Script.Serialization.JavaScriptSerializer].Assembly.Location
$sourcePath = [IO.Path]::GetFullPath("$PSScriptRoot/../apps/desktop/speech/WindowsSpeech.cs")
& "$framework/csc.exe" /nologo /target:exe /platform:x64 /optimize+ "/out:$OutputPath" "/reference:$speechAssembly" "/reference:$jsonAssembly" "$sourcePath"
if ($LASTEXITCODE -ne 0) { throw 'Windows speech helper compilation failed' }
