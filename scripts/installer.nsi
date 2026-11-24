Unicode true
!include "LogicLib.nsh"
!include "FileFunc.nsh"
!include "x64.nsh"
!include "WinVer.nsh"
Name "Swapy ${VERSION}"
OutFile "${OUTPUT_FILE}"
InstallDir "$LOCALAPPDATA\Programs\Swapy"
InstallDirRegKey HKCU "Software\Swapy" "InstallPath"
RequestExecutionLevel user
SetCompressor zlib
ShowInstDetails show
ShowUninstDetails show
Page instfiles
UninstPage instfiles
Var Arguments
Var NoIntegration
Function .onInit
  ${IfNot} ${RunningX64}
    MessageBox MB_ICONSTOP|MB_OK "Swapy requires 64-bit Windows 10 or 11." /SD IDOK
    SetErrorLevel 1
    Abort
  ${EndIf}
  ${IfNot} ${AtLeastWin10}
    MessageBox MB_ICONSTOP|MB_OK "Swapy requires Windows 10 or 11." /SD IDOK
    SetErrorLevel 1
    Abort
  ${EndIf}
  SetRegView 64
  SetShellVarContext current
  ${GetParameters} $Arguments
  ClearErrors
  ${GetOptions} $Arguments "/NOINTEGRATION" $0
  IfErrors +2
  StrCpy $NoIntegration "yes"
FunctionEnd
Section "Install"
  IfFileExists "$INSTDIR\installed.flag" stop_previous
  IfFileExists "$INSTDIR\swapy.jar" unmanaged_directory copy_files
unmanaged_directory:
  MessageBox MB_ICONSTOP|MB_OK "This folder contains a portable or unmanaged installation. Choose an unused installation directory." /SD IDOK
  SetErrorLevel 1
  Abort
stop_previous:
  ExecWait '"$SYSDIR\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File "$INSTDIR\scripts\launch.ps1" -Stop -ForUpgrade' $0
  ${If} $0 != 0
    MessageBox MB_ICONSTOP|MB_OK "Could not stop the previous instance. Pause active scans or finish tasks, then try again." /SD IDOK
    SetErrorLevel 1
    Abort
  ${EndIf}
copy_files:
  SetOutPath "$INSTDIR"
  SetOverwrite on
  ClearErrors
  File /r "${STAGE_PATH}\*"
  IfErrors extraction_failed extraction_ok
extraction_failed:
  MessageBox MB_ICONSTOP|MB_OK "Installation files could not be extracted. Check free disk space and file permissions, then run setup again." /SD IDOK
  SetErrorLevel 1
  Abort
extraction_ok:
  FileOpen $0 "$INSTDIR\installed.flag" w
  FileWrite $0 "Swapy ${VERSION} per-user install$\r$\n"
  FileClose $0
  WriteUninstaller "$INSTDIR\Uninstall.exe"
  ${If} $NoIntegration != "yes"
    CreateDirectory "$SMPROGRAMS\Swapy"
    CreateShortcut "$SMPROGRAMS\Swapy\Start Swapy.lnk" "$INSTDIR\Start Swapy.cmd"
    CreateShortcut "$SMPROGRAMS\Swapy\Stop Swapy.lnk" "$INSTDIR\Stop Swapy.cmd"
    CreateShortcut "$SMPROGRAMS\Swapy\Uninstall Swapy.lnk" "$INSTDIR\Uninstall.exe"
    CreateShortcut "$DESKTOP\Swapy.lnk" "$INSTDIR\Start Swapy.cmd"
    WriteRegStr HKCU "Software\Swapy" "InstallPath" "$INSTDIR"
    WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Swapy" "DisplayName" "Swapy"
    WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Swapy" "DisplayVersion" "${VERSION}"
    WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Swapy" "UninstallString" '$\"$INSTDIR\Uninstall.exe$\"'
    WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Swapy" "NoModify" 1
    WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Swapy" "NoRepair" 1
  ${EndIf}
  ClearErrors
  ${GetOptions} $Arguments "/NOAUTOSTART" $0
  IfErrors start_app finished
start_app:
  ExecShell "open" "$INSTDIR\Start Swapy.cmd"
finished:
SectionEnd
Function un.onInit
  SetRegView 64
  SetShellVarContext current
  IfFileExists "$INSTDIR\installed.flag" +4
    MessageBox MB_ICONSTOP|MB_OK "This directory is not a managed Swapy installation." /SD IDOK
    SetErrorLevel 1
    Abort
FunctionEnd
Section "Uninstall"
  ExecWait '"$SYSDIR\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File "$INSTDIR\scripts\launch.ps1" -Stop -ForUpgrade' $0
  ${If} $0 != 0
    MessageBox MB_ICONSTOP|MB_OK "Finish active tasks before uninstalling Swapy." /SD IDOK
    SetErrorLevel 1
    Abort
  ${EndIf}
  !include "${UNINSTALL_LIST}"
  Delete "$INSTDIR\installed.flag"
  Delete "$INSTDIR\Uninstall.exe"
  RMDir "$INSTDIR"
  ReadRegStr $0 HKCU "Software\Swapy" "InstallPath"
  ${If} $0 == $INSTDIR
    Delete "$DESKTOP\Swapy.lnk"
    Delete "$SMPROGRAMS\Swapy\Start Swapy.lnk"
    Delete "$SMPROGRAMS\Swapy\Stop Swapy.lnk"
    Delete "$SMPROGRAMS\Swapy\Uninstall Swapy.lnk"
    RMDir "$SMPROGRAMS\Swapy"
    DeleteRegKey HKCU "Software\Swapy"
    DeleteRegKey HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Swapy"
  ${EndIf}
SectionEnd
