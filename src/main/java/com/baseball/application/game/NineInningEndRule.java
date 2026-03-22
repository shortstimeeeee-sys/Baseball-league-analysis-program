package com.baseball.application.game;

import com.baseball.application.game.dto.GameRecordImportDto;
import com.baseball.domain.game.Game.GameStatus;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 9회초 종료 시점에서 홈이 원정보다 점수가 높으면 야구 규칙상 9회말이 없이 경기가 끝난다.
 * 파싱 결과에 불필요한 9회말(말) 기록이 포함된 경우 제거한다.
 */
public final class NineInningEndRule {

    private NineInningEndRule() {
    }

    /**
     * DTO의 타석·투수교체·헤더 집합을 직접 수정한다.
     * 제거가 일어나면 {@link GameRecordImportDto.GameInfo#getStatus()}에 {@link GameStatus#COMPLETED}를 설정한다.
     */
    public static void applyToDto(GameRecordImportDto dto) {
        if (dto == null) {
            return;
        }
        boolean stripped = stripNineBottomIfHomeLeadsAfterNineTop(
                dto.getPlateAppearances(),
                dto.getPitcherSubstitutions(),
                dto.getHalfInningsWithHeader());
        if (stripped && dto.getGameInfo() != null) {
            dto.getGameInfo().setStatus(GameStatus.COMPLETED);
        }
    }

    /**
     * @return true면 9회말 기록을 제거했다.
     */
    public static boolean stripNineBottomIfHomeLeadsAfterNineTop(
            List<GameRecordImportDto.PlateAppearanceRow> plateAppearances,
            List<GameRecordImportDto.PitcherSubstitutionRow> pitcherSubstitutions,
            Set<String> halfInningsWithHeader) {
        if (plateAppearances == null || plateAppearances.isEmpty()) {
            return false;
        }
        boolean hasNineBottom = plateAppearances.stream().anyMatch(p -> p.getInning() == 9 && !p.isTop())
                || (pitcherSubstitutions != null && pitcherSubstitutions.stream().anyMatch(s -> s.getInning() == 9 && !s.isTop()))
                || (halfInningsWithHeader != null && halfInningsWithHeader.contains("9_false"));
        if (!hasNineBottom) {
            return false;
        }

        int awayThru9Top = 0;
        int homeThru8Bot = 0;
        for (GameRecordImportDto.PlateAppearanceRow r : plateAppearances) {
            int inn = r.getInning();
            if (inn < 1 || inn > 9) {
                continue;
            }
            int runs = countRuns(r);
            if (r.isTop()) {
                if (inn <= 9) {
                    awayThru9Top += runs;
                }
            } else {
                if (inn <= 8) {
                    homeThru8Bot += runs;
                }
            }
        }

        // 9회초까지 진행한 뒤 홈이 원정보다 점수가 높을 때만 9회말 불필요 (동점·원정 리드면 9회말 필요)
        if (homeThru8Bot <= awayThru9Top) {
            return false;
        }

        plateAppearances.removeIf(p -> p.getInning() == 9 && !p.isTop());
        if (pitcherSubstitutions != null) {
            pitcherSubstitutions.removeIf(s -> s.getInning() == 9 && !s.isTop());
        }
        if (halfInningsWithHeader != null) {
            halfInningsWithHeader.remove("9_false");
        }
        renumberPlateAppearancesAndRemapSubs(plateAppearances, pitcherSubstitutions);
        return true;
    }

    private static void renumberPlateAppearancesAndRemapSubs(
            List<GameRecordImportDto.PlateAppearanceRow> plateAppearances,
            List<GameRecordImportDto.PitcherSubstitutionRow> pitcherSubstitutions) {
        plateAppearances.sort(Comparator.comparingInt(GameRecordImportDto.PlateAppearanceRow::getInning)
                .thenComparing((a, b) -> Boolean.compare(b.isTop(), a.isTop()))
                .thenComparingInt(GameRecordImportDto.PlateAppearanceRow::getSequenceOrder));
        Map<Integer, Integer> oldToNew = new HashMap<>();
        for (int i = 0; i < plateAppearances.size(); i++) {
            GameRecordImportDto.PlateAppearanceRow row = plateAppearances.get(i);
            int oldSeq = row.getSequenceOrder();
            int newSeq = i + 1;
            oldToNew.put(oldSeq, newSeq);
            row.setSequenceOrder(newSeq);
        }
        if (pitcherSubstitutions != null) {
            for (GameRecordImportDto.PitcherSubstitutionRow s : pitcherSubstitutions) {
                Integer ao = s.getAfterPaSequenceOrder();
                if (ao != null && ao > 0) {
                    Integer n = oldToNew.get(ao);
                    if (n != null) {
                        s.setAfterPaSequenceOrder(n);
                    } else {
                        s.setAfterPaSequenceOrder(0);
                    }
                }
            }
        }
    }

    private static int countRuns(GameRecordImportDto.PlateAppearanceRow r) {
        StringBuilder sb = new StringBuilder();
        if (r.getResultText() != null) {
            sb.append(r.getResultText());
        }
        if (r.getRunnerPlays() != null) {
            for (String play : r.getRunnerPlays()) {
                if (play != null) {
                    sb.append(' ').append(play);
                }
            }
        }
        String combined = sb.toString();
        int count = 0;
        int idx = 0;
        while ((idx = combined.indexOf("홈인", idx)) != -1) {
            count++;
            idx += 2;
        }
        return count;
    }
}
