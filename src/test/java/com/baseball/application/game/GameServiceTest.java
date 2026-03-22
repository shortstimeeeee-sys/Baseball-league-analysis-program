package com.baseball.application.game;

import com.baseball.domain.game.Game;
import com.baseball.domain.game.Game.GameStatus;
import com.baseball.domain.game.GameRepository;
import com.baseball.domain.game.InningScoreRepository;
import com.baseball.domain.game.PitchRepository;
import com.baseball.domain.game.PitcherSubstitutionRepository;
import com.baseball.domain.game.PlateAppearance;
import com.baseball.domain.game.PlateAppearanceRepository;
import com.baseball.domain.team.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

class GameServiceTest {

    @Test
    @DisplayName("없는 경기 id 조회 시 NotFoundException을 던진다")
    void getById_notFound() {
        GameRepository gameRepository = Mockito.mock(GameRepository.class);
        TeamRepository teamRepository = Mockito.mock(TeamRepository.class);
        InningScoreRepository inningScoreRepository = Mockito.mock(InningScoreRepository.class);
        PlateAppearanceRepository plateAppearanceRepository = Mockito.mock(PlateAppearanceRepository.class);
        PitcherSubstitutionRepository pitcherSubstitutionRepository = Mockito.mock(PitcherSubstitutionRepository.class);
        PitchRepository pitchRepository = Mockito.mock(PitchRepository.class);

        given(gameRepository.findById(999L)).willReturn(Optional.empty());

        GameService gameService = new GameService(
                gameRepository, teamRepository, inningScoreRepository, plateAppearanceRepository, pitcherSubstitutionRepository, pitchRepository
        );

        assertThatThrownBy(() -> gameService.getById(999L))
                .isInstanceOf(com.baseball.common.NotFoundException.class);
    }

    @Test
    @DisplayName("스코어보드 중앙: 완료 처리 시 종료")
    void computeScoreboardSituationLabel_completed() {
        GameService gameService = emptyGameService();
        Game game = Mockito.mock(Game.class);
        given(game.getStatus()).willReturn(GameStatus.COMPLETED);
        assertThat(gameService.computeScoreboardSituationLabel(game, List.of())).isEqualTo("종료");
    }

    @Test
    @DisplayName("스코어보드 중앙: 마지막 타석 기준 N회초/말")
    void computeScoreboardSituationLabel_inningPhase() {
        GameService gameService = emptyGameService();
        Game game = Mockito.mock(Game.class);
        given(game.getStatus()).willReturn(GameStatus.IN_PROGRESS);
        given(game.getHomeScore()).willReturn(3);
        given(game.getAwayScore()).willReturn(2);

        PlateAppearance pa = Mockito.mock(PlateAppearance.class);
        given(pa.getInning()).willReturn(7);
        given(pa.getIsTop()).willReturn(true);

        assertThat(gameService.computeScoreboardSituationLabel(game, List.of(pa))).isEqualTo("7회초");
    }

    @Test
    @DisplayName("스코어보드 중앙: 9회초 끝난 뒤 홈이 원정보다 점수가 높으면 9회말 없이 종료")
    void computeScoreboardSituationLabel_ninthTop_homeLeads() {
        GameService gameService = emptyGameService();
        Game game = Mockito.mock(Game.class);
        given(game.getStatus()).willReturn(GameStatus.IN_PROGRESS);
        given(game.getHomeScore()).willReturn(5);
        given(game.getAwayScore()).willReturn(2);

        PlateAppearance pa = Mockito.mock(PlateAppearance.class);
        given(pa.getInning()).willReturn(9);
        given(pa.getIsTop()).willReturn(true);

        assertThat(gameService.computeScoreboardSituationLabel(game, List.of(pa))).isEqualTo("종료");
    }

    @Test
    @DisplayName("스코어보드 중앙: 9회초 끝난 뒤 원정이 앞서면 9회말 진행(표시는 9회초)")
    void computeScoreboardSituationLabel_ninthTop_awayLeads() {
        GameService gameService = emptyGameService();
        Game game = Mockito.mock(Game.class);
        given(game.getStatus()).willReturn(GameStatus.IN_PROGRESS);
        given(game.getHomeScore()).willReturn(2);
        given(game.getAwayScore()).willReturn(5);

        PlateAppearance pa = Mockito.mock(PlateAppearance.class);
        given(pa.getInning()).willReturn(9);
        given(pa.getIsTop()).willReturn(true);

        assertThat(gameService.computeScoreboardSituationLabel(game, List.of(pa))).isEqualTo("9회초");
    }

