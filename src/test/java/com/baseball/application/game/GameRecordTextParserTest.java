package com.baseball.application.game;

import com.baseball.application.game.dto.GameRecordImportDto;
import com.baseball.domain.game.SubstitutionKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GameRecordTextParserTest {

    private final GameRecordTextParser parser = new GameRecordTextParser();

    @Test
    @DisplayName("빈 텍스트는 예외를 발생시킨다")
    void parse_emptyText_throwsException() {
        assertThatThrownBy(() -> parser.parse("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("간단한 중계 텍스트를 파싱해 GameRecordImportDto를 생성한다")
    void parse_simpleText_success() {
        String text = """
                1회초 LG 공격
                홍길동 선수 페이지
                홍길동3번타자타율 0.333
                홍길동 : 중견수 플라이 아웃
                한화 승리 확률 40.7% (-1.1%p)
                1구타격
                152km/h직구
                볼카운트1 - 0
                """;

        GameRecordImportDto dto = parser.parse(text);

        assertThat(dto).isNotNull();
        assertThat(dto.getGameInfo()).isNotNull();
        assertThat(dto.getGameInfo().getHomeTeamName()).isNotBlank();
        assertThat(dto.getGameInfo().getAwayTeamName()).isNotBlank();
        assertThat(dto.getPlateAppearances()).hasSize(1);
        GameRecordImportDto.PlateAppearanceRow pa = dto.getPlateAppearances().get(0);
        assertThat(pa.getInning()).isEqualTo(1);
        assertThat(pa.isTop()).isTrue();
        assertThat(pa.getBatterName()).isEqualTo("홍길동");
        assertThat(pa.getResultText()).contains("플라이");
        assertThat(pa.getResultText()).contains("아웃");
        assertThat(pa.getPitches()).isNotEmpty();
    }

    @Test
    @DisplayName("타석 결과(아웃)와 주자 플레이(포스아웃·터치아웃)가 모두 파싱된다")
    void parse_outResultsAndRunnerPlays_success() {
        String text = """
                1회초 LG 공격
                오스틴 선수 페이지
                오스틴5번타자타율 0.059
                2루주자 김현수 : 3루까지 진루
                1루주자 문보경 : 포스아웃 (유격수->2루수 2루 터치아웃)
                오스틴 : 유격수 앞 땅볼로 출루
                2구타격
                132km/h포크
                볼카운트0 - 1
                홍창기 선수 페이지
                홍창기1번타자타율 0.188
                홍창기 : 1루수 땅볼 아웃 (1루수 1루 터치아웃)
                2구타격
                118km/h커브
                볼카운트0 - 1
                """;

        GameRecordImportDto dto = parser.parse(text);

        assertThat(dto.getPlateAppearances()).hasSize(2);

        GameRecordImportDto.PlateAppearanceRow pa1 = dto.getPlateAppearances().get(0);
        assertThat(pa1.getBatterName()).isEqualTo("오스틴");
        assertThat(pa1.getResultText()).isEqualTo("유격수 앞 땅볼로 출루");
        assertThat(pa1.getRunnerPlays()).hasSize(2);
        assertThat(pa1.getRunnerPlays().get(0)).contains("3루까지 진루");
        assertThat(pa1.getRunnerPlays().get(1)).contains("포스아웃");
        assertThat(pa1.getRunnerPlays().get(1)).contains("터치아웃");

        GameRecordImportDto.PlateAppearanceRow pa2 = dto.getPlateAppearances().get(1);
        assertThat(pa2.getBatterName()).isEqualTo("홍창기");
        assertThat(pa2.getResultText()).contains("땅볼 아웃");
        assertThat(pa2.getResultText()).contains("터치아웃");
    }

    @Test
    @DisplayName("최신 이벤트가 위(1회말 블록 후 1회초)여도 타석·결과가 비지 않고 시간순으로 맞는다")
    void parse_newestFirstFeed_lotteMalThenKtCho_reordersHalves() {
        String text = """
                1회말 롯데 공격
                윤동희 선수 페이지
                윤동희5번타자타율 0.000
                윤동희 : 중견수 플라이 아웃
                1구타격
                볼카운트0 - 1
                황성빈 선수 페이지
                황성빈1번타자타율 0.000
                황성빈 : 유격수 땅볼 아웃
                1구타격
                1회초 KT 공격
                허경민 선수 페이지
                허경민5번타자타율 0.000
                허경민 : 1루수 땅볼 아웃
                1구타격
                최원준 선수 페이지
                최원준1번타자타율 0.000
                최원준 : 삼진 아웃
                1구타격
                """;

        GameRecordImportDto dto = parser.parse(text);

        assertThat(dto.getPlateAppearances()).hasSize(4);
        // 1회초: 붙여넣기는 5번→1번(최신순) → 시간순으로 1번→5번
        assertThat(dto.getPlateAppearances().get(0).getBatterName()).isEqualTo("최원준");
        assertThat(dto.getPlateAppearances().get(0).isTop()).isTrue();
        assertThat(dto.getPlateAppearances().get(0).getResultText()).contains("삼진");
        assertThat(dto.getPlateAppearances().get(1).getBatterName()).isEqualTo("허경민");
        assertThat(dto.getPlateAppearances().get(1).getResultText()).contains("땅볼");
        assertThat(dto.getPlateAppearances().get(2).getBatterName()).isEqualTo("황성빈");
        assertThat(dto.getPlateAppearances().get(2).isTop()).isFalse();
        assertThat(dto.getPlateAppearances().get(3).getBatterName()).isEqualTo("윤동희");
        assertThat(dto.getPlateAppearances().get(3).getResultText()).contains("플라이");
    }

    @Test
    @DisplayName("이닝별 득점·총점·안타·실책·사사구(B)가 집계되어 스코어보드와 일치한다")
    void parse_boxScoreAndInningScores_success() {
        String text = """
                1회초 LG 공격
                신민재 선수 페이지
                신민재2번타자타율 0.389
                신민재 : 좌익수 왼쪽 2루타
                3구타격
                볼카운트1 - 1
                김현수 선수 페이지
                김현수3번타자타율 0.500
                2루주자 신민재 : 홈인
                김현수 : 좌익수 왼쪽 1루타
                기록 펼치기
                3구타격
                볼카운트1 - 1
                오지환 선수 페이지
                오지환5번타자타율 0.310
                오지환 : 볼넷
                4구타격
                볼카운트4 - 0
                1회말 한화 공격
                손아섭 선수 페이지
                손아섭1번타자타율 0.297
                손아섭 : 유격수 실책으로 출루
                1구타격
                볼카운트0 - 0
                노시환 선수 페이지
                노시환3번타자타율 0.290
                노시환 : 몸에 맞는 볼
                1구타격
                볼카운트1 - 0
                """;

        GameRecordImportDto dto = parser.parse(text);

        assertThat(dto.getGameInfo().getAwayScore()).isEqualTo(1);
        assertThat(dto.getGameInfo().getHomeScore()).isEqualTo(0);
        assertThat(dto.getGameInfo().getAwayHits()).isEqualTo(2);
        assertThat(dto.getGameInfo().getHomeHits()).isEqualTo(0);
        // '실책으로 출루'도 실책으로 집계
        assertThat(dto.getGameInfo().getAwayErrors()).isEqualTo(1);
        assertThat(dto.getGameInfo().getHomeErrors()).isEqualTo(0);
        assertThat(dto.getGameInfo().getAwayWalks()).isEqualTo(1);
        assertThat(dto.getGameInfo().getHomeWalks()).isEqualTo(1);
        assertThat(dto.getGameInfo().getStatus()).isEqualTo(com.baseball.domain.game.Game.GameStatus.IN_PROGRESS);

        assertThat(dto.getInningScores()).hasSize(9);
        assertThat(dto.getInningScores().get(0).getAwayRunsInInning()).isEqualTo(1);
        assertThat(dto.getInningScores().get(0).getHomeRunsInInning()).isEqualTo(0);
    }

    @Test
    @DisplayName("승리 투수, 패전 투수, 세이브 투수를 파싱한다")
    void parse_winningLosingSavePitcher_success() {
        String text = """
                1회초 한화 공격
                김철수 선수 페이지
                김철수1번타자타율 0.250
                김철수 : 2루수 땅볼 아웃
                1구타격
                승리 투수: 이승우
                패전 투수: 박찬희
                세이브 투수: 정우영
                """;

        GameRecordImportDto dto = parser.parse(text);

        assertThat(dto.getGameInfo()).isNotNull();
        assertThat(dto.getGameInfo().getWinningPitcherName()).isEqualTo("이승우");
        assertThat(dto.getGameInfo().getLosingPitcherName()).isEqualTo("박찬희");
        assertThat(dto.getGameInfo().getSavePitcherName()).isEqualTo("정우영");
    }

    @Test
    @DisplayName("한 줄 라벨(승/패/세) + 다음 줄 이름 형식 파싱")
    void parse_shortWlLabels_nextLineNames() {
        String text = """
                1회초 한화 공격
                김철수 선수 페이지
                김철수1번타자타율 0.250
                김철수 : 2루수 땅볼 아웃
                승
                홍민기
                패
                김민수
                세
                홍민기
                """;

        GameRecordImportDto dto = parser.parse(text);

        assertThat(dto.getGameInfo().getWinningPitcherName()).isEqualTo("홍민기");
        assertThat(dto.getGameInfo().getLosingPitcherName()).isEqualTo("김민수");
        assertThat(dto.getGameInfo().getSavePitcherName()).isEqualTo("홍민기");
    }

    @Test
    @DisplayName("승승리투수/패패전투수 + 다음 줄 이름, 경기 종료 문구는 COMPLETED")
    void parse_gameEnded_typoPitcherLabels_nextLineNames() {
        String text = """
                경기가 종료되었습니다.
                승승리투수 -
                로드리게스
                패패전투수 -
                엄상백
                9회초 한화 공격
                이진영 선수 페이지
                이진영5번타자타율 0.143
                이진영 : 포수 스트라이크 낫 아웃 (포수 태그아웃)
                """;

        GameRecordImportDto dto = parser.parse(text);

        assertThat(dto.getGameInfo().getStatus()).isEqualTo(com.baseball.domain.game.Game.GameStatus.COMPLETED);
        assertThat(dto.getGameInfo().getWinningPitcherName()).isEqualTo("로드리게스");
        assertThat(dto.getGameInfo().getLosingPitcherName()).isEqualTo("엄상백");
        assertThat(dto.getHalfInningsWithHeader()).contains("9_true");
        assertThat(dto.getPlateAppearances().get(0).getInning()).isEqualTo(9);
    }

    @Test
    @DisplayName("전각 이닝 숫자(９)·'9 회초' 공백 변형도 9회초로 인식")
    void parse_inningHeader_nfkAndSpaces() {
        String fullwidthNine = "\uFF19"; // ９
        String text = fullwidthNine + " 회초 한화 공격\n"
                + "이진영 선수 페이지\n"
                + "이진영5번타자타율 0.143\n"
                + "이진영 : 아웃\n";

        GameRecordImportDto dto = parser.parse(text);

        assertThat(dto.getHalfInningsWithHeader()).contains("9_true");
        assertThat(dto.getPlateAppearances()).hasSize(1);
        assertThat(dto.getPlateAppearances().get(0).getInning()).isEqualTo(9);
    }

    @Test
    @DisplayName("9회초 블록 전체(종료·투수·5타자·교체) 파싱 시 이닝·타석 수 유지")
    void parse_ninthInning_block_fiveBatters() {
        String text = """
                경기가 종료되었습니다.
                승승리투수 -
                로드리게스
                패패전투수 -
                엄상백
                9회초 한화 공격
                이진영 선수 페이지
                이진영5번타자타율 0.143
                타석2타수2안타0득점1타점0홈런0볼넷0피삼진2
                투구 위치보기
                이진영 : 포수 스트라이크 낫 아웃 (포수 태그아웃)
                기록 펼치기
                한화 승리 확률 0.0% (0.0%p)
                4구헛스윙
                128km/h슬라이더
                최유빈 선수 페이지
                최유빈4번타자타율 0.300
                투구 위치보기
                2루주자 김태연 : 홈인
                최유빈 : 1루수 왼쪽 내야안타
                기록 펼치기
                최인호 선수 페이지
                최인호3번타자타율 0.333
                최인호 : 좌익수 플라이 아웃
                김태연 선수 페이지
                김태연2번타자타율 0.400
                1루주자 김태연 : 좌익수 실책으로 2루까지 진루 (좌익수 포구 실책)
                김태연 : 좌익수 왼쪽 1루타
                오재원 선수 페이지
                오재원1번타자타율 0.258
                오재원 : 투수 땅볼 아웃 (투수->1루수 송구아웃)
                대타 김민성 : 지명타자(으)로 수비위치 변경
                교체
                투수: 윤성빈
                OUT
                투수: 정철원
                IN
                """;

        GameRecordImportDto dto = parser.parse(text);

        assertThat(dto.getHalfInningsWithHeader()).contains("9_true");
        assertThat(dto.getPlateAppearances()).hasSize(5);
        assertThat(dto.getPlateAppearances()).allMatch(pa -> pa.getInning() == 9 && pa.isTop());
        assertThat(dto.getPitcherSubstitutions()).isNotEmpty();
    }

    @Test
    @DisplayName("대타/대주자 교체 문구를 타자 교체 유형으로 구분한다")
    void parse_batterSubstitutionType_distinguishesPinchHitterAndRunner() {
        String text = """
                1회초 한화 공격
                대타 김민성 : 지명타자(으)로 수비위치 변경
                김민성 선수 페이지
                김민성4번타자타율 0.300
                김민성 : 좌익수 앞 1루타
                2회초 한화 공격
                대주자 정훈 : 1루주자(으)로 교체
                정훈 선수 페이지
                정훈4번타자타율 0.250
                정훈 : 포수 파울플라이 아웃
                """;

        GameRecordImportDto dto = parser.parse(text);
        assertThat(dto.getPlateAppearances()).hasSize(2);

        GameRecordImportDto.PlateAppearanceRow first = dto.getPlateAppearances().get(0);
        GameRecordImportDto.PlateAppearanceRow second = dto.getPlateAppearances().get(1);
        assertThat(first.getBatterName()).isEqualTo("김민성");
        assertThat(first.getBatterSubstitutionType()).isEqualTo("PINCH_HITTER");
        assertThat(second.getBatterName()).isEqualTo("정훈");
        assertThat(second.getBatterSubstitutionType()).isEqualTo("PINCH_RUNNER");
    }

    @Test
    @DisplayName("N번타자 : 대타 OOO (으)로 교체 형태도 대타로 인식한다")
    void parse_pinchHitterChangeLine_detected() {
        String text = """
                8회말 롯데 공격
                전준우 선수 페이지
                전준우4번타자타율 0.357
                4번타자 전준우 : 대타 김민성 (으)로 교체
                김민성 선수 페이지
                김민성4번타자타율 0.214
                김민성 : 유격수 땅볼 아웃
                """;

        GameRecordImportDto dto = parser.parse(text);
        assertThat(dto.getPlateAppearances()).hasSize(2);
        GameRecordImportDto.PlateAppearanceRow second = dto.getPlateAppearances().get(1);
        assertThat(second.getBatterName()).isEqualTo("김민성");
        assertThat(second.getBatterSubstitutionType()).isEqualTo("PINCH_HITTER");
    }

    @Test
    @DisplayName("교체 블록의 '대주자: OOO IN' 형태도 대주자로 인식한다")
    void parse_pinchRunnerColonInLine_detected() {
        String text = """
                6회말 롯데 공격
                전준우 선수 페이지
                전준우4번타자타율 0.357
                전준우 : 2루수 왼쪽 내야안타
                교체
                1루주자: 윤동희
                OUT
                대주자: 김한홀
                IN
                김한홀 선수 페이지
                김한홀4번타자타율 0.214
                김한홀 : 볼넷
                """;

        GameRecordImportDto dto = parser.parse(text);
        assertThat(dto.getPlateAppearances()).hasSize(2);
        GameRecordImportDto.PlateAppearanceRow second = dto.getPlateAppearances().get(1);
        assertThat(second.getBatterName()).isEqualTo("김한홀");
        assertThat(second.getBatterSubstitutionType()).isEqualTo("PINCH_RUNNER");
    }

    @Test
    @DisplayName("한 타석 뒤 연속 교체: 유격수·3루수·투수 등 포지션별 OUT/IN이 모두 파싱된다")
    void parse_multipleFieldAndPitcherSubs_inOneAtBat() {
        String text = """
                8회말 KT 공격
                이강민 선수 페이지
                이강민9번타자타율 0.000
                이강민 : 3루수 라인드라이브 아웃
                교체
                유격수: 전민재
                OUT
                유격수: 이서준
                IN
                교체
                3루수: 손호영
                OUT
                3루수: 박찬형
                IN
                교체
                투수: 정철원
                OUT
                투수: 박정민
                IN
                """;

        GameRecordImportDto dto = parser.parse(text);
        assertThat(dto.getPlateAppearances()).hasSize(1);
        assertThat(dto.getPitcherSubstitutions()).hasSize(3);
        assertThat(dto.getPitcherSubstitutions().get(0).getKind()).isEqualTo(SubstitutionKind.FIELD);
        assertThat(dto.getPitcherSubstitutions().get(0).getPositionLabel()).isEqualTo("유격수");
        assertThat(dto.getPitcherSubstitutions().get(0).getPitcherOutName()).isEqualTo("전민재");
        assertThat(dto.getPitcherSubstitutions().get(0).getPitcherInName()).isEqualTo("이서준");
        assertThat(dto.getPitcherSubstitutions().get(1).getKind()).isEqualTo(SubstitutionKind.FIELD);
        assertThat(dto.getPitcherSubstitutions().get(1).getPositionLabel()).isEqualTo("3루수");
        assertThat(dto.getPitcherSubstitutions().get(2).getKind()).isEqualTo(SubstitutionKind.PITCHER);
        assertThat(dto.getPitcherSubstitutions().get(2).getPitcherOutName()).isEqualTo("정철원");
        assertThat(dto.getPitcherSubstitutions().get(2).getPitcherInName()).isEqualTo("박정민");
    }
}

