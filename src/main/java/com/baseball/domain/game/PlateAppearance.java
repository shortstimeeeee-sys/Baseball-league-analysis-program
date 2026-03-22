package com.baseball.domain.game;

import com.baseball.domain.player.Player;
import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 타석 기록 (한 타자의 타석 결과).
 * 1회초/1회말 등 이닝·공격팀, 타자/투수, 결과, 승리확률, 투구 목록을 가집니다.
 */
@Entity
@Table(name = "plate_appearances", indexes = {
        @Index(name = "idx_plate_app_game_inning", columnList = "game_id, inning, is_top")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class PlateAppearance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "game_id", nullable = false)
    private Game game;

    /** 이닝 (1~9) */
    @Column(nullable = false)
    private Integer inning;

    /** true = 초(원정 공격), false = 말(홈 공격) */
    @Column(name = "is_top", nullable = false)
    private Boolean isTop;

    /** 해당 이닝 내 타석 순서 */
    @Column(name = "sequence_order", nullable = false)
    private Integer sequenceOrder;

    @Column(name = "batter_name", nullable = false, length = 100)
    private String batterName;

    @Column(name = "pitcher_name", nullable = false, length = 100)
    private String pitcherName;

    /** 타순 (1~9). 없으면 null */
    private Integer batterOrder;

    /** 타자 구분: true=주전(해당 팀/타순 첫 등장), false=후보/교체 */
    @Column(name = "batter_is_starter")
    private Boolean batterIsStarter;

    /** 타자 교체 유형: 대타/대주자 구분 */
    @Enumerated(EnumType.STRING)
    @Column(name = "batter_substitution_type", length = 20)
    private BatterSubstitutionType batterSubstitutionType;

    /** 투수 구분: true=선발(해당 팀 수비 첫 등장), false=교체 */
    @Column(name = "pitcher_is_starter")
    private Boolean pitcherIsStarter;

    /** 타석 결과 요약 (예: "삼진 아웃", "1루수 땅볼 아웃") */
    @Column(name = "result_text", length = 200)
    private String resultText;

    /** 타석 전 승리 확률 (공격팀 기준, 0~100). null 가능 */
    @Column(name = "win_probability_before")
    private Double winProbabilityBefore;

    /** 타석 후 승리 확률 (공격팀 기준). null 가능 */
    @Column(name = "win_probability_after")
    private Double winProbabilityAfter;

    /** 주자 진루·아웃 등 (한 줄씩 \n 구분). null 가능 */
    @Column(name = "runner_plays_text", length = 2000)
    private String runnerPlaysText;

    /** DB에 등록된 타자 선수 (선택) */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "batter_id")
    private Player batter;

    /** DB에 등록된 투수 선수 (선택) */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pitcher_id")
    private Player pitcher;

    @OneToMany(mappedBy = "plateAppearance", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("pitchOrder")
    @Builder.Default
    private List<Pitch> pitches = new ArrayList<>();

    public void addPitch(Pitch pitch) {
        pitches.add(pitch);
        pitch.setPlateAppearance(this);
    }

    /** 주자 플레이 목록 (화면 표시용, runnerPlaysText를 줄 단위로 분리) */
    public List<String> getRunnerPlaysList() {
        if (runnerPlaysText == null || runnerPlaysText.isBlank()) {
            return Collections.emptyList();
        }
        return Arrays.asList(runnerPlaysText.split("\n"));
    }

    /** 득점(홈인/득점) 발생 타석 여부. 기록 탭 '득점' 필터용 */
    public boolean isRunScoring() {
        if (resultText != null && (resultText.contains("홈인") || resultText.contains("득점"))) {
            return true;
        }
        for (String play : getRunnerPlaysList()) {
            if (play != null && (play.contains("홈인") || play.contains("득점"))) {
                return true;
            }
        }
        return false;
    }

    private static final String OUT_EMPHASIS_HTML = "<strong class=\"out-emphasis\">아웃</strong>";
    private static final String ERROR_EMPHASIS_HTML = "<strong class=\"error-emphasis\">실책</strong>";
    /** 홈인: 부모 .pa-away / .pa-home 에 따라 팀 색 적용 */
    private static final String RUN_EMPHASIS_HTML = "<strong class=\"run-emphasis\">홈인</strong>";

    private static final String OUT_TOKEN = "아웃";

    /**
     * 야구 규칙상 각 회초·회말마다 아웃은 세 개까지이며, 기록 화면에서는 그 반 이닝 안에서
     * {@code 1아웃}→{@code 2아웃}→{@code 3아웃} 순으로 문맥에 맞게 강조한다.
     * <p>
     * 카운트는 <strong>경기 진행 순</strong>(이닝 → 초가 말보다 먼저 → 같은 반 안에서는 {@code sequenceOrder})으로
     * 타석을 돌며 쌓고, 반 이닝이 바뀌면 {@link #resetOutCountIfNewHalf}로 다시 1부터 센다.
     * 화면 카드만 타순 사이클로 재배치된 경우에도 실제 타석 순과 맞추기 위함이다.
     * </p>
     */
    public record EmphasisHtml(Map<Long, String> resultById, Map<Long, List<String>> runnerLinesById) {}

    /** 경기 시간순: 이닝 오름차순, 같은 이닝은 회초(true)가 회말(false)보다 먼저 */
    private static final Comparator<PlateAppearance> CHRONOLOGICAL_GAME_ORDER = Comparator
            .comparing(PlateAppearance::getInning, Comparator.nullsLast(Integer::compareTo))
            .thenComparing(PlateAppearance::getIsTop, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(PlateAppearance::getSequenceOrder, Comparator.nullsLast(Integer::compareTo))
            .thenComparing(PlateAppearance::getId, Comparator.nullsLast(Long::compareTo));

    public static EmphasisHtml buildEmphasisHtml(List<PlateAppearance> displayOrder) {
        Map<Long, String> res = new LinkedHashMap<>();
        Map<Long, List<String>> runners = new LinkedHashMap<>();
        if (displayOrder == null || displayOrder.isEmpty()) {
            return new EmphasisHtml(Map.of(), Map.of());
        }
        List<PlateAppearance> chronological = new ArrayList<>(displayOrder);
        chronological.sort(CHRONOLOGICAL_GAME_ORDER);

        int[] outsInHalf = {0};
        PlateAppearance prev = null;
        for (PlateAppearance pa : chronological) {
            resetOutCountIfNewHalf(pa, prev, outsInHalf);
            if (pa.getId() != null) {
                res.put(pa.getId(), emphasizeResultAndRunnerLine(pa.getResultText(), outsInHalf));
                List<String> lines = pa.getRunnerPlaysList();
                if (lines.isEmpty()) {
                    runners.put(pa.getId(), List.of());
                } else {
                    List<String> built = new ArrayList<>(lines.size());
                    for (String line : lines) {
                        built.add(emphasizeResultAndRunnerLine(line, outsInHalf));
                    }
                    runners.put(pa.getId(), built);
                }
            }
            prev = pa;
        }
        return new EmphasisHtml(res, runners);
    }

    private static void resetOutCountIfNewHalf(PlateAppearance pa, PlateAppearance prev, int[] outsInHalf) {
        if (pa.getInning() == null || pa.getIsTop() == null) {
            return;
        }
        if (prev == null) {
            outsInHalf[0] = 0;
            return;
        }
        if (prev.getInning() == null || prev.getIsTop() == null) {
            return;
        }
        if (!Objects.equals(prev.getInning(), pa.getInning()) || !Objects.equals(prev.getIsTop(), pa.getIsTop())) {
            outsInHalf[0] = 0;
        }
    }

    private static String emphasizeResultAndRunnerLine(String text, int[] outsInHalf) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String s = replaceOutsWithNumberedEmphasis(text, outsInHalf);
        return s.replace("실책", ERROR_EMPHASIS_HTML).replace("홈인", RUN_EMPHASIS_HTML);
    }

    /**
     * "아웃" 글자가 없어도 삼진은 1아웃으로 본다(중계가 "헛스윙 삼진"만 찍는 경우가 많음).
     * 타자는 출루했지만 주자가 포스/터치 등으로 잡힌 줄은 '아웃'이 생략된 경우가 있어
     * {@code 루주자 … 포스/터치/송구/태그} 패턴이면 1~3아웃 카운트에 포함한다.
     * 한 줄에 "포스아웃"+"터치아웃"처럼 '아웃'이 두 번 나와도 실제는 한 번의 아웃이므로 카운트는 1번만 올린다.
     * 표시는 첫 번째 '아웃'은 번호 없이 강조만 하고, 두 번째에만 {@code N아웃}을 붙인다(포스2아웃·터치2아웃 중복 방지).
     */
    private static String replaceOutsWithNumberedEmphasis(String text, int[] outsInHalf) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        if (!text.contains(OUT_TOKEN)) {
            String noToken = replaceStrikeoutWithNumberedEmphasisIfPresent(text, outsInHalf);
            if (!noToken.equals(text)) {
                return noToken;
            }
            return replaceImplicitRunnerPlayOutEmphasisIfPresent(text, outsInHalf);
        }
        outsInHalf[0]++;
        int n = outsInHalf[0];
        String numberedRepl = "<strong class=\"out-emphasis\">" + n + "아웃</strong>";
        List<Integer> positions = new ArrayList<>();
        int from = 0;
        while (true) {
            int idx = text.indexOf(OUT_TOKEN, from);
            if (idx < 0) {
                break;
            }
            positions.add(idx);
            from = idx + OUT_TOKEN.length();
        }
        if (positions.isEmpty()) {
            return text;
        }
        if (positions.size() == 1) {
            return text.replace(OUT_TOKEN, numberedRepl);
        }
        // positions[0]=문자열상 첫 '아웃', positions[1]=둘째 — 둘째만 N아웃(같은 플레이 설명의 중복 서술)
        StringBuilder sb = new StringBuilder(text);
        for (int i = positions.size() - 1; i >= 0; i--) {
            int pos = positions.get(i);
            String repl = (i == 0) ? OUT_EMPHASIS_HTML : numberedRepl;
            sb.replace(pos, pos + OUT_TOKEN.length(), repl);
        }
        return sb.toString();
    }

    /** "삼진"만 있고 "아웃" 문구가 없는 줄(예: 헛스윙 삼진) */
    private static String replaceStrikeoutWithNumberedEmphasisIfPresent(String text, int[] outsInHalf) {
        if (!containsStrikeoutAsOut(text)) {
            return text;
        }
        outsInHalf[0]++;
        int n = outsInHalf[0];
        String numberedRepl = "<strong class=\"out-emphasis\">" + n + "아웃</strong>";
        return text.replaceFirst("삼진", numberedRepl);
    }

    private static boolean containsStrikeoutAsOut(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        if (text.contains(OUT_TOKEN)) {
            return false;
        }
        if (!text.contains("삼진")) {
            return false;
        }
        return !text.contains("삼진족");
    }

    /**
     * '아웃' 글자 없이도 주자 아웃(포스/터치 등)으로 보이면 이닝 카운트에 넣고 문장 끝에 N아웃을 붙인다.
     */
    private static String replaceImplicitRunnerPlayOutEmphasisIfPresent(String text, int[] outsInHalf) {
        if (!runnerPlayLineIndicatesOutWithoutOutToken(text)) {
            return text;
        }
        outsInHalf[0]++;
        int n = outsInHalf[0];
        String numberedRepl = "<strong class=\"out-emphasis\">" + n + "아웃</strong>";
        return text + " " + numberedRepl;
    }

    /**
     * 예: "1루주자 윤동희 : 유격수->2루수 2루 터치" (아웃 생략), "… 포스" 만 있는 경우.
     */
    private static boolean runnerPlayLineIndicatesOutWithoutOutToken(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        if (text.contains(OUT_TOKEN)) {
            return false;
        }
        if (text.contains("진루") || text.contains("홈인") || text.contains("득점")) {
            return false;
        }
        if (text.contains("세이프")) {
            return false;
        }
        boolean runnerLine = text.contains("루주자")
                || text.contains("1루주자")
                || text.contains("2루주자")
                || text.contains("3루주자");
        if (!runnerLine) {
            return false;
        }
        return text.contains("포스")
                || text.contains("터치")
                || text.contains("태그");
    }

    /** 타석 결과 문구에서 '아웃'·'실책'·'홈인'을 강조용 HTML로 감싼 문자열 (화면용, th:utext 사용) */
    public String getResultTextWithOutEmphasis() {
        if (resultText == null || resultText.isEmpty()) {
            return "";
        }
        return resultText.replace(OUT_TOKEN, OUT_EMPHASIS_HTML)
                .replace("실책", ERROR_EMPHASIS_HTML)
                .replace("홈인", RUN_EMPHASIS_HTML);
    }

    /** 주자 플레이 목록에서 '아웃'·'실책'·'홈인'을 강조용 HTML로 감싼 목록 (화면용, th:utext 사용) */
    public List<String> getRunnerPlaysListWithOutEmphasis() {
        return getRunnerPlaysList().stream()
                .map(play -> play.replace(OUT_TOKEN, OUT_EMPHASIS_HTML)
                        .replace("실책", ERROR_EMPHASIS_HTML)
                        .replace("홈인", RUN_EMPHASIS_HTML))
                .collect(Collectors.toList());
    }

    void setGame(Game game) {
        this.game = game;
    }
}
