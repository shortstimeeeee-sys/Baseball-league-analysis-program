' 제어판 실행 시 CMD 창이 전혀 보이지 않도록 함 (바탕화면 바로가기에 이 파일 사용 권장)
Set sh = CreateObject("WScript.Shell")
sh.CurrentDirectory = CreateObject("Scripting.FileSystemObject").GetParentFolderName(WScript.ScriptFullName)
sh.Run "cmd /c run-baseball-control.bat", 0, False