    @Test
    @DisplayName("회초·말 공/교체 메모 한 줄 파싱")
    void parseHalfInningBreakNotes_line() {
        String raw = "1초|공: 새 공|교체: 포수 A→B\n2말|교체: 대타 투입";
        Map<String, GameService.HalfInningBreakNote> m = GameService.parseHalfInningBreakNotes(raw);
        assertThat(m).hasSize(2);
        GameService.HalfInningBreakNote top1 = m.get("1_true");
        assertThat(top1.gongText()).isEqualTo("새 공");
        assertThat(top1.substitutionText()).isEqualTo("포수 A→B");
        GameService.HalfInningBreakNote bot2 = m.get("2_false");
        assertThat(bot2.substitutionText()).isEqualTo("대타 투입");
        assertThat(bot2.gongText()).isEmpty();
    }

    @Test
    @DisplayName("자동 교대 멘트: 초 끝 다음 타석이 같은 회 말이면 말 시작까지 표시")
    void buildHalfHandoffDisplay_topToBottom() {
        PlateAppearance next = Mockito.mock(PlateAppearance.class);
        given(next.getInning()).willReturn(1);
        given(next.getIsTop()).willReturn(false);
        GameService.HalfInningHandoff h = GameService.buildHalfHandoffDisplay(1, true, next);
        assertThat(h.line()).isEqualTo("1회초 종료 → 1회말 시작");
        assertThat(h.filterInning()).isEqualTo(1);
        assertThat(h.endedTop()).isTrue();
        assertThat(h.confirmHalfKey()).isEqualTo("1_true");
    }

    @Test
    @DisplayName("자동 교대 멘트: 초 끝인데 다음 타석이 없으면 종료만")
    void buildHalfHandoffDisplay_topNoNext() {
        GameService.HalfInningHandoff h = GameService.buildHalfHandoffDisplay(9, true, null);
        assertThat(h.line()).isEqualTo("9회초 종료");
        assertThat(h.confirmHalfKey()).isEqualTo("9_true");
    }

    @Test
    @DisplayName("자동 교대 멘트: 말 끝 다음이 다음 회 초면 회초 시작 표시")
    void buildHalfHandoffDisplay_bottomToNextInningTop() {
        PlateAppearance next = Mockito.mock(PlateAppearance.class);
        given(next.getInning()).willReturn(2);
        given(next.getIsTop()).willReturn(true);
        GameService.HalfInningHandoff h = GameService.buildHalfHandoffDisplay(1, false, next);
        assertThat(h.line()).isEqualTo("1회말 종료 → 2회초 시작");
        assertThat(h.endedTop()).isFalse();
        assertThat(h.confirmHalfKey()).isEqualTo("1_false");
    }

    @Test
    @DisplayName("자동 교대 멘트: 말 끝 다음 타석 없으면 말 종료만")
    void buildHalfHandoffDisplay_bottomNoNext() {
        GameService.HalfInningHandoff h = GameService.buildHalfHandoffDisplay(9, false, null);
        assertThat(h.line()).isEqualTo("9회말 종료");
        assertThat(h.confirmHalfKey()).isEqualTo("9_false");
    }

    @Test
    @DisplayName("경기 시작 배너: 전용 키·접미 분리 없음")
    void gameStartBanner_marker() {
        GameService.HalfInningHandoff g = GameService.HalfInningHandoff.gameStart();
        assertThat(g.gameStartBanner()).isTrue();
        assertThat(g.confirmHalfKey()).isEqualTo("__game_start__");
        assertThat(g.gameEndSuffixLabel()).isNull();
    }

