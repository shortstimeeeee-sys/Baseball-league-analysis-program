package com.baseball.application.game;

import com.baseball.application.game.dto.GameRecordImportDto;
import com.baseball.domain.game.Game.GameStatus;
import com.baseball.domain.game.SubstitutionKind;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 중계/스코어 시트 형식의 텍스트를 파싱해 GameRecordImportDto로 변환합니다.
 * 예: "1회말 한화 공격", "문현빈 선수 페이지", "문현빈 : 중견수 플라이 아웃", "152km/h직구", "볼카운트1 - 0" 등
 */
@Slf4j
@Component
public class GameRecordTextParser {

    /** 웹 복사 시 전각 숫자·공백(NFKC로 보정) 및 "9 회초" 등 공백 변형 허용 */
    private static final Pattern INNING_HEADER = Pattern.compile("(\\d+)\\s*회\\s*(초|말)\\s+(.+?)\\s+공격");
    private static final Pattern BATTER_PAGE = Pattern.compile("(.+?)\\s+선수\\s+페이지");
    /** "문현빈3번타자타율 0.333" 또는 "문현빈 3번타자타율" 형태 */
    private static final Pattern BATTER_ORDER = Pattern.compile("(.+?)(\\d)번타자타율.*");
    private static final Pattern RESULT_LINE = Pattern.compile("(.+?)\\s*:\\s*(.+)"); // "문현빈 : 중견수 플라이 아웃"
    private static final Pattern PITCH_RESULT = Pattern.compile("(\\d)구(.+)");
    private static final Pattern PITCH_SPEED_TYPE = Pattern.compile("(\\d+)km/h\\s*(.+)");
    private static final Pattern BALL_STRIKE = Pattern.compile("볼카운트\\s*(\\d)\\s*[-–]\\s*(\\d)");
    /** N루주자 이름 : ... */
    private static final Pattern RUNNER_PLAY = Pattern.compile("([123]루주자\\s+.+?)\\s*:\\s*(.+)");
    /** 교체 유형 감지: "대타 김OO :" */
    private static final Pattern PINCH_HITTER_LINE = Pattern.compile("대타\\s+(.+?)\\s*:");
    /** 교체 유형 감지: "대주자 김OO :" */
    private static final Pattern PINCH_RUNNER_LINE = Pattern.compile("대주자\\s+(.+?)\\s*:");
    /** 교체 유형 감지: "대타: 김OO IN" */
    private static final Pattern PINCH_HITTER_COLON = Pattern.compile("대타\\s*:\\s*(.+?)(?:\\s+IN)?$");
    /** 교체 유형 감지: "대주자: 김OO IN" */
    private static final Pattern PINCH_RUNNER_COLON = Pattern.compile("대주자\\s*:\\s*(.+?)(?:\\s+IN)?$");
    /** 교체 유형 감지: "... : 대타 김OO (으)로 교체" 등 */
    private static final Pattern PINCH_HITTER_CHANGE = Pattern.compile("대타\\s+(.+?)\\s*(?:\\(으\\))?로\\s*교체");
    /** 교체 유형 감지: "... : 대주자 김OO (으)로 교체" 등 */
    private static final Pattern PINCH_RUNNER_CHANGE = Pattern.compile("대주자\\s+(.+?)\\s*(?:\\(으\\))?로\\s*교체");
    /**
     * 승리 투수 줄. OCR·복사 시 "승승리투수", "승리 투수 -" + 다음 줄 이름 등 허용.
     * (?:승)+리 → 승리, 승승리 …
     */
    private static final Pattern WINNING_PITCHER = Pattern.compile("^(?:승)+리\\s*투수\\s*(.*)$");
    /** 패패전투수 등 (?:패)+전 → 패전, 패패전 … */
    private static final Pattern LOSING_PITCHER = Pattern.compile("^(?:패)+전\\s*투수\\s*(.*)$");
    /** 세이브 투수 / 세이브: 이름 */
    private static final Pattern SAVE_PITCHER = Pattern.compile("^세이브\\s*(?:투수)?\\s*(.*)$");
    /** "승" / "패" / "세" 한 줄만 있고 이름은 다음 줄 (요약 복사 형식) */
    private static final Pattern WIN_LABEL_ONLY = Pattern.compile("^승\\s*$");
    private static final Pattern LOSE_LABEL_ONLY = Pattern.compile("^패\\s*$");
    private static final Pattern SAVE_LABEL_ONLY = Pattern.compile("^세\\s*$");

    /** "경기가 종료되었습니다." 등이 있으면 경기 완료로 간주 */
    private static final Pattern GAME_ENDED_LINE = Pattern.compile(".*경기가\\s*종료.*");

    /** 교체 줄: "유격수: 전민재", "투수: 정철원 OUT" 등 */
    private static final Pattern ROLE_COLON_REST = Pattern.compile("^(.+?)\\s*:\\s*(.+)$");

    public GameRecordImportDto parse(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            throw new IllegalArgumentException("입력 텍스트가 비어 있습니다.");
        }

