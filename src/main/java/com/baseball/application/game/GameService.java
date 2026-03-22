package com.baseball.application.game;

import com.baseball.common.NotFoundException;
import com.baseball.domain.game.Game;
import com.baseball.domain.game.GameRepository;
import com.baseball.domain.game.Game.GameStatus;
import com.baseball.domain.game.InningScore;
import com.baseball.domain.game.InningScoreRepository;
import com.baseball.domain.game.Pitch;
import com.baseball.domain.game.PitchRepository;
import com.baseball.domain.game.PitcherSubstitution;
import com.baseball.domain.game.PitcherSubstitutionRepository;
import com.baseball.domain.game.PlateAppearance;
import com.baseball.domain.game.PlateAppearanceRepository;
import com.baseball.domain.team.TeamRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GameService {

    public enum GameCategory {
        LEAGUE,
        EXHIBITION,
        ALL
    }

    public record GameCreateResult(Game game, boolean mergedIntoExisting) {}

    private final GameRepository gameRepository;
    private final TeamRepository teamRepository;
    private final InningScoreRepository inningScoreRepository;
    private final PlateAppearanceRepository plateAppearanceRepository;
    private final PitcherSubstitutionRepository pitcherSubstitutionRepository;
    private final PitchRepository pitchRepository;

    public List<Game> findAll() {
        return gameRepository.findAllByOrderByGameDateTimeDesc();
    }

    public List<Game> findAllByCategory(GameCategory category) {
        if (category == GameCategory.EXHIBITION) {
            return gameRepository.findByExhibitionTrueOrderByGameDateTimeDesc();
        }
        if (category == GameCategory.ALL) {
            return gameRepository.findAllByOrderByGameDateTimeDesc();
        }
        return gameRepository.findByExhibitionFalseOrderByGameDateTimeDesc();
    }

    /** 경기 메뉴용: 완료된 경기만 */
    public List<Game> findAllCompleted() {
        return gameRepository.findByStatusOrderByGameDateTimeDesc(Game.GameStatus.COMPLETED);
    }

    /** 완료된 경기만 날짜별 조회 (date가 null이면 전체) */
    public List<Game> findAllCompletedByDate(LocalDate date) {
        if (date == null) {
            return gameRepository.findByStatusOrderByGameDateTimeDesc(Game.GameStatus.COMPLETED);
        }
        LocalDateTime start = date.atStartOfDay();
        LocalDateTime end = date.plusDays(1).atStartOfDay();
        return gameRepository.findByStatusAndGameDateTimeBetweenOrderByGameDateTimeDesc(
                Game.GameStatus.COMPLETED, start, end);
    }

    /** 전체 경기 날짜별 조회 (date가 null이면 전체) */
    public List<Game> findAllByDate(LocalDate date) {
        return findAllByDateAndCategory(date, GameCategory.ALL);
    }

    /** 전체 경기 날짜별 조회 + 분류 필터 */
    public List<Game> findAllByDateAndCategory(LocalDate date, GameCategory category) {
        if (date == null) {
            return findAllByCategory(category);
        }
        LocalDateTime start = date.atStartOfDay();
        LocalDateTime end = date.plusDays(1).atStartOfDay();
        if (category == GameCategory.EXHIBITION) {
            return gameRepository.findByExhibitionTrueAndGameDateTimeBetweenOrderByGameDateTimeDesc(start, end);
        }
        if (category == GameCategory.ALL) {
            return gameRepository.findByGameDateTimeBetweenOrderByGameDateTimeDesc(start, end);
        }
        return gameRepository.findByExhibitionFalseAndGameDateTimeBetweenOrderByGameDateTimeDesc(start, end);
    }

    /** 날짜 필터용: 경기가 있는 날짜 목록 (최신순) */
    public List<LocalDate> getDistinctGameDatesCompleted() {
        return gameRepository.findByStatusOrderByGameDateTimeDesc(Game.GameStatus.COMPLETED).stream()
                .map(g -> g.getGameDateTime() != null ? g.getGameDateTime().toLocalDate() : null)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    /** 날짜 필터용: 전체 경기 날짜 목록 (최신순) */
    public List<LocalDate> getDistinctGameDates() {
        return getDistinctGameDatesByCategory(GameCategory.ALL);
    }

    /** 날짜 필터용: 분류별 경기 날짜 목록 (최신순) */
    public List<LocalDate> getDistinctGameDatesByCategory(GameCategory category) {
        List<Game> source = findAllByCategory(category);
        return source.stream()
                .map(g -> g.getGameDateTime() != null ? g.getGameDateTime().toLocalDate() : null)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    public GameCategory parseCategory(String raw) {
        if (raw == null || raw.isBlank()) {
            return GameCategory.LEAGUE;
        }
        String v = raw.trim().toUpperCase(java.util.Locale.ROOT);
        try {
            return GameCategory.valueOf(v);
        } catch (IllegalArgumentException ex) {
            return GameCategory.LEAGUE;
        }
    }

    public String categoryQueryValue(GameCategory category) {
        if (category == null) return "LEAGUE";
        return category.name();
    }

    public String categoryDisplayName(GameCategory category) {
        if (category == GameCategory.EXHIBITION) return "시범 경기";
        if (category == GameCategory.ALL) return "전체 경기";
        return "리그 경기";
    }

    public boolean isLeagueCategory(GameCategory category) {
        return category == null || category == GameCategory.LEAGUE;
    }

    public boolean isExhibitionCategory(GameCategory category) {
        return category == GameCategory.EXHIBITION;
    }

    public boolean isAllCategory(GameCategory category) {
        return category == GameCategory.ALL;
    }

    public Game getById(Long id) {
        return gameRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("경기를 찾을 수 없습니다. id=" + id));
    }

    /** 경기 + 이닝별 스코어 + 타석(투구 포함) 목록. 상세/중계 화면용 */
    public GameDetailView getDetailWithRecord(Long gameId) {
        Game game = gameRepository.findByIdWithTeams(gameId)
                .orElseThrow(() -> new NotFoundException("경기를 찾을 수 없습니다. id=" + gameId));
        var inningScores = inningScoreRepository.findByGameIdOrderByInning(gameId);
        Map<Integer, InningScore> byInning = new LinkedHashMap<>();
        for (int i = 1; i <= 9; i++) {
            byInning.put(i, null);
        }
        for (InningScore is : inningScores) {
            if (is.getInning() != null) {
                byInning.put(is.getInning(), is);
            }
        }
        // 1~9회 순서 리스트(인덱스 1~9 사용). 템플릿에서 inningScoreByIndex[i]로 안정적으로 참조
        List<InningScore> byIndex = new ArrayList<>(10);
        byIndex.add(null);
        for (int i = 1; i <= 9; i++) {
            byIndex.add(byInning.get(i));
        }
        // 1회초 → 1회말 → 2회초 → … 순서. 1회초만 1번부터(타순) 정렬, 나머지는 등장 순서(연결성) 유지
        var raw = plateAppearanceRepository.findByGameIdWithPitches(gameId);
        String scoreboardSituationLabel = computeScoreboardSituationLabel(game, raw);
        List<PlateAppearance> plateAppearances = orderPlateAppearancesForDisplay(raw);
        List<String> inningsWithError = findInningsWithError(plateAppearances);
        List<PitcherSubstitution> substitutions = pitcherSubstitutionRepository.findByGameIdOrderByInningAscIsTopDescBattersFacedAsc(gameId);
        List<RecordDisplayItem> recordDisplayItems = buildRecordDisplayItems(game, plateAppearances, substitutions);
        PlateAppearance.EmphasisHtml emphasis = PlateAppearance.buildEmphasisHtml(plateAppearances);
        recordDisplayItems = mergeEmphasisIntoRecordDisplayItems(recordDisplayItems, emphasis);
        List<RecordDisplayItem> keyHighlightItems = buildKeyHighlightItems(recordDisplayItems);
        return new GameDetailView(game, inningScores, byInning, byIndex, plateAppearances, inningsWithError, recordDisplayItems, keyHighlightItems, scoreboardSituationLabel);
    }

    /** 아웃 번호 강조 HTML을 타석마다 붙인다. (Thymeleaf Map 조회 키 불일치로 맵 방식이 실패하는 것을 피함) */
    private static List<RecordDisplayItem> mergeEmphasisIntoRecordDisplayItems(
            List<RecordDisplayItem> items, PlateAppearance.EmphasisHtml emphasis) {
        if (items == null || items.isEmpty() || emphasis == null) {
            return items != null ? items : List.of();
        }
        Map<Long, String> res = emphasis.resultById() != null ? emphasis.resultById() : Map.of();
        Map<Long, List<String>> run = emphasis.runnerLinesById() != null ? emphasis.runnerLinesById() : Map.of();
        List<RecordDisplayItem> out = new ArrayList<>(items.size());
        for (RecordDisplayItem it : items) {
            if (it.plateAppearance() == null) {
                out.add(it);
                continue;
            }
            Long id = it.plateAppearance().getId();
            if (id == null) {
                out.add(it);
                continue;
            }
            String resultHtml = res.get(id);
            List<String> runnerHtml = run.get(id);
            out.add(new RecordDisplayItem(
                    it.plateAppearance(),
                    it.substitution(),
                    it.halfInningBreak(),
                    it.halfInningHandoff(),
                    resultHtml,
                    runnerHtml));
        }
        return out;
    }

    /**
     * 스코어보드 중앙 문구: [경기 완료 처리]로 확정되면 종료, 아니면 DB에 등록된 마지막 타석 기준 이닝·초/말.
     * 타석 순서는 이닝↑·초→말·sequence 순의 마지막이 가장 최근 기록 위치.
     * 9회초 종료 직후 홈이 원정보다 점수가 높으면 9회말 없이 끝나므로 그때도 종료로 표시.
     */
    public String computeScoreboardSituationLabel(Game game, List<PlateAppearance> chronologicalOrder) {
        if (game == null) {
            return "";
        }
        List<PlateAppearance> list = chronologicalOrder != null ? chronologicalOrder : List.of();
        PlateAppearance last = list.isEmpty() ? null : list.get(list.size() - 1);

        if (game.getStatus() == GameStatus.COMPLETED) {
            return "종료";
        }

        if (last == null || last.getInning() == null || last.getIsTop() == null) {
            return game.getStatus() != null ? game.getStatus().getDisplayName() : "";
        }

        int inn = last.getInning();
        boolean top = Boolean.TRUE.equals(last.getIsTop());
        int h = game.getHomeScore() != null ? game.getHomeScore() : 0;
        int a = game.getAwayScore() != null ? game.getAwayScore() : 0;

        if (inn == 9 && top && h > a) {
            return "종료";
        }

        return inn + "회" + (top ? "초" : "말");
    }

    /** 파싱된 내용 중 주요 내용만: 득점 발생 타석 + 투수 교체 + 실책 타석 */
    private List<RecordDisplayItem> buildKeyHighlightItems(List<RecordDisplayItem> recordDisplayItems) {
        if (recordDisplayItems == null) return List.of();
        return recordDisplayItems.stream()
                .filter(item -> item.substitution() != null
                        || (item.plateAppearance() != null && (hasRun(item.plateAppearance()) || hasError(item.plateAppearance()))))
                .collect(Collectors.toList());
    }

    /** 득점이 발생한 타석: 결과·주자 플레이에 '홈인' 또는 '득점' 포함 */
    private boolean hasRun(PlateAppearance pa) {
        if (pa.getResultText() != null) {
            if (pa.getResultText().contains("홈인") || pa.getResultText().contains("득점")) return true;
        }
        List<String> plays = pa.getRunnerPlaysList();
        if (plays != null) for (String play : plays) {
            if (play != null && (play.contains("홈인") || play.contains("득점"))) return true;
        }
        return false;
    }

    /**
     * 타석(PA)과 투수 교체를 파싱 순서대로 섞어서 기록 화면용 리스트 생성. 교체는 afterPaSequenceOrder에 따라 해당 타석 다음 위치에 표시.
     * 각 반 이닝의 마지막 타석(및 그 직후 교체) 뒤에는 {@link Game#getHalfInningBreakNotes()}에서 파싱한 공·교체 메모 행을 넣는다.
     */
    private List<RecordDisplayItem> buildRecordDisplayItems(Game game, List<PlateAppearance> plateAppearances, List<PitcherSubstitution> substitutions) {
        Map<String, HalfInningBreakNote> breakByKey = parseHalfInningBreakNotes(game != null ? game.getHalfInningBreakNotes() : null);
        List<RecordDisplayItem> out = new ArrayList<>();
        Set<String> halfInningFirstPaShown = new LinkedHashSet<>();
        Map<String, List<PitcherSubstitution>> subsByHalf = (substitutions != null ? substitutions : List.<PitcherSubstitution>of()).stream()
                .collect(Collectors.groupingBy(ps -> ps.getInning() + "_" + ps.getIsTop(), LinkedHashMap::new, Collectors.toList()));
        List<PlateAppearance> list = plateAppearances != null ? plateAppearances : List.of();
        if (shouldShowGameStartBanner(list)) {
            out.add(RecordDisplayItem.handoff(HalfInningHandoff.gameStart()));
        }
        for (int idx = 0; idx < list.size(); idx++) {
            PlateAppearance pa = list.get(idx);
            Integer inning = pa.getInning();
            Boolean isTop = pa.getIsTop();
            if (inning == null || isTop == null) {
                out.add(RecordDisplayItem.pa(pa));
                continue;
            }
            String key = inning + "_" + isTop;
            if (!halfInningFirstPaShown.contains(key)) {
                halfInningFirstPaShown.add(key);
                List<PitcherSubstitution> subsHead = subsByHalf.get(key);
                if (subsHead != null) {
                    for (PitcherSubstitution sub : subsHead) {
                        Integer after = sub.getAfterPaSequenceOrder();
                        if (after == null || after == 0) {
                            out.add(RecordDisplayItem.sub(sub));
                        }
                    }
                }
            }
            out.add(RecordDisplayItem.pa(pa));
            List<PitcherSubstitution> subs = subsByHalf.get(key);
            if (subs != null) {
                Integer paSeq = pa.getSequenceOrder();
                for (PitcherSubstitution sub : subs) {
                    Integer after = sub.getAfterPaSequenceOrder();
                    if (after != null && after != 0 && after.equals(paSeq)) {
                        out.add(RecordDisplayItem.sub(sub));
                    }
                }
            }
            boolean lastOfHalf = (idx == list.size() - 1)
                    || !sameHalfInning(list.get(idx + 1), inning, isTop);
            if (lastOfHalf) {
                PlateAppearance nextPa = (idx + 1 < list.size()) ? list.get(idx + 1) : null;
                HalfInningHandoff handoff = buildHalfHandoffDisplay(inning, Boolean.TRUE.equals(isTop), nextPa);
                handoff = appendGameEndSuffixForRecordLine(handoff, game, list, idx == list.size() - 1);
                out.add(RecordDisplayItem.handoff(handoff));
                HalfInningBreakNote note = breakByKey.get(key);
                if (note != null && note.hasContent()) {
                    out.add(RecordDisplayItem.halfBreak(note));
                }
            }
        }
        return out;
    }

    /** 첫 타석이 1회초이면 기록 맨 앞에 ◇ 경기시작 / 1회초 시작 배너를 둔다. */
    private static boolean shouldShowGameStartBanner(List<PlateAppearance> list) {
        if (list == null || list.isEmpty()) {
            return false;
        }
        PlateAppearance p0 = list.get(0);
        return p0.getInning() != null && p0.getInning() == 1 && Boolean.TRUE.equals(p0.getIsTop());
    }

    /**
     * 마지막 타석 기준 반 이닝이 끝난 뒤 표시할 자동 교대 멘트 (예: 1회초 종료 → 1회말 시작).
     * 실제 3아웃 여부는 판별하지 않고, 기록상 해당 반의 마지막 타석 직후에 둔다.
     */
    static HalfInningHandoff buildHalfHandoffDisplay(int inning, boolean endedTop, PlateAppearance nextPa) {
        if (endedTop) {
            if (nextPa != null && nextPa.getInning() != null && nextPa.getIsTop() != null
                    && nextPa.getInning().equals(inning) && Boolean.FALSE.equals(nextPa.getIsTop())) {
                return new HalfInningHandoff(inning, true, inning + "회초 종료 → " + inning + "회말 시작");
            }
            return new HalfInningHandoff(inning, true, inning + "회초 종료");
        }
        if (nextPa != null && nextPa.getInning() != null && nextPa.getIsTop() != null
                && nextPa.getInning().equals(inning + 1) && Boolean.TRUE.equals(nextPa.getIsTop())) {
            return new HalfInningHandoff(inning, false, inning + "회말 종료 → " + (inning + 1) + "회초 시작");
        }
        return new HalfInningHandoff(inning, false, inning + "회말 종료");
    }

    /**
     * 스코어보드 중앙과 동일하게 {@link #computeScoreboardSituationLabel}가 "종료"일 때만,
     * 해당 경기의 <strong>마지막 타석</strong>이 속한 반 이닝 끝 멘트에 {@code / 경기 종료}를 붙인다.
     * (DB가 COMPLETED가 아니어도 9회초 끝 홈 리드 등 스코어보드가 종료로 보이는 경우 포함 · 연장 10회+ 마지막도 동일)
     */
    HalfInningHandoff appendGameEndSuffixForRecordLine(HalfInningHandoff handoff, Game game,
                                                       List<PlateAppearance> chronological, boolean lastPaOfGame) {
        if (handoff == null || game == null || !lastPaOfGame || chronological == null || chronological.isEmpty()) {
            return handoff;
        }
        if (!"종료".equals(computeScoreboardSituationLabel(game, chronological))) {
            return handoff;
        }
        String line = handoff.line();
        if (line != null && line.contains("경기 종료")) {
            return handoff;
        }
        return new HalfInningHandoff(handoff.filterInning(), handoff.endedTop(), line + HalfInningHandoff.GAME_END_DISPLAY_SUFFIX);
    }

    private static final Pattern HALF_INNING_HEAD = Pattern.compile("^(\\d+)(초|말)$");

    /**
     * 줄 단위 파싱. 한 줄: {@code N초|공: ...|교체: ...} 또는 {@code N말|...} (공·교체 순서 자유, 둘 중 하나만 있어도 됨).
     */
    static Map<String, HalfInningBreakNote> parseHalfInningBreakNotes(String raw) {
        Map<String, HalfInningBreakNote> map = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return map;
        }
        for (String line : raw.split("\\R")) {
            String lineTrim = line.trim();
            if (lineTrim.isEmpty()) {
                continue;
            }
            int pipe = lineTrim.indexOf('|');
            if (pipe < 0) {
                continue;
            }
            String head = lineTrim.substring(0, pipe).trim();
            Matcher hm = HALF_INNING_HEAD.matcher(head);
            if (!hm.matches()) {
                continue;
            }
            int inn = Integer.parseInt(hm.group(1));
            boolean top = "초".equals(hm.group(2));
            String rest = lineTrim.substring(pipe + 1);
            String gong = "";
            String change = "";
            for (String seg : rest.split("\\|", -1)) {
                String t = seg.trim();
                if (t.startsWith("공:")) {
                    gong = t.substring(2).trim();
                } else if (t.startsWith("교체:")) {
                    change = t.substring(3).trim();
                }
            }
            if (gong.isEmpty() && change.isEmpty()) {
                continue;
            }
            map.put(inn + "_" + top, new HalfInningBreakNote(inn, top, gong, change));
        }
        return map;
    }

    /** 실책이 발생한 반 이닝 목록 (예: ["2회초", "5회말"]). 타석 결과·주자 플레이에 '실책' 포함된 이닝 */
    private List<String> findInningsWithError(List<PlateAppearance> plateAppearances) {
        if (plateAppearances == null || plateAppearances.isEmpty()) return List.of();
        Set<String> set = new LinkedHashSet<>();
        for (PlateAppearance pa : plateAppearances) {
            if (pa.getInning() == null || pa.getIsTop() == null) continue;
            if (hasError(pa)) set.add(pa.getInning() + "회" + (pa.getIsTop() ? "초" : "말"));
        }
        return new ArrayList<>(set);
    }

    /** 실책 문구를 폭넓게 인정(단, 무실책 제외) */
    private boolean hasError(PlateAppearance pa) {
        if (pa.getResultText() != null && hasRealError(pa.getResultText())) return true;
        List<String> plays = pa.getRunnerPlaysList();
        if (plays != null) for (String play : plays) { if (play != null && hasRealError(play)) return true; }
        return false;
    }

    private boolean hasRealError(String text) {
        if (text == null || text.contains("무실책")) return false;
        return text.contains("실책");
    }

    /** 1회초는 1번→9번 타순으로, 2회 이후는 등장 순서(sequenceOrder) → 7,8,9,1,2… 순으로 표시 */
    private List<PlateAppearance> orderPlateAppearancesForDisplay(List<PlateAppearance> raw) {
        if (raw == null || raw.isEmpty()) return raw;
        List<PlateAppearance> result = new ArrayList<>(raw.size());
        int i = 0;
        while (i < raw.size()) {
            PlateAppearance pa = raw.get(i);
            boolean isFirstHalf = pa.getInning() != null && pa.getInning() == 1 && Boolean.TRUE.equals(pa.getIsTop());
            if (isFirstHalf) {
                List<PlateAppearance> firstInningTop = new ArrayList<>();
                while (i < raw.size()) {
                    PlateAppearance p = raw.get(i);
                    if (p.getInning() != null && p.getInning() == 1 && Boolean.TRUE.equals(p.getIsTop())) {
                        firstInningTop.add(p);
                        i++;
                    } else break;
                }
                firstInningTop.sort(Comparator.comparing(PlateAppearance::getBatterOrder, Comparator.nullsLast(Comparator.naturalOrder())));
                result.addAll(firstInningTop);
            } else {
                Integer inning = pa.getInning();
                Boolean isTop = pa.getIsTop();
                List<PlateAppearance> halfInning = new ArrayList<>();
                while (i < raw.size()) {
                    PlateAppearance p = raw.get(i);
                    if (sameHalfInning(p, inning, isTop)) {
                        halfInning.add(p);
                        i++;
                    } else break;
                }
                sortHalfInningByCycleOrder(halfInning);
                result.addAll(halfInning);
            }
        }
        return result;
    }

    /** 이닝 내 타순 사이클(7→8→9→1…) 순으로 정렬. 2회초면 박동원7번·구본혁8번·박해민9번·홍창기1번 순 */
    private void sortHalfInningByCycleOrder(List<PlateAppearance> halfInning) {
        if (halfInning == null || halfInning.size() <= 1) return;
        int cycleStart = findCycleStart(halfInning);
        halfInning.sort((a, b) -> {
            Integer oa = a.getBatterOrder();
            Integer ob = b.getBatterOrder();
            if (oa == null && ob == null) return Comparator.nullsLast(Integer::compareTo).compare(a.getSequenceOrder(), b.getSequenceOrder());
            if (oa == null) return 1;
            if (ob == null) return -1;
            int keyA = (oa - cycleStart + 9) % 9;
            int keyB = (ob - cycleStart + 9) % 9;
            if (keyA != keyB) return Integer.compare(keyA, keyB);
            return Comparator.nullsLast(Integer::compareTo).compare(a.getSequenceOrder(), b.getSequenceOrder());
        });
    }

    /** 이닝 내 첫 타순(7이면 7→8→9→1 순). 선수 번호 집합에서 (x-2+9)%9+1 이 없으면 x가 사이클 시작 */
    private int findCycleStart(List<PlateAppearance> halfInning) {
        if (halfInning == null || halfInning.isEmpty()) return 1;
        Set<Integer> orders = halfInning.stream()
                .map(PlateAppearance::getBatterOrder)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (orders.isEmpty()) return 1;
        for (int start = 1; start <= 9; start++) {
            int prev = (start - 2 + 9) % 9 + 1;
            if (!orders.contains(prev)) return start;
        }
        return 1;
    }

    private boolean sameHalfInning(PlateAppearance p, Integer inning, Boolean isTop) {
        return (inning != null && inning.equals(p.getInning())) && (isTop != null && isTop.equals(p.getIsTop()));
    }

    /** 회초/말 마지막 타석 직후 표시용(수동 입력). {@code top}=초(원정 공격) */
    public record HalfInningBreakNote(int inning, boolean top, String gongText, String substitutionText) {
        public boolean hasContent() {
            return (gongText != null && !gongText.isBlank())
                    || (substitutionText != null && !substitutionText.isBlank());
        }
    }

    /**
     * 자동 생성: 반 이닝 종료 → 다음 반 시작 안내.
     * {@code filterInning}: 이닝 탭 필터용(방금 끝난 반의 이닝 번호).
     * {@code endedTop}: 방금 끝난 반이 회초면 true, 회말이면 false(파싱 확정 키 {@link #confirmHalfKey()}에 사용).
     */
    public record HalfInningHandoff(int filterInning, boolean endedTop, String line) {
        /** 기록 줄 끝에 붙는 경기 종료 표시 (템플릿에서 {@code / } 는 기본색, {@code 경기 종료} 만 빨간색) */
        public static final String GAME_END_DISPLAY_SUFFIX = " / 경기 종료";

        private static final String GAME_START_LINE_MARKER = "__GAME_START__";

        /** ◇ 경기시작 / 1회초 시작 (템플릿 전용 마커) */
        public static HalfInningHandoff gameStart() {
            return new HalfInningHandoff(1, true, GAME_START_LINE_MARKER);
        }

        public boolean gameStartBanner() {
            return GAME_START_LINE_MARKER.equals(line);
        }

        /** 파싱 병합 잠금 키 (예: {@code 1_true} = 1회초). 경기 시작 배너는 전용 키. */
        public String confirmHalfKey() {
            if (gameStartBanner()) {
                return "__game_start__";
            }
            return filterInning + "_" + endedTop;
        }

        /** {@link #GAME_END_DISPLAY_SUFFIX} 앞까지(◇ 바로 뒤 본문). */
        public String lineWithoutGameEndSuffix() {
            if (gameStartBanner()) {
                return "";
            }
            if (line != null && line.endsWith(GAME_END_DISPLAY_SUFFIX)) {
                return line.substring(0, line.length() - GAME_END_DISPLAY_SUFFIX.length());
            }
            return line;
        }

        public boolean hasGameEndSuffix() {
            return line != null && line.endsWith(GAME_END_DISPLAY_SUFFIX);
        }

        /** {@code / } 구분자(기본색). 경기 종료 접미가 있을 때만. */
        public String gameEndSuffixSeparator() {
            return hasGameEndSuffix() ? " / " : null;
        }

        /** 빨간색으로만 쓸 라벨. */
        public String gameEndSuffixLabel() {
            return hasGameEndSuffix() ? "경기 종료" : null;
        }
    }

    /** 기록 화면에서 타석(PA) 또는 투수 교체 또는 반 이닝 공·교체 메모 또는 자동 교대 멘트 중 하나를 표시 */
    public record RecordDisplayItem(
            PlateAppearance plateAppearance,
            PitcherSubstitution substitution,
            HalfInningBreakNote halfInningBreak,
            HalfInningHandoff halfInningHandoff,
            /** {@link PlateAppearance#buildEmphasisHtml} 결과(타석 결과 줄). null이면 엔티티 폴백 */
            String resultEmphasisHtml,
            /** 주자 줄별 강조 HTML. null이면 엔티티 폴백 */
            List<String> runnerPlaysEmphasisHtml) {

        public static RecordDisplayItem pa(PlateAppearance pa) {
            return new RecordDisplayItem(pa, null, null, null, null, null);
        }

        public static RecordDisplayItem sub(PitcherSubstitution s) {
            return new RecordDisplayItem(null, s, null, null, null, null);
        }

        public static RecordDisplayItem halfBreak(HalfInningBreakNote n) {
            return new RecordDisplayItem(null, null, n, null, null, null);
        }

        public static RecordDisplayItem handoff(HalfInningHandoff h) {
            return new RecordDisplayItem(null, null, null, h, null, null);
        }
    }

    @Transactional
    public GameCreateResult create(Long homeTeamId, Long awayTeamId, LocalDateTime gameDateTime, String venue,
                                   Integer homeScore, Integer awayScore, GameStatus status, String memo,
                                   String halfInningTransitionNotes,
                                   String halfInningBreakNotes,
                                   boolean doubleheader, boolean exhibition) {
        if (gameDateTime == null) {
            throw new IllegalArgumentException("경기 날짜/시간은 필수입니다.");
        }
        if (!doubleheader) {
            Optional<Game> existing = findExistingSameMatchup(gameDateTime.toLocalDate(), homeTeamId, awayTeamId, exhibition);
            if (existing.isPresent()) {
                Game g = existing.get();
                g.updateResult(homeScore, awayScore, status, memo);
                g.updateSchedule(venue, gameDateTime);
                return new GameCreateResult(gameRepository.save(g), true);
            }
        }
        var homeTeam = teamRepository.findById(homeTeamId)
                .orElseThrow(() -> new NotFoundException("홈 팀을 찾을 수 없습니다. id=" + homeTeamId));
        var awayTeam = teamRepository.findById(awayTeamId)
                .orElseThrow(() -> new NotFoundException("원정 팀을 찾을 수 없습니다. id=" + awayTeamId));
        Game game = Game.builder()
                .homeTeam(homeTeam)
                .awayTeam(awayTeam)
                .gameDateTime(gameDateTime != null ? gameDateTime : LocalDateTime.now())
                .venue(venue)
                .homeScore(homeScore)
                .awayScore(awayScore)
                .status(status != null ? status : GameStatus.SCHEDULED)
                .memo(memo)
                .halfInningTransitionNotes(blankToNull(halfInningTransitionNotes))
                .halfInningBreakNotes(blankToNull(halfInningBreakNotes))
                .doubleheader(doubleheader)
                .exhibition(exhibition)
                .build();
        return new GameCreateResult(gameRepository.save(game), false);
    }

    /**
     * 같은 날짜·같은 홈/원정·같은 리그(둘 다 리그가 있으면 동일)일 때 병합 대상 경기.
     * 더블헤더(2차전)로 표시된 행은 제외하고, 없으면 목록 첫 행.
     */
    private Optional<Game> findExistingSameMatchup(LocalDate date, Long homeTeamId, Long awayTeamId, boolean exhibition) {
        var homeTeam = teamRepository.findById(homeTeamId).orElse(null);
        var awayTeam = teamRepository.findById(awayTeamId).orElse(null);
        if (homeTeam == null || awayTeam == null) {
            return Optional.empty();
        }
        if (homeTeam.getLeague() != null && awayTeam.getLeague() != null
                && !Objects.equals(homeTeam.getLeague().getId(), awayTeam.getLeague().getId())) {
            return Optional.empty();
        }
        LocalDateTime start = date.atStartOfDay();
        LocalDateTime end = date.plusDays(1).atStartOfDay();
        List<Game> list = gameRepository.findByHomeAwayAndGameDateTimeBetweenOrderByGameDateTimeDesc(
                homeTeamId, awayTeamId, start, end);
        if (list == null || list.isEmpty()) {
            return Optional.empty();
        }
        List<Game> sameCategory = list.stream()
                .filter(g -> g.isExhibition() == exhibition)
                .toList();
        if (sameCategory.isEmpty()) {
            return Optional.empty();
        }
        Optional<Game> firstSlot = sameCategory.stream().filter(g -> !g.isDoubleheader()).findFirst();
        return firstSlot.or(() -> Optional.of(sameCategory.get(0)));
    }

    @Transactional
    public Game updateResult(Long id, Integer homeScore, Integer awayScore, GameStatus status, String memo) {
        Game game = getById(id);
        game.updateResult(homeScore, awayScore, status, memo);
        return game;
    }

    @Transactional
    public Game updateGameFull(Long id, Integer homeScore, Integer awayScore, GameStatus status, String memo,
                               String venue, LocalDateTime gameDateTime, boolean doubleheader, boolean exhibition,
                               String halfInningTransitionNotes,
                               String halfInningBreakNotes) {
        Game game = getById(id);
        game.updateResult(homeScore, awayScore, status, memo);
        game.updateSchedule(venue, gameDateTime);
        game.setDoubleheader(doubleheader);
        game.setExhibition(exhibition);
        game.setHalfInningTransitionNotes(blankToNull(halfInningTransitionNotes));
        game.setHalfInningBreakNotes(blankToNull(halfInningBreakNotes));
        return gameRepository.save(game);
    }

    private static String blankToNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s;
    }

    @Transactional(readOnly = false)
    public void addRecordConfirmedHalfKeys(Long gameId, Collection<String> halfKeys) {
        Game g = getById(gameId);
        g.addRecordConfirmedHalfKeys(halfKeys);
        gameRepository.save(g);
    }

    @Transactional(readOnly = false)
    public void removeRecordConfirmedHalfKeys(Long gameId, Collection<String> halfKeys) {
        Game g = getById(gameId);
        g.removeRecordConfirmedHalfKeys(halfKeys);
        gameRepository.save(g);
    }

    @Transactional
    public void delete(Long id) {
        getById(id);
        // Game 엔티티에 매핑되지 않은 pitcher_substitutions 등 FK 때문에 순서대로 삭제
        List<PitcherSubstitution> subs = pitcherSubstitutionRepository.findByGameIdOrderByInningAscIsTopDescBattersFacedAsc(id);
        if (!subs.isEmpty()) {
            pitcherSubstitutionRepository.deleteAll(subs);
        }
        List<PlateAppearance> pas = plateAppearanceRepository.findByGameIdWithPitches(id);
        for (PlateAppearance pa : pas) {
            List<Pitch> pitches = pa.getPitches();
            if (pitches != null && !pitches.isEmpty()) {
                pitchRepository.deleteAll(pitches);
            }
        }
        if (!pas.isEmpty()) {
            plateAppearanceRepository.deleteAll(pas);
        }
        List<InningScore> innings = inningScoreRepository.findByGameIdOrderByInning(id);
        if (!innings.isEmpty()) {
            inningScoreRepository.deleteAll(innings);
        }
        gameRepository.deleteById(id);
    }

    @Transactional
    public void clearRecordData(Long id) {
        Game game = getById(id);
        List<PitcherSubstitution> subs = pitcherSubstitutionRepository.findByGameIdOrderByInningAscIsTopDescBattersFacedAsc(id);
        if (!subs.isEmpty()) {
            pitcherSubstitutionRepository.deleteAll(subs);
        }
        List<PlateAppearance> pas = plateAppearanceRepository.findByGameIdWithPitches(id);
        for (PlateAppearance pa : pas) {
            List<Pitch> pitches = pa.getPitches();
            if (pitches != null && !pitches.isEmpty()) {
                pitchRepository.deleteAll(pitches);
            }
        }
        if (!pas.isEmpty()) {
            plateAppearanceRepository.deleteAll(pas);
        }
        List<InningScore> innings = inningScoreRepository.findByGameIdOrderByInning(id);
        if (!innings.isEmpty()) {
            inningScoreRepository.deleteAll(innings);
        }
        game.clearRecordSummary();
        game.setRecordConfirmedHalfKeys(null);
        gameRepository.save(game);
    }

    @Transactional
    public int deleteUnrecordedGames() {
        List<Long> ids = gameRepository.findIdsWithoutAnyRecordData();
        if (ids == null || ids.isEmpty()) return 0;
        for (Long id : ids) {
            gameRepository.deleteById(id);
        }
        return ids.size();
    }
}
