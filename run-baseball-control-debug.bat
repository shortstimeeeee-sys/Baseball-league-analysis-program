@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo [제어판] 컴파일 후 java로 실행 (오류 시 메시지가 이 창에 출력됩니다)
call mvn -q compile -q
if errorlevel 1 (
    echo 컴파일 실패.
    pause
    exit /b 1
)
java -cp "target/classes" com.baseball.presentation.desktop.ControlPanelApp
echo.
echo 종료됨. 아무 키나 누르면 닫습니다.
pause