    @Test
    @DisplayName("스코어보드가 종료일 때 마지막 반 멘트에 경기 종료 접미사 (9회말·완료)")
    void appendGameEndSuffixForRecordLine_ninthBottomCompleted() {
        GameService svc = emptyGameService();
        GameService.HalfInningHandoff base = GameService.buildHalfHandoffDisplay(9, false, null);
        Game g = Mockito.mock(Game.class);
        given(g.getStatus()).willReturn(GameStatus.COMPLETED);
        PlateAppearance pa = Mockito.mock(PlateAppearance.class);
        given(pa.getInning()).willReturn(9);
        given(pa.getIsTop()).willReturn(false);
        GameService.HalfInningHandoff out = svc.appendGameEndSuffixForRecordLine(base, g, List.of(pa), true);
        assertThat(out.line()).isEqualTo("9회말 종료 / 경기 종료");
    }

    @Test
    @DisplayName("스코어보드가 종료일 때 연장 마지막 반에도 경기 종료 접미사")
    void appendGameEndSuffixForRecordLine_extraInningsCompleted() {
        GameService svc = emptyGameService();
        GameService.HalfInningHandoff base = GameService.buildHalfHandoffDisplay(10, false, null);
        Game g = Mockito.mock(Game.class);
        given(g.getStatus()).willReturn(GameStatus.COMPLETED);
        PlateAppearance pa = Mockito.mock(PlateAppearance.class);
        given(pa.getInning()).willReturn(10);
        given(pa.getIsTop()).willReturn(false);
        GameService.HalfInningHandoff out = svc.appendGameEndSuffixForRecordLine(base, g, List.of(pa), true);
        assertThat(out.line()).isEqualTo("10회말 종료 / 경기 종료");
    }

    @Test
    @DisplayName("DB 미완료여도 9회초 끝 홈 리드면 스코어보드 종료와 동일하게 접미사")
    void appendGameEndSuffixForRecordLine_nineTopHomeLeadsNotCompleted() {
        GameService svc = emptyGameService();
        GameService.HalfInningHandoff base = GameService.buildHalfHandoffDisplay(9, true, null);
        Game g = Mockito.mock(Game.class);
        given(g.getStatus()).willReturn(GameStatus.IN_PROGRESS);
        given(g.getHomeScore()).willReturn(4);
        given(g.getAwayScore()).willReturn(3);
        PlateAppearance pa = Mockito.mock(PlateAppearance.class);
        given(pa.getInning()).willReturn(9);
        given(pa.getIsTop()).willReturn(true);
        GameService.HalfInningHandoff out = svc.appendGameEndSuffixForRecordLine(base, g, List.of(pa), true);
        assertThat(out.line()).isEqualTo("9회초 종료 / 경기 종료");
    }

    @Test
    @DisplayName("진행 중이면 마지막 멘트에 경기 종료 미부여")
    void appendGameEndSuffixForRecordLine_inProgressNoSuffix() {
        GameService svc = emptyGameService();
        GameService.HalfInningHandoff base = GameService.buildHalfHandoffDisplay(9, false, null);
        Game g = Mockito.mock(Game.class);
        given(g.getStatus()).willReturn(GameStatus.IN_PROGRESS);
        given(g.getHomeScore()).willReturn(3);
        given(g.getAwayScore()).willReturn(3);
        PlateAppearance pa = Mockito.mock(PlateAppearance.class);
        given(pa.getInning()).willReturn(9);
        given(pa.getIsTop()).willReturn(false);
        GameService.HalfInningHandoff out = svc.appendGameEndSuffixForRecordLine(base, g, List.of(pa), true);
        assertThat(out.line()).isEqualTo("9회말 종료");
    }

    private static GameService emptyGameService() {
        GameRepository gameRepository = Mockito.mock(GameRepository.class);
        TeamRepository teamRepository = Mockito.mock(TeamRepository.class);
        InningScoreRepository inningScoreRepository = Mockito.mock(InningScoreRepository.class);
        PlateAppearanceRepository plateAppearanceRepository = Mockito.mock(PlateAppearanceRepository.class);
        PitcherSubstitutionRepository pitcherSubstitutionRepository = Mockito.mock(PitcherSubstitutionRepository.class);
        PitchRepository pitchRepository = Mockito.mock(PitchRepository.class);
        return new GameService(
                gameRepository, teamRepository, inningScoreRepository, plateAppearanceRepository, pitcherSubstitutionRepository, pitchRepository
        );
    }
}

