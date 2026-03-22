package com.baseball.application.game;

import com.baseball.domain.game.Game;
import com.baseball.domain.game.PitcherNameNormalizer;
import com.baseball.domain.game.PlateAppearance;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 경기 기록(타석·득점)만으로 승리/패전/세이브 투수를 추정합니다.
 * <p>
 * - 승리(W): 역전한 직전 반 이닝에 수비했던 승리 팀 투수.
 * - 패전(L): 역전당한 타석의 수비 투수(역전당한 팀).
 * - 세이브(S): MLB 규칙 9.19에 가깝게, 마지막으로 승리 팀이 수비하며 마친 투수가
 *   {@linkplain #qualifiesForSave 승리 투수가 아니고, 세이브 상황을 만족할 때}만 부여.
 *   (승리 투수와 동일 인물이면 세이브 불가 — 규칙상 동시 부여 불가)
 */
@Slf4j
@Component
public class WinningLosingSaveResolver {

    public record Result(String winningPitcherName, String losingPitcherName, String savePitcherName) {}

    /**
     * @param game 경기 (홈/원정 점수로 승패 판단)
     * @param plateAppearances 타석 목록 (이닝·초/말·sequenceOrder 순으로 정렬된 것)
     * @return 추정 W/L/S. 판단 불가 시 해당 항목은 null
     */
    public Result resolve(Game game, List<PlateAppearance> plateAppearances) {
        if (game == null || plateAppearances == null || plateAppearances.isEmpty()) {
            return new Result(null, null, null);
        }
        Integer homeScore = game.getHomeScore();
        Integer awayScore = game.getAwayScore();
        if (homeScore == null) homeScore = 0;
        if (awayScore == null) awayScore = 0;
        if (homeScore == awayScore) {
            return new Result(null, null, null);
        }
        boolean homeWins = homeScore > awayScore;

        List<PlateAppearance> ordered = plateAppearances.stream()
                .sorted(Comparator
                        .comparing(PlateAppearance::getInning, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(pa -> Boolean.TRUE.equals(pa.getIsTop()) ? 0 : 1)
                        .thenComparing(PlateAppearance::getSequenceOrder, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        int awayTotal = 0;
        int homeTotal = 0;
        Integer goAheadInning = null;
        Boolean goAheadIsTop = null;
        String goAheadDefensePitcher = null;

        for (PlateAppearance pa : ordered) {
            int runs = countRunsInPa(pa);
            if (runs <= 0) continue;

            Integer inn = pa.getInning();
            Boolean isTop = pa.getIsTop();
            if (inn == null || isTop == null) continue;

            int prevAway = awayTotal;
            int prevHome = homeTotal;
            if (Boolean.TRUE.equals(isTop)) {
                awayTotal += runs;
            } else {
                homeTotal += runs;
            }

            boolean winningTeamAheadNow = homeWins ? (homeTotal > awayTotal) : (awayTotal > homeTotal);
            boolean wasNotAheadBefore = homeWins ? (prevHome <= prevAway) : (prevAway <= prevHome);
            if (winningTeamAheadNow && wasNotAheadBefore) {
                goAheadInning = inn;
                goAheadIsTop = isTop;
                goAheadDefensePitcher = pa.getPitcherName();
            }
        }

        String winningPitcherName = null;
        String losingPitcherName = null;
        String savePitcherName = null;

        if (goAheadInning != null && goAheadIsTop != null) {
            losingPitcherName = blankToNull(goAheadDefensePitcher);

            int prevInning = Boolean.TRUE.equals(goAheadIsTop) ? goAheadInning - 1 : goAheadInning;
            boolean prevIsTop = !Boolean.TRUE.equals(goAheadIsTop);
            if (prevInning < 1) {
                prevInning = 1;
                prevIsTop = false;
            }
            if (prevInning >= 1) {
                for (PlateAppearance pa : ordered) {
                    if (Objects.equals(pa.getInning(), prevInning) && Boolean.TRUE.equals(pa.getIsTop()) == prevIsTop) {
                        winningPitcherName = blankToNull(pa.getPitcherName());
                        break;
                    }
                }
            }
        }

        String finisher = null;
        for (int i = ordered.size() - 1; i >= 0; i--) {
            PlateAppearance pa = ordered.get(i);
            Integer inn = pa.getInning();
            Boolean top = pa.getIsTop();
            if (inn == null || top == null) continue;
            boolean winningTeamDefense = winningTeamOnDefense(homeWins, top);
            if (winningTeamDefense) {
                finisher = blankToNull(pa.getPitcherName());
                break;
            }
        }

        if (finisher != null && qualifiesForSave(finisher, winningPitcherName, homeWins, ordered)) {
            savePitcherName = finisher;
        }

        return new Result(winningPitcherName, losingPitcherName, savePitcherName);
    }

    private static boolean winningTeamOnDefense(boolean homeWins, Boolean top) {
        return (homeWins && Boolean.TRUE.equals(top)) || (!homeWins && Boolean.FALSE.equals(top));
    }

    /**
     * 세이브 자격 (MLB 9.19 요지, 타석 데이터로 근사).
     * <ul>
     *   <li>승리 투수가 아닐 것</li>
     *   <li>다음 중 하나: (1) 진입 시점 승리 팀 리드가 1~3점이고, 이후 해당 투수가 맡은 타석이 3개 이상(약 1이닝·3아웃 근사)</li>
     *   <li>(2) 해당 투수가 맡은 타석이 9개 이상(약 3이닝 구원)</li>
     * </ul>
     */
    static boolean qualifiesForSave(String finisher, String winningPitcherName, boolean homeWins, List<PlateAppearance> ordered) {
        if (finisher == null || finisher.isBlank()) {
            return false;
        }
        if (PitcherNameNormalizer.samePitcher(winningPitcherName, finisher)) {
            return false;
        }
        Integer firstIdx = null;
        for (int i = 0; i < ordered.size(); i++) {
            PlateAppearance pa = ordered.get(i);
            if (pa.getInning() == null || pa.getIsTop() == null) {
                continue;
            }
            if (!winningTeamOnDefense(homeWins, pa.getIsTop())) {
                continue;
            }
            if (PitcherNameNormalizer.samePitcher(finisher, pa.getPitcherName())) {
                firstIdx = i;
                break;
            }
        }
        if (firstIdx == null) {
            return false;
        }
        int leadBeforeEntry = winningTeamLeadBeforePaIndex(ordered, homeWins, firstIdx);
        if (leadBeforeEntry < 1) {
            return false;
        }
        int paCount = countFinisherPasFromIndex(finisher, homeWins, ordered, firstIdx);
        // (2) 약 3이닝 이상 구원 (규칙 9.19(c))
        if (paCount >= 9) {
            return true;
        }
        // (1) 3점 이하 리드로 등판 후 최소 약 1이닝(3타석) (규칙 9.19(a))
        return leadBeforeEntry <= 3 && paCount >= 3;
    }

    /** firstIdx번째 타석이 시작되기 직전 승리 팀 리드 */
    private static int winningTeamLeadBeforePaIndex(List<PlateAppearance> ordered, boolean homeWins, int firstIdx) {
        int away = 0;
        int home = 0;
        for (int j = 0; j < firstIdx; j++) {
            PlateAppearance pa = ordered.get(j);
            int r = countRunsInPa(pa);
            if (r <= 0) {
                continue;
            }
            if (Boolean.TRUE.equals(pa.getIsTop())) {
                away += r;
            } else {
                home += r;
            }
        }
        if (homeWins) {
            return Math.max(0, home - away);
        }
        return Math.max(0, away - home);
    }

    private static int countFinisherPasFromIndex(String finisher, boolean homeWins, List<PlateAppearance> ordered, int firstIdx) {
        int c = 0;
        for (int i = firstIdx; i < ordered.size(); i++) {
            PlateAppearance pa = ordered.get(i);
            if (pa.getInning() == null || pa.getIsTop() == null) {
                continue;
            }
            if (!winningTeamOnDefense(homeWins, pa.getIsTop())) {
                continue;
            }
            if (PitcherNameNormalizer.samePitcher(finisher, pa.getPitcherName())) {
                c++;
            }
        }
        return c;
    }

    private static int countRunsInPa(PlateAppearance pa) {
        String text = (pa.getResultText() != null ? pa.getResultText() : "") + " ";
        if (pa.getRunnerPlaysList() != null) {
            for (String play : pa.getRunnerPlaysList()) {
                if (play != null) text += play + " ";
            }
        }
        int c = 0, i = 0;
        while ((i = text.indexOf("홈인", i)) != -1) {
            c++;
            i += 2;
        }
        return c;
    }

    private static String blankToNull(String s) {
        return (s != null && !s.isBlank()) ? s.trim() : null;
    }
}
