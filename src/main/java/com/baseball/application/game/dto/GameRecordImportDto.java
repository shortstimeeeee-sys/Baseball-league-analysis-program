package com.baseball.application.game.dto;

import com.baseball.domain.game.Game.GameStatus;
import com.baseball.domain.game.SubstitutionKind;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 경기 기록 파싱/임포트용 DTO.
 * JSON 또는 텍스트 파싱 결과를 담아 DB에 반영할 때 사용합니다.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GameRecordImportDto {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GameInfo {
        /** 리그 이름. 있으면 해당 리그에서 팀 조회/생성, 없으면 팀 이름만으로 조회 */
        private String leagueName;
        /** 홈팀 ID (선택). 없으면 homeTeamName 사용 */
        private Long homeTeamId;
        /** 원정팀 ID (선택). 없으면 awayTeamName 사용 */
        private Long awayTeamId;
        /** 홈팀 이름. 팀 ID 없을 때 이 이름으로 팀 찾거나 생성 */
        private String homeTeamName;
        /** 원정팀 이름. 팀 ID 없을 때 이 이름으로 팀 찾거나 생성 */
        private String awayTeamName;
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        private LocalDateTime gameDateTime;
        private String venue;
        private Integer homeScore;
        private Integer awayScore;
        private Integer homeHits;
        private Integer awayHits;
        private Integer homeErrors;
        private Integer awayErrors;
        private Integer homeWalks;
        private Integer awayWalks;
        private GameStatus status;
        /** 승리 투수 이름 (파싱 시 "승리 투수: 이름" 등에서 추출) */
        private String winningPitcherName;
        /** 패전 투수 이름 */
        private String losingPitcherName;
        /** 세이브 투수 이름 */
        private String savePitcherName;
        /**
         * true면 동일 일자·동일 매치업이라도 새 경기(더블헤더 2차전 등)로 생성.
         * false/null이면 같은 날 같은 리그의 홈/원정 조합이 이미 있으면 그 경기에 병합(업데이트).
         */
        private Boolean doubleheader;
        /** true면 시범 경기로 분류(리그 공식 경기와 분리). */
        private Boolean exhibition;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InningScoreRow {
        private int inning;
        private int homeRunsInInning;
        private int awayRunsInInning;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PitchRow {
        private int pitchOrder;
        private Integer ballCountAfter;
        private Integer strikeCountAfter;
        private String pitchType;
        private Integer speedKmh;
        private String resultText;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PlateAppearanceRow {
        private int inning;
        private boolean isTop;  // true=초(원정 공격), false=말(홈 공격)
        private int sequenceOrder;
        private String batterName;
        private String pitcherName;
        private Integer batterOrder;
        /** 선택값. null이면 서버에서 경기 흐름으로 자동 산정 */
        private Boolean batterIsStarter;
        /** 선택값. 대타/대주자 구분 (PINCH_HITTER, PINCH_RUNNER) */
        private String batterSubstitutionType;
        /** 선택값. null이면 서버에서 경기 흐름으로 자동 산정 */
        private Boolean pitcherIsStarter;
        private String resultText;
        /** 주자 진루/아웃 등 (예: "2루주자 김현수 : 3루까지 진루") */
        @Builder.Default
        private List<String> runnerPlays = new ArrayList<>();
        @Builder.Default
        private List<PitchRow> pitches = new ArrayList<>();
    }

    /** 투수·야수 교체 (예: 문동주 N타자 상대 후 정우주 IN, 유격수 전민재 OUT → 이서준 IN) */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PitcherSubstitutionRow {
        private int inning;
        private boolean isTop;
        /** null이면 투수 교체로 저장 */
        private SubstitutionKind kind;
        /** FIELD일 때 포지션명 */
        private String positionLabel;
        @Builder.Default
        private int displayOrder = 0;
        private String pitcherOutName;
        private String pitcherInName;
        /** 퇴장 투수가 해당 반 이닝에서 상대한 타자 수 (FIELD는 0) */
        private int battersFaced;
        /** 이 타석(sequenceOrder) 다음에 표시. null 또는 0이면 해당 반 이닝 첫 타석 전에 표시 */
        private Integer afterPaSequenceOrder;
    }

    private GameInfo gameInfo;
    @Builder.Default
    private List<InningScoreRow> inningScores = new ArrayList<>();
    @Builder.Default
    private List<PlateAppearanceRow> plateAppearances = new ArrayList<>();
    @Builder.Default
    private List<PitcherSubstitutionRow> pitcherSubstitutions = new ArrayList<>();
    /** 병합 시 이 집합에 있는 (이닝_초말)만 교체. 파서가 「N회초/말 팀명 공격」 줄을 만날 때만 추가 */
    @Builder.Default
    private Set<String> halfInningsWithHeader = new HashSet<>();
}
