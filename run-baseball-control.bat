@echo off
chcp 65001 >nul
REM Baseball Insight 제어판 - 이 폴더에서 실행하면 서버는 "소스 기준(mvn spring-boot:run)"으로 뜹니다.
REM → UI/템플릿 수정 후 서버만 재시작하면 바로 반영됩니다. JAR 재빌드 불필요.
cd /d "%~dp0"

REM 항상 최신 코드로 컴파일 후 제어판 실행
echo [제어판] 컴파일 중...
call mvn -q compile -q
if errorlevel 1 (
    echo 컴파일 실패. Maven이 설치되어 있는지 확인해 주세요.
    pause
    exit /b 1
)
start "" javaw -cp "target/classes" com.baseball.presentation.desktop.ControlPanelApp
exit /b 0