        // 전각 숫자(９)·호환 문자를 ASCII 등으로 정규화해 "９회초"도 9회초로 인식
        String normalized = Normalizer.normalize(rawText, Normalizer.Form.NFKC);
        List<String> lineList = normalized.lines().map(String::trim).filter(s -> !s.isEmpty()).collect(java.util.stream.Collectors.toList());
        // 전체 Collections.reverse는 타자 블록을 깨뜨림(선수 페이지 직 다음 줄이 이닝 헤더로 인식되어 내용이 비는 현상).
        // 원문 순서 그대로 파싱한 뒤, 아래에서 반 이닝 단위로 시간순을 맞춘다.
        String[] lines = lineList.toArray(String[]::new);
        String awayTeamName = null;
        String homeTeamName = null;
        String currentAwayPitcher = null;
        String currentHomePitcher = null;
        int currentInning = 1;
        boolean currentIsTop = true;

        List<GameRecordImportDto.PlateAppearanceRow> plateAppearances = new ArrayList<>();
        List<GameRecordImportDto.PitcherSubstitutionRow> pitcherSubstitutions = new ArrayList<>();
        Map<String, String> pendingBatterSubstitutionTypeByName = new HashMap<>();
        Map<String, String> teamFromInning = new LinkedHashMap<>();
        Set<String> halfInningsWithHeader = new HashSet<>();

        int sequenceOrder = 0;
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            capturePendingBatterSubstitutionType(line, pendingBatterSubstitutionTypeByName);

            Matcher inningMatcher = INNING_HEADER.matcher(line);
            if (inningMatcher.matches()) {
                currentInning = Integer.parseInt(inningMatcher.group(1));
                currentIsTop = "초".equals(inningMatcher.group(2));
                halfInningsWithHeader.add(currentInning + "_" + currentIsTop);
                String team = inningMatcher.group(3).trim();
                teamFromInning.put(currentInning + (currentIsTop ? "초" : "말"), team);
                if (currentIsTop && awayTeamName == null) awayTeamName = team;
                if (!currentIsTop && homeTeamName == null) homeTeamName = team;
                i++;
                continue;
            }

            Matcher pageMatcher = BATTER_PAGE.matcher(line);
            if (pageMatcher.matches()) {
                String batterName = pageMatcher.group(1).trim();
                Integer batterOrder = null;
                String resultText = null;
                List<String> runnerPlays = new ArrayList<>();
                List<GameRecordImportDto.PitchRow> pitches = new ArrayList<>();
                String pitcherName = currentIsTop ? (currentHomePitcher != null ? currentHomePitcher : "") : (currentAwayPitcher != null ? currentAwayPitcher : "");

                i++;
                while (i < lines.length) {
                    String l = lines[i];
                    capturePendingBatterSubstitutionType(l, pendingBatterSubstitutionTypeByName);
                    if (BATTER_PAGE.matcher(l).matches() || INNING_HEADER.matcher(l).matches()) {
                        break;
                    }

                    // 타자 블록 안: "교체" 다음 유격수·투수 등 모든 OUT/IN 교체 (여러 번 연속 가능)
                    if (l.trim().equals("교체") || l.trim().endsWith("교체")) {
                        final int subInning = currentInning;
                        final boolean subIsTop = currentIsTop;
                        int j = i + 1;
                        int subOrder = 0;
                        while (j < lines.length) {
                            String peek = lines[j].trim();
                            if (BATTER_PAGE.matcher(peek).matches() || INNING_HEADER.matcher(peek).matches()) {
                                break;
                            }
                            if (peek.isEmpty()) {
                                j++;
                                continue;
                            }
                            if (peek.equals("교체") || peek.endsWith("교체")) {
                                j++;
                                continue;
                            }
                            ParsedRoleOutIn one = parseOneRoleOutIn(lines, j);
                            if (one == null) {
                                break;
                            }
                            int bf = one.kind() == SubstitutionKind.PITCHER
                                    ? ((subInning - 1) >= 1
                                    ? (int) plateAppearances.stream()
                                    .filter(pa -> pa.getInning() == subInning - 1 && pa.isTop() == subIsTop)
                                    .count() : 0)
                                    : 0;
                            if (one.kind() == SubstitutionKind.RUNNER) {
                                pendingBatterSubstitutionTypeByName.put(one.inName().trim(), "PINCH_RUNNER");
                            }
                            pitcherSubstitutions.add(GameRecordImportDto.PitcherSubstitutionRow.builder()
                                    .inning(subInning)
                                    .isTop(subIsTop)
                                    .kind(one.kind())
                                    .positionLabel(one.positionLabel())
                                    .displayOrder(subOrder++)
                                    .pitcherOutName(one.outName())
                                    .pitcherInName(one.inName())
                                    .battersFaced(bf)
                                    .afterPaSequenceOrder(sequenceOrder + 1)
                                    .build());
                            if (one.kind() == SubstitutionKind.PITCHER) {
                                if (subIsTop) {
                                    currentHomePitcher = one.inName();
                                } else {
                                    currentAwayPitcher = one.inName();
                                }
                            }
                            j = one.nextIndex();
                        }
                        i = j - 1;
                        i++;
                        continue;
                    }

                    Matcher orderMatcher = BATTER_ORDER.matcher(l);
                    if (orderMatcher.matches() && orderMatcher.group(1).trim().equals(batterName)) {
                        batterOrder = Integer.parseInt(orderMatcher.group(2));
                    }

                    Matcher resultMatcher = RESULT_LINE.matcher(l);
                    if (resultMatcher.matches() && resultMatcher.group(1).trim().equals(batterName)) {
                        resultText = resultMatcher.group(2).trim();
                    }

                    if (l.startsWith("투수:") && !l.contains("OUT") && !l.contains("IN")) {
                        String p = l.replaceFirst("^투수:\\s*", "").trim();
                        if (p.contains(" ")) p = p.substring(0, p.indexOf(" ")).trim();
                        if (!p.isBlank()) {
                            pitcherName = p;
                            if (currentIsTop) currentHomePitcher = p;
                            else currentAwayPitcher = p;
                        }
                    }

                    if (RUNNER_PLAY.matcher(l).matches()) {
                        runnerPlays.add(l.trim());
                    }

                    parsePitchLine(l, pitches);
                    i++;
                }

                sequenceOrder++;
                GameRecordImportDto.PlateAppearanceRow row = GameRecordImportDto.PlateAppearanceRow.builder()
                        .inning(currentInning)
                        .isTop(currentIsTop)
                        .sequenceOrder(sequenceOrder)
                        .batterName(batterName)
                        .pitcherName(pitcherName != null ? pitcherName : "")
                        .batterOrder(batterOrder)
                        .batterSubstitutionType(popSubstitutionTypeForBatter(batterName, pendingBatterSubstitutionTypeByName))
                        .resultText(resultText)
                        .runnerPlays(runnerPlays)
                        .pitches(normalizePitchOrder(pitches))
                        .build();
                plateAppearances.add(row);
                continue;
            }

