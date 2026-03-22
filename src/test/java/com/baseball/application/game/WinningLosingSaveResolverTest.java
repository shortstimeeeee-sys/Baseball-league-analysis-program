package com.baseball.application.game;

import com.baseball.domain.game.Game;
import com.baseball.domain.game.PitcherNameNormalizer;
import com.baseball.domain.game.PlateAppearance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WinningLosingSaveResolverTest {

    @Test
    @DisplayName("qualifiesForSave: 승리 투수와 같으면 세이브 불가")
    void qualifiesForSave_falseWhenSameAsWinningPitcher() {
        assertThat(WinningLosingSaveResolver.qualifiesForSave("홍민기", "홍민기", true, List.of())).isFalse();
    }

    @Test
    @DisplayName("qualifiesForSave: 5점 차로 등판·3타석만 — 세이브 아님(리드 3점 초과)")
    void qualifiesForSave_falseWhenLeadTooBig() {
        List<PlateAppearance> ordered = homeWin9thCloseGame(5, 3);
        assertThat(WinningLosingSaveResolver.qualifiesForSave("마무리", "선발", true, ordered)).isFalse();
    }

    @Test
    @DisplayName("qualifiesForSave: 3점 차·3타석 이상 — 세이브")
    void qualifiesForSave_trueWhenSaveSituationThreeRuns() {
        List<PlateAppearance> ordered = homeWin9thCloseGame(3, 3);
        assertThat(WinningLosingSaveResolver.qualifiesForSave("마무리", "선발", true, ordered)).isTrue();
    }

    @Test
    @DisplayName("qualifiesForSave: 3점 차지만 2타석만 — 세이브 아님(1이닝 미만 근사)")
    void qualifiesForSave_falseWhenNotEnoughInnings() {
        List<PlateAppearance> ordered = homeWin9thCloseGame(3, 2);
        assertThat(WinningLosingSaveResolver.qualifiesForSave("마무리", "선발", true, ordered)).isFalse();
    }

    @Test
    @DisplayName("qualifiesForSave: 10점 차라도 9타석 이상 구원이면 세이브(3이닝 근사)")
    void qualifiesForSave_trueLongRelief() {
        List<PlateAppearance> ordered = longReliefGame();
        assertThat(WinningLosingSaveResolver.qualifiesForSave("구원", "선발", true, ordered)).isTrue();
    }

    /** 홈 승리, 9회초에 마무리가 등판한다고 가정. leadBefore9 = 승리팀(홈) 리드로 9회초 첫 타석 전 점수 차, closerPas = 9회초 마무리 타석 수 */
    private static List<PlateAppearance> homeWin9thCloseGame(int leadBefore9, int closerPas) {
        List<PlateAppearance> list = new ArrayList<>();
        int seq = 1;
        // 1~8회: 홈이 leadBefore9점 앞서도록 원정에 0, 홈에 득점 배치 (단순화: 8회말까지 홈 leadBefore9)
        list.add(pa(1, true, seq++, "p", ""));
        list.add(pa(8, false, seq++, "p", runsToText(leadBefore9)));
        for (int i = 0; i < closerPas; i++) {
            list.add(pa(9, true, seq++, "마무리", ""));
        }
        return list;
    }

    private static List<PlateAppearance> longReliefGame() {
        List<PlateAppearance> list = new ArrayList<>();
        int seq = 1;
        list.add(pa(1, true, seq++, "선발", ""));
        list.add(pa(1, false, seq++, "p", runsToText(10)));
        for (int i = 0; i < 9; i++) {
            list.add(pa(9, true, seq++, "구원", ""));
        }
        return list;
    }

    private static String runsToText(int runs) {
        if (runs <= 0) {
            return "";
        }
        return "홈인".repeat(runs);
    }

    private static PlateAppearance pa(int inn, boolean top, int seq, String pitcher, String result) {
        return PlateAppearance.builder()
                .inning(inn)
                .isTop(top)
                .sequenceOrder(seq)
                .pitcherName(pitcher)
                .batterName("b")
                .resultText(result)
                .build();
    }

    @Test
    @DisplayName("승리·세이브 투수 이름이 같으면 세이브를 비운다 (기록 규칙)")
    void game_clearsSaveWhenSameAsWin() {
        Game g = Game.builder().build();
        ReflectionTestUtils.setField(g, "winningPitcherName", "홍민기");
        ReflectionTestUtils.setField(g, "savePitcherName", "홍민기");
        g.clearSavePitcherWhenSameAsWinningPitcher();
        assertThat(g.getSavePitcherName()).isNull();
        assertThat(g.getWinningPitcherName()).isEqualTo("홍민기");
    }

    @Test
    @DisplayName("공백만 다른 동일 인물도 세이브 제거 대상")
    void game_clearsSaveWhenNamesEqualIgnoringSpaces() {
        Game g = Game.builder().build();
        ReflectionTestUtils.setField(g, "winningPitcherName", "홍 민 기");
        ReflectionTestUtils.setField(g, "savePitcherName", "홍민기");
        g.clearSavePitcherWhenSameAsWinningPitcher();
        assertThat(g.getSavePitcherName()).isNull();
    }

    @Test
    @DisplayName("승리와 세이브가 다르면 유지")
    void game_keepsSaveWhenDifferent() {
        Game g = Game.builder().build();
        ReflectionTestUtils.setField(g, "winningPitcherName", "홍민기");
        ReflectionTestUtils.setField(g, "savePitcherName", "이강민");
        g.clearSavePitcherWhenSameAsWinningPitcher();
        assertThat(g.getSavePitcherName()).isEqualTo("이강민");
    }

    @Test
    @DisplayName("PitcherNameNormalizer: 추정 결과 승·세 동일 시 세이브 null 처리")
    void samePitcherName_usedForSaveClear() {
        assertThat(PitcherNameNormalizer.samePitcher("홍민기", "홍민기")).isTrue();
        assertThat(PitcherNameNormalizer.samePitcher("홍 민 기", "홍민기")).isTrue();
        assertThat(PitcherNameNormalizer.samePitcher("홍민기", "김민수")).isFalse();
        assertThat(PitcherNameNormalizer.samePitcher(null, "홍민기")).isFalse();
    }
}
