# Baseball League Analysis (Baseball Insight)

야구 리그·팀·선수·경기 기록 및 분석을 위한 웹 애플리케이션입니다.

## 기술 스택

- **Java 17** + **Spring Boot 3.2** + **Maven**
- **H2 Database** (파일 기반, 재시작 후에도 데이터 유지)
- **Spring Data JPA** + **Thymeleaf**
- 공통 레이아웃·스타일 적용 (다크 테마, 반응형)

## 실행 방법

```bash
# 루트 디렉터리에서
mvn spring-boot:run
```

브라우저에서 **http://localhost:8080** 접속.

## 데이터 초기화 방지

- H2는 **파일 DB** 사용 (`./data/baseball-league-db`)
- `spring.jpa.hibernate.ddl-auto=update` 로 스키마만 갱신, 데이터는 유지
- `create` / `create-drop` 사용하지 않음

## H2 콘솔

개발 시 DB 확인: **http://localhost:8080/h2-console**

- JDBC URL: `jdbc:h2:file:./data/baseball-league-db`
- User: `sa`, Password: (비워두기)

## 프로젝트 구조

- `domain` – 엔티티(League, Season, Team, Player, Game), Repository
- `application` – 서비스(리그/팀/선수/경기)
- `presentation.web` – 컨트롤러, 폼 DTO
- `config` – H2 콘솔 등 설정
- `templates` – Thymeleaf (공통 레이아웃 + 리그/팀/선수/경기 화면)
- `static/css/common.css` – 공통 스타일

## 기능

- **대시보드**: 리그/팀/선수/경기 수 요약
- **리그 관리**: 등록·수정·삭제·목록
- **팀 관리**: 리그별 필터, 등록·수정·삭제
- **선수 관리**: 팀별 필터, 포지션·등번호 등
- **경기 관리**: 홈/원정, 일시, 스코어, 상태

추가 기능(파싱, 상세 기록, 분석·예측)은 추후 확장 예정입니다.