            if (line.trim().equals("교체") || line.trim().endsWith("교체")) {
                final int subInning = currentInning;
                final boolean subIsTop = currentIsTop;
                int j = i + 1;
                int subOrder = 0;
                boolean any = false;
                while (j < lines.length) {
                    String peek = lines[j].trim();
                    if (BATTER_PAGE.matcher(peek).matches() || INNING_HEADER.matcher(peek).matches()) {
                        break;
                    }
                    if (peek.isEmpty()) {
                        j++;
                        continue;
                    }
                    if (peek.equals("교체") || peek.endsWith("교체")) {
                        j++;
                        continue;
                    }
                    ParsedRoleOutIn one = parseOneRoleOutIn(lines, j);
                    if (one == null) {
                        break;
                    }
                    any = true;
                    int pInn = subInning - 1;
                    final boolean pTop = subIsTop;
                    int battersFaced = one.kind() == SubstitutionKind.PITCHER && pInn >= 1
                            ? (int) plateAppearances.stream()
                            .filter(pa -> pa.getInning() == pInn && pa.isTop() == pTop)
                            .count() : 0;
                    pitcherSubstitutions.add(GameRecordImportDto.PitcherSubstitutionRow.builder()
                            .inning(subInning)
                            .isTop(subIsTop)
                            .kind(one.kind())
                            .positionLabel(one.positionLabel())
                            .displayOrder(subOrder++)
                            .pitcherOutName(one.outName())
                            .pitcherInName(one.inName())
                            .battersFaced(battersFaced)
                            .afterPaSequenceOrder(0)
                            .build());
                    if (one.kind() == SubstitutionKind.PITCHER) {
                        if (subIsTop) {
                            currentHomePitcher = one.inName();
                        } else {
                            currentAwayPitcher = one.inName();
                        }
                    }
                    j = one.nextIndex();
                }
                i = any ? j : i + 1;
                continue;
            }
            i++;
        }

        boolean newestFirstFeed = detectNewestFirstInningFeed(lines)
                || looksLikeReverseOrderedFeed(Arrays.asList(lines));
        reorderPlateAppearancesChronologically(plateAppearances, pitcherSubstitutions, newestFirstFeed);

        if (awayTeamName == null) awayTeamName = "원정";
        if (homeTeamName == null) homeTeamName = "홈";

        boolean gameEndedDeclaration = false;
        for (String l : lines) {
            if (GAME_ENDED_LINE.matcher(l.trim()).matches()) {
                gameEndedDeclaration = true;
                break;
            }
        }

        String winningPitcherName = null;
        String losingPitcherName = null;
        String savePitcherName = null;
        for (int li = 0; li < lines.length; li++) {
            String t = lines[li].trim();
            if (WIN_LABEL_ONLY.matcher(t).matches()) {
                PitcherNameResult r = extractPitcherNameWithOptionalNextLine(lines, li, "");
                if (r.name() != null && !r.name().isBlank()) {
                    winningPitcherName = r.name();
                }
                li += r.linesToSkipAfterLabel();
                continue;
            }
            if (LOSE_LABEL_ONLY.matcher(t).matches()) {
                PitcherNameResult r = extractPitcherNameWithOptionalNextLine(lines, li, "");
                if (r.name() != null && !r.name().isBlank()) {
                    losingPitcherName = r.name();
                }
                li += r.linesToSkipAfterLabel();
                continue;
            }
            if (SAVE_LABEL_ONLY.matcher(t).matches()) {
                PitcherNameResult r = extractPitcherNameWithOptionalNextLine(lines, li, "");
                if (r.name() != null && !r.name().isBlank()) {
                    savePitcherName = r.name();
                }
                li += r.linesToSkipAfterLabel();
                continue;
            }
            Matcher w = WINNING_PITCHER.matcher(t);
            if (w.matches()) {
                PitcherNameResult r = extractPitcherNameWithOptionalNextLine(lines, li, w.group(1));
                if (r.name() != null && !r.name().isBlank()) {
                    winningPitcherName = r.name();
                }
                li += r.linesToSkipAfterLabel();
                continue;
            }
            Matcher lo = LOSING_PITCHER.matcher(t);
            if (lo.matches()) {
                PitcherNameResult r = extractPitcherNameWithOptionalNextLine(lines, li, lo.group(1));
                if (r.name() != null && !r.name().isBlank()) {
                    losingPitcherName = r.name();
                }
                li += r.linesToSkipAfterLabel();
                continue;
            }
            Matcher s = SAVE_PITCHER.matcher(t);
            if (s.matches()) {
                PitcherNameResult r = extractPitcherNameWithOptionalNextLine(lines, li, s.group(1));
                if (r.name() != null && !r.name().isBlank()) {
                    savePitcherName = r.name();
                }
                li += r.linesToSkipAfterLabel();
            }
        }

        boolean strippedNineBottom = NineInningEndRule.stripNineBottomIfHomeLeadsAfterNineTop(
                plateAppearances, pitcherSubstitutions, halfInningsWithHeader);

        List<GameRecordImportDto.InningScoreRow> inningScores = buildInningScoresFromPa(plateAppearances);
        int totalAway = inningScores.stream().mapToInt(GameRecordImportDto.InningScoreRow::getAwayRunsInInning).sum();
        int totalHome = inningScores.stream().mapToInt(GameRecordImportDto.InningScoreRow::getHomeRunsInInning).sum();
        int[] box = countBoxScoreFromPa(plateAppearances);

        GameRecordImportDto.GameInfo gameInfo = GameRecordImportDto.GameInfo.builder()
                .leagueName("KBO")
                .homeTeamName(homeTeamName)
                .awayTeamName(awayTeamName)
                .gameDateTime(LocalDateTime.now())
                .venue("")
                .homeScore(totalHome)
                .awayScore(totalAway)
                .homeHits(box[1])
                .awayHits(box[0])
                .homeErrors(box[3])
                .awayErrors(box[2])
                .homeWalks(box[5])
                .awayWalks(box[4])
                .status(gameEndedDeclaration || strippedNineBottom ? GameStatus.COMPLETED : GameStatus.IN_PROGRESS)
                .winningPitcherName(winningPitcherName)
                .losingPitcherName(losingPitcherName)
                .savePitcherName(savePitcherName)
                .build();

        return GameRecordImportDto.builder()
                .gameInfo(gameInfo)
                .inningScores(inningScores)
                .plateAppearances(plateAppearances)
                .pitcherSubstitutions(pitcherSubstitutions)
                .halfInningsWithHeader(halfInningsWithHeader)
                .build();
    }

    /** 한 번의 교체: "포지션: 아웃선수" / OUT / "포지션: 인선수" / IN (OUT·IN은 같은 줄에 붙어도 됨) */
    private record ParsedRoleOutIn(
            SubstitutionKind kind,
            String positionLabel,
            String outName,
            String inName,
            int nextIndex
    ) {
    }

    /**
     * "유격수: 전민재" … OUT … "유격수: 이서준" … IN 또는 "투수: 정철원 OUT" … "투수: 박정민 IN" 형식 파싱.
     */
    private ParsedRoleOutIn parseOneRoleOutIn(String[] lines, int start) {
        int j = start;
        while (j < lines.length && lines[j].trim().isEmpty()) {
            j++;
        }
        if (j >= lines.length) {
            return null;
        }
        String l1 = lines[j].trim();
        if (BATTER_PAGE.matcher(l1).matches() || INNING_HEADER.matcher(l1).matches()) {
            return null;
        }
        if (l1.equals("교체") || l1.endsWith("교체")) {
            return parseOneRoleOutIn(lines, j + 1);
        }
        Matcher m1 = ROLE_COLON_REST.matcher(l1);
        if (!m1.matches()) {
            return null;
        }
        String role1 = m1.group(1).trim();
        String rest1 = m1.group(2).trim();
        // 아래에서 role2를 읽은 뒤 kind 확정 (1루주자→대주자 = 주자 교체)

        boolean inlineOut = rest1.endsWith("OUT");
        String outPlayer = inlineOut ? stripTrailingOutIn(rest1, "OUT") : rest1;
        j++;
        if (!inlineOut) {
            while (j < lines.length && lines[j].trim().isEmpty()) {
                j++;
            }
            if (j < lines.length && "OUT".equals(lines[j].trim())) {
                j++;
            }
        }

        while (j < lines.length && lines[j].trim().isEmpty()) {
            j++;
        }
        if (j >= lines.length) {
            return null;
        }
        String l2 = lines[j].trim();
        Matcher m2 = ROLE_COLON_REST.matcher(l2);
        if (!m2.matches()) {
            return null;
        }
        String role2 = m2.group(1).trim();
        String rest2 = m2.group(2).trim();
        boolean inlineIn = rest2.endsWith("IN");
        String inPlayer = inlineIn ? stripTrailingOutIn(rest2, "IN") : rest2;
        j++;
        if (!inlineIn) {
            while (j < lines.length && lines[j].trim().isEmpty()) {
                j++;
            }
            if (j < lines.length && "IN".equals(lines[j].trim())) {
                j++;
            }
        }

        if (outPlayer.isBlank() || inPlayer.isBlank()) {
            return null;
        }
        SubstitutionKind kind;
        String positionLabel;
        if (role1.contains("투수")) {
            kind = SubstitutionKind.PITCHER;
            positionLabel = null;
        } else if (role1.matches("[123]루주자") && role2.contains("대주자")) {
            kind = SubstitutionKind.RUNNER;
            positionLabel = role1;
        } else {
            kind = SubstitutionKind.FIELD;
            positionLabel = role1;
        }
        return new ParsedRoleOutIn(kind, positionLabel, outPlayer, inPlayer, j);
    }

    private static String stripTrailingOutIn(String rest, String token) {
        return rest.trim().replaceFirst("\\s*" + token + "\\s*$", "").trim();
    }

    private record PitcherNameResult(String name, int linesToSkipAfterLabel) {}

    /**
     * 라벨 뒤 접미사(콜론·이름 또는 "-")만 있거나 비어 있으면 다음 줄을 투수 이름으로 사용.
     */
    private PitcherNameResult extractPitcherNameWithOptionalNextLine(String[] lines, int idx, String group1) {
        String g = normalizePitcherSuffix(group1);
        if (g.isEmpty() || isDashOnly(g)) {
            if (idx + 1 < lines.length && isLikelyPitcherNameLine(lines[idx + 1])) {
                return new PitcherNameResult(lines[idx + 1].trim(), 1);
            }
            return new PitcherNameResult(null, 0);
        }
        return new PitcherNameResult(g, 0);
    }

    private static String normalizePitcherSuffix(String rest) {
        if (rest == null) {
            return "";
        }
        String s = rest.trim();
        s = s.replaceFirst("^[:：]\\s*", "").trim();
        return s;
    }

    private static boolean isDashOnly(String g) {
        if (g == null || g.isEmpty()) {
            return true;
        }
        String t = g.trim();
        return t.equals("-") || t.equals("–") || t.equals("—") || t.equals("－");
    }

    /** 다음 줄이 이닝 헤더·타자 페이지 등이 아니면 투수 이름 후보로 인정 */
    private boolean isLikelyPitcherNameLine(String line) {
        if (line == null || line.isBlank()) {
            return false;
        }
        String t = line.trim();
        if (INNING_HEADER.matcher(t).matches()) {
            return false;
        }
        if (BATTER_PAGE.matcher(t).matches()) {
            return false;
        }
        if (WINNING_PITCHER.matcher(t).matches()) {
            return false;
        }
        if (LOSING_PITCHER.matcher(t).matches()) {
            return false;
        }
        if (SAVE_PITCHER.matcher(t).matches()) {
            return false;
        }
        if (WIN_LABEL_ONLY.matcher(t).matches() || LOSE_LABEL_ONLY.matcher(t).matches() || SAVE_LABEL_ONLY.matcher(t).matches()) {
            return false;
        }
        if (GAME_ENDED_LINE.matcher(t).matches()) {
            return false;
        }
        if (t.contains("교체")) {
            return false;
        }
        if (t.contains("투수:") && (t.contains("OUT") || t.contains("IN"))) {
            return false;
        }
        if (t.contains("선수 페이지")) {
            return false;
        }
        if (t.contains("km/h")) {
            return false;
        }
        if (t.contains("볼카운트")) {
            return false;
        }
        if (t.contains("투구")) {
            return false;
        }
        return t.length() <= 80;
    }

    private void parsePitchLine(String line, List<GameRecordImportDto.PitchRow> pitches) {
        Matcher pr = PITCH_RESULT.matcher(line);
        if (pr.matches()) {
            int pitchNum = Integer.parseInt(pr.group(1));
            String result = pr.group(2).trim();
            pitches.add(GameRecordImportDto.PitchRow.builder()
                    .pitchOrder(pitchNum)
                    .resultText(result)
                    .build());
            return;
        }
        Matcher ps = PITCH_SPEED_TYPE.matcher(line);
        if (ps.matches() && !pitches.isEmpty()) {
            int speed = Integer.parseInt(ps.group(1));
            String type = ps.group(2).trim();
            GameRecordImportDto.PitchRow last = pitches.get(pitches.size() - 1);
            pitches.set(pitches.size() - 1, GameRecordImportDto.PitchRow.builder()
                    .pitchOrder(last.getPitchOrder())
                    .ballCountAfter(last.getBallCountAfter())
                    .strikeCountAfter(last.getStrikeCountAfter())
                    .pitchType(type)
                    .speedKmh(speed)
                    .resultText(last.getResultText())
                    .build());
            return;
        }
        Matcher bs = BALL_STRIKE.matcher(line);
        if (bs.matches() && !pitches.isEmpty()) {
            int balls = Integer.parseInt(bs.group(1));
            int strikes = Integer.parseInt(bs.group(2));
            GameRecordImportDto.PitchRow last = pitches.get(pitches.size() - 1);
            pitches.set(pitches.size() - 1, GameRecordImportDto.PitchRow.builder()
                    .pitchOrder(last.getPitchOrder())
                    .ballCountAfter(balls)
                    .strikeCountAfter(strikes)
                    .pitchType(last.getPitchType())
                    .speedKmh(last.getSpeedKmh())
                    .resultText(last.getResultText())
                    .build());
        }
    }

    private List<GameRecordImportDto.PitchRow> normalizePitchOrder(List<GameRecordImportDto.PitchRow> pitches) {
        if (pitches.isEmpty()) return pitches;
        pitches.sort(Comparator.comparingInt(GameRecordImportDto.PitchRow::getPitchOrder));
        List<GameRecordImportDto.PitchRow> result = new ArrayList<>();
        for (int j = 0; j < pitches.size(); j++) {
            GameRecordImportDto.PitchRow p = pitches.get(j);
            result.add(GameRecordImportDto.PitchRow.builder()
                    .pitchOrder(j + 1)
                    .ballCountAfter(p.getBallCountAfter())
                    .strikeCountAfter(p.getStrikeCountAfter())
                    .pitchType(p.getPitchType())
                    .speedKmh(p.getSpeedKmh())
                    .resultText(p.getResultText())
                    .build());
        }
        return result;
    }

    private List<GameRecordImportDto.InningScoreRow> buildInningScoresFromPa(List<GameRecordImportDto.PlateAppearanceRow> pas) {
        Map<Integer, int[]> runsByInning = new TreeMap<>();
        for (int inn = 1; inn <= 9; inn++) {
            runsByInning.put(inn, new int[]{0, 0});
        }
        for (GameRecordImportDto.PlateAppearanceRow pa : pas) {
            int inning = pa.getInning();
            if (inning < 1 || inning > 9) continue;
            int[] r = runsByInning.get(inning);
            int runs = count홈인(pa.getResultText(), pa.getRunnerPlays());
            if (pa.isTop()) {
                r[0] += runs;
            } else {
                r[1] += runs;
            }
        }
        List<GameRecordImportDto.InningScoreRow> rows = new ArrayList<>();
        for (Map.Entry<Integer, int[]> e : runsByInning.entrySet()) {
            int[] r = e.getValue();
            rows.add(GameRecordImportDto.InningScoreRow.builder()
                    .inning(e.getKey())
                    .homeRunsInInning(r[1])
                    .awayRunsInInning(r[0])
                    .build());
        }
        return rows;
    }

    /** 타석 결과·주자 플레이에서 '홈인' 개수만큼 득점으로 집계 */
    private int count홈인(String resultText, List<String> runnerPlays) {
        StringBuilder sb = new StringBuilder();
        if (resultText != null && !resultText.isEmpty()) sb.append(resultText);
        if (runnerPlays != null) {
            for (String play : runnerPlays) {
                if (play != null && !play.isEmpty()) sb.append(' ').append(play);
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

    /** 타석 목록에서 안타(H)·실책(E)·사사구(B) 집계. 반환: [awayHits, homeHits, awayErrors, homeErrors, awayWalks, homeWalks] */
    private int[] countBoxScoreFromPa(List<GameRecordImportDto.PlateAppearanceRow> pas) {
        int awayHits = 0, homeHits = 0, awayErrors = 0, homeErrors = 0, awayWalks = 0, homeWalks = 0;
        for (GameRecordImportDto.PlateAppearanceRow pa : pas) {
            String result = pa.getResultText() != null ? pa.getResultText() : "";
            boolean isHit = result.contains("1루타") || result.contains("2루타") || result.contains("3루타")
                    || result.contains("홈런") || result.contains("내야안타") || result.contains("번트안타");
            if (isHit) {
                if (pa.isTop()) awayHits++;
                else homeHits++;
            }
            if (isWalkLikeResult(result)) {
                if (pa.isTop()) awayWalks++;
                else homeWalks++;
            }
            int errorsInPa = countErrorsInLine(result);
            if (pa.getRunnerPlays() != null) {
                for (String play : pa.getRunnerPlays()) {
                    errorsInPa = Math.max(errorsInPa, countErrorsInLine(play));
                }
            }
            if (errorsInPa > 0) {
                if (pa.isTop()) homeErrors += 1;
                else awayErrors += 1;
            }
        }
        return new int[]{awayHits, homeHits, awayErrors, homeErrors, awayWalks, homeWalks};
    }

    private int countOccurrences(String text, String sub) {
        if (text == null || sub == null || sub.isEmpty()) return 0;
        int count = 0, idx = 0;
        while ((idx = text.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }

    /** 실책 문구를 폭넓게 인정. 단, '무실책'은 제외 */
    private boolean hasRealError(String text) {
        if (text == null || text.contains("무실책")) return false;
        return text.contains("실책");
    }

    /** 한 줄(결과/주자플레이)에서 실책 이벤트 개수. 현재 라인당 최대 1건으로 계산 */
    private int countErrorsInLine(String text) {
        return hasRealError(text) ? 1 : 0;
    }

    /** B 집계용: 볼넷 + 사구(몸에 맞는 볼/데드볼)를 사사구로 합산 */
    private boolean isWalkLikeResult(String text) {
        if (text == null || text.isBlank()) return false;
        return text.contains("볼넷")
                || text.contains("고의4구")
                || text.contains("고의 4구")
                || text.contains("자동 고의4구")
                || text.contains("자동고의4구")
                || text.contains("몸에 맞는 볼")
                || text.contains("몸에 맞는볼")
                || text.contains("사구")
                || text.contains("데드볼");
    }

    private void capturePendingBatterSubstitutionType(String line, Map<String, String> pendingByName) {
        if (line == null || line.isBlank()) return;
        String text = line.trim();
        Matcher pinchHitter = PINCH_HITTER_LINE.matcher(text);
        if (pinchHitter.find()) {
            String name = pinchHitter.group(1) != null ? pinchHitter.group(1).trim() : "";
            if (!name.isBlank()) pendingByName.put(name, "PINCH_HITTER");
            return;
        }
        Matcher pinchRunner = PINCH_RUNNER_LINE.matcher(text);
        if (pinchRunner.find()) {
            String name = pinchRunner.group(1) != null ? pinchRunner.group(1).trim() : "";
            if (!name.isBlank()) pendingByName.put(name, "PINCH_RUNNER");
            return;
        }
        Matcher pinchHitterColon = PINCH_HITTER_COLON.matcher(text);
        if (pinchHitterColon.find()) {
            String name = pinchHitterColon.group(1) != null ? pinchHitterColon.group(1).trim() : "";
            if (!name.isBlank()) pendingByName.put(name, "PINCH_HITTER");
            return;
        }
        Matcher pinchRunnerColon = PINCH_RUNNER_COLON.matcher(text);
        if (pinchRunnerColon.find()) {
            String name = pinchRunnerColon.group(1) != null ? pinchRunnerColon.group(1).trim() : "";
            if (!name.isBlank()) pendingByName.put(name, "PINCH_RUNNER");
            return;
        }
        Matcher pinchHitterChange = PINCH_HITTER_CHANGE.matcher(text);
        if (pinchHitterChange.find()) {
            String name = pinchHitterChange.group(1) != null ? pinchHitterChange.group(1).trim() : "";
            if (!name.isBlank()) pendingByName.put(name, "PINCH_HITTER");
            return;
        }
        Matcher pinchRunnerChange = PINCH_RUNNER_CHANGE.matcher(text);
        if (pinchRunnerChange.find()) {
            String name = pinchRunnerChange.group(1) != null ? pinchRunnerChange.group(1).trim() : "";
            if (!name.isBlank()) pendingByName.put(name, "PINCH_RUNNER");
        }
    }

    private String popSubstitutionTypeForBatter(String batterName, Map<String, String> pendingByName) {
        if (batterName == null || batterName.isBlank()) return null;
        return pendingByName.remove(batterName.trim());
    }

    /** 경기 시간순 반 이닝 순번 (오름차순 = 초→말→다음 이닝) */
    private static int chronHalfOrder(int inning, boolean isTop) {
        return inning * 2 + (isTop ? 0 : 1);
    }

    /**
     * 파일에서 가장 먼저 나온 이닝 헤더가, 가장 나중에 나온 헤더보다 "시간상 뒤"이면 최신→과거 순 붙여넣기로 본다.
     */
    private static boolean detectNewestFirstInningFeed(String[] lines) {
        int firstIdx = Integer.MAX_VALUE;
        int lastIdx = -1;
        Integer firstOrder = null;
        Integer lastOrder = null;
        for (int i = 0; i < lines.length; i++) {
            Matcher m = INNING_HEADER.matcher(lines[i]);
            if (m.matches()) {
                int inn = Integer.parseInt(m.group(1));
                boolean top = "초".equals(m.group(2));
                int ord = chronHalfOrder(inn, top);
                if (i < firstIdx) {
                    firstIdx = i;
                    firstOrder = ord;
                }
                if (i > lastIdx) {
                    lastIdx = i;
                    lastOrder = ord;
                }
            }
        }
        if (firstOrder == null || lastOrder == null) {
            return false;
        }
        return firstOrder > lastOrder;
    }

    /**
     * 타석·투수교체를 반 이닝 단위로 모은 뒤, 경기 시간순(이닝↑, 초→말)으로 정렬한다.
     * 최신순 피드면 각 반 이닝 안의 타석 순서를 뒤집는다.
     */
    private static void reorderPlateAppearancesChronologically(
            List<GameRecordImportDto.PlateAppearanceRow> plateAppearances,
            List<GameRecordImportDto.PitcherSubstitutionRow> pitcherSubstitutions,
            boolean newestFirstFeed) {
        if (plateAppearances.isEmpty()) {
            return;
        }
        Map<String, List<GameRecordImportDto.PlateAppearanceRow>> byHalf = new LinkedHashMap<>();
        for (GameRecordImportDto.PlateAppearanceRow r : plateAppearances) {
            String key = r.getInning() + "_" + r.isTop();
            byHalf.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
        }
        List<String> keys = new ArrayList<>(byHalf.keySet());
        keys.sort(HALF_GROUP_KEY_ORDER);
        Map<String, Boolean> reverseWithinHalf = new HashMap<>();
        List<GameRecordImportDto.PlateAppearanceRow> ordered = new ArrayList<>();
        for (String k : keys) {
            List<GameRecordImportDto.PlateAppearanceRow> chunk = new ArrayList<>(byHalf.get(k));
            // 타순만 보고 뒤집지 않음(5번→1번 등 한 사이클은 정상). 헤더·피드 감지로만 역순 적용.
            reverseWithinHalf.put(k, newestFirstFeed);
            if (newestFirstFeed) {
                Collections.reverse(chunk);
            }
            ordered.addAll(chunk);
        }
        Map<Integer, Integer> oldSeqToNew = new HashMap<>();
        for (int i = 0; i < ordered.size(); i++) {
            GameRecordImportDto.PlateAppearanceRow r = ordered.get(i);
            oldSeqToNew.put(r.getSequenceOrder(), i + 1);
            r.setSequenceOrder(i + 1);
        }
        plateAppearances.clear();
        plateAppearances.addAll(ordered);

        if (pitcherSubstitutions != null && !pitcherSubstitutions.isEmpty()) {
            Map<String, List<GameRecordImportDto.PitcherSubstitutionRow>> subBy = new LinkedHashMap<>();
            for (GameRecordImportDto.PitcherSubstitutionRow s : pitcherSubstitutions) {
                String key = s.getInning() + "_" + s.isTop();
                subBy.computeIfAbsent(key, k -> new ArrayList<>()).add(s);
            }
            List<String> subKeys = new ArrayList<>(subBy.keySet());
            subKeys.sort(HALF_GROUP_KEY_ORDER);
            List<GameRecordImportDto.PitcherSubstitutionRow> newSubs = new ArrayList<>();
            for (String k : subKeys) {
                List<GameRecordImportDto.PitcherSubstitutionRow> ch = new ArrayList<>(subBy.get(k));
                ch.sort(Comparator
                        .comparingInt((GameRecordImportDto.PitcherSubstitutionRow s) ->
                                s.getAfterPaSequenceOrder() != null ? s.getAfterPaSequenceOrder() : 0)
                        .thenComparingInt(GameRecordImportDto.PitcherSubstitutionRow::getDisplayOrder));
                if (Boolean.TRUE.equals(reverseWithinHalf.get(k))) {
                    Collections.reverse(ch);
                }
                newSubs.addAll(ch);
            }
            pitcherSubstitutions.clear();
            pitcherSubstitutions.addAll(newSubs);
            for (GameRecordImportDto.PitcherSubstitutionRow ps : pitcherSubstitutions) {
                Integer ao = ps.getAfterPaSequenceOrder();
                if (ao != null && ao > 0) {
                    Integer mapped = oldSeqToNew.get(ao);
                    if (mapped != null) {
                        ps.setAfterPaSequenceOrder(mapped);
                    }
                }
            }
        }
    }

    /** "3_true" → 이닝 오름차순, 같은 이닝은 초(true) 먼저 */
    private static final Comparator<String> HALF_GROUP_KEY_ORDER = (ka, kb) -> {
        int ua = ka.lastIndexOf('_');
        int ub = kb.lastIndexOf('_');
        int ia = Integer.parseInt(ka.substring(0, ua));
        int ib = Integer.parseInt(kb.substring(0, ub));
        if (ia != ib) {
            return Integer.compare(ia, ib);
        }
        boolean ta = Boolean.parseBoolean(ka.substring(ua + 1));
        boolean tb = Boolean.parseBoolean(kb.substring(ub + 1));
        return Boolean.compare(tb, ta);
    };

    /**
     * 일부 중계 페이지는 최신 이벤트가 위에 와서(역순) 붙여넣기되는 경우가 있다.
     * 이때 투수 교체(IN/OUT) 시점이 밀려 잘못 귀속될 수 있어, 투구 번호 흐름으로 역순 여부를 추정한다.
     */
    private boolean looksLikeReverseOrderedFeed(List<String> lines) {
        if (lines == null || lines.size() < 20) return false;
        // 1) 이닝 헤더 흐름을 최우선으로 판단 (가장 신뢰도 높음)
        int inningIncreases = 0;
        int inningDecreases = 0;
        int sameInningHalfReversed = 0;
        Integer prevInning = null;
        Integer prevHalf = null; // 초=0, 말=1
        for (String line : lines) {
            if (line == null || line.isBlank()) continue;
            Matcher m = INNING_HEADER.matcher(line);
            if (!m.matches()) continue;
            Integer now;
            try {
                now = Integer.parseInt(m.group(1));
            } catch (NumberFormatException ex) {
                continue;
            }
            int half = "초".equals(m.group(2)) ? 0 : 1;
            if (prevInning != null) {
                if (now > prevInning) inningIncreases++;
                else if (now < prevInning) inningDecreases++;
                else if (prevHalf != null && prevHalf == 1 && half == 0) {
                    // 같은 이닝에서 "말 -> 초"로 나오면 최신->과거 순서일 가능성이 매우 높음
                    sameInningHalfReversed++;
                }
            }
            prevInning = now;
            prevHalf = half;
        }
        if (sameInningHalfReversed >= 1) {
            return true;
        }
        if (inningDecreases >= 2 && inningDecreases > inningIncreases) {
            return true;
        }
        if (inningIncreases > 0 || inningDecreases > 0) {
            // 이닝 흐름 증거가 있는데 역순 우세가 아니면 뒤집지 않음
            return false;
        }

        // 2) 이닝 헤더가 거의 없을 때만 투구 번호 흐름으로 보조 판단
        int increases = 0;
        int decreases = 0;
        int prevPitchNo = -1;
        for (String line : lines) {
            if (line == null || line.isBlank()) continue;
            if (INNING_HEADER.matcher(line).matches() || BATTER_PAGE.matcher(line).matches()) {
                prevPitchNo = -1;
                continue;
            }
            Matcher m = PITCH_RESULT.matcher(line);
            if (!m.matches()) continue;
            int now;
            try {
                now = Integer.parseInt(m.group(1));
            } catch (NumberFormatException ex) {
                continue;
            }
            if (prevPitchNo != -1) {
                if (now > prevPitchNo) increases++;
                else if (now < prevPitchNo) decreases++;
            }
            prevPitchNo = now;
        }
        // 보수적 기준: 감소 패턴이 증가 대비 충분히 우세할 때만 역순으로 판단
        return decreases >= 10 && decreases >= (increases * 2);
    }
}
