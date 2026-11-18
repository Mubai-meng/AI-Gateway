@echo off
rem Usage: sync-core.bat            -> pull the pinned ref from upstream.ref and replace core/
rem        sync-core.bat -Ref v2.32.0 -> pin a release tag
rem        sync-core.bat -Check     -> show latest upstream tag
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0sync-core.ps1" %*
