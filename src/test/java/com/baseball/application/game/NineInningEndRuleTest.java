package com.baseball.application.game;

import com.baseball.application.game.dto.GameRecordImportDto;
import com.baseball.domain.game.Game.GameStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class NineInningEndRuleTest {

    @Test
    @DisplayName("9회초 후 홈이 앞서면 9회말 타석·헤더 제거")
    void stripsNineBottomWhenHomeLeadsAfterNineTop() {
        List<GameRecordImportDto.PlateAppearanceRow> pas = new ArrayList<>();
        pas.add(pa(1, true, 1, "홈인", List.of())); // away 1
        pas.add(pa(1, false, 2, "홈인", List.of()));
        pas.add(pa(2, false, 3, "홈인", List.of()));
        pas.add(pa(3, false, 4, "홈인", List.of())); // home 3 (1~8 말)
        pas.add(pa(9, true, 5, "", List.of())); // away still 1
        pas.add(pa(9, false, 6, "홈인", List.of())); // 불필요한 9회말

        List<GameRecordImportDto.PitcherSubstitutionRow> subs = new ArrayList<>();
        Set<String> headers = new HashSet<>(Set.of("9_true", "9_false"));

        boolean stripped = NineInningEndRule.stripNineBottomIfHomeLeadsAfterNineTop(pas, subs, headers);

        assertThat(stripped).isTrue();
        assertThat(pas).hasSize(5);
        assertThat(pas.stream().noneMatch(p -> p.getInning() == 9 && !p.isTop())).isTrue();
        assertThat(headers).doesNotContain("9_false");
    }

    @Test
    @DisplayName("동점이면 9회말 유지")
    void keepsNineBottomWhenTied() {
        List<GameRecordImportDto.PlateAppearanceRow> pas = new ArrayList<>();
        pas.add(pa(9, true, 1, "홈인", List.of()));
        pas.add(pa(9, false, 2, "", List.of()));

        boolean stripped = NineInningEndRule.stripNineBottomIfHomeLeadsAfterNineTop(pas, new ArrayList<>(), Set.of("9_true", "9_false"));

        assertThat(stripped).isFalse();
        assertThat(pas).hasSize(2);
    }

    @Test
    @DisplayName("원정이 앞서면 9회말 유지")
    void keepsNineBottomWhenAwayLeads() {
        List<GameRecordImportDto.PlateAppearanceRow> pas = new ArrayList<>();
        pas.add(pa(1, false, 1, "홈인", List.of()));
        pas.add(pa(9, true, 2, "홈인", List.of())); // away +1 in 9 top
        pas.add(pa(9, false, 3, "", List.of()));

        boolean stripped = NineInningEndRule.stripNineBottomIfHomeLeadsAfterNineTop(pas, new ArrayList<>(), Set.of("9_true", "9_false"));

        assertThat(stripped).isFalse();
        assertThat(pas).hasSize(3);
    }

    @Test
    @DisplayName("applyToDto: 제거 시 경기 완료 상태 설정")
    void applyToDto_setsCompleted() {
        GameRecordImportDto dto = new GameRecordImportDto();
        dto.setGameInfo(GameRecordImportDto.GameInfo.builder().status(GameStatus.IN_PROGRESS).build());
        List<GameRecordImportDto.PlateAppearanceRow> pas = new ArrayList<>();
        pas.add(pa(1, false, 1, "홈인", List.of()));
        pas.add(pa(9, true, 2, "", List.of()));
        pas.add(pa(9, false, 3, "홈인", List.of()));
        dto.setPlateAppearances(pas);
        dto.setHalfInningsWithHeader(new HashSet<>(Set.of("9_true", "9_false")));

        NineInningEndRule.applyToDto(dto);

        assertThat(dto.getGameInfo().getStatus()).isEqualTo(GameStatus.COMPLETED);
        assertThat(dto.getPlateAppearances()).hasSize(2);
    }

    private static GameRecordImportDto.PlateAppearanceRow pa(int inn, boolean top, int seq, String result, List<String> runners) {
        return GameRecordImportDto.PlateAppearanceRow.builder()
                .inning(inn)
                .isTop(top)
                .sequenceOrder(seq)
                .batterName("x")
                .pitcherName("y")
                .resultText(result)
                .runnerPlays(runners != null ? new ArrayList<>(runners) : new ArrayList<>())
                .build();
    }
}
