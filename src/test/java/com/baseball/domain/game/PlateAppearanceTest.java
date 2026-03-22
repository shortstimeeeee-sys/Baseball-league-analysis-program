package com.baseball.domain.game;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlateAppearanceTest {

    @Test
    @DisplayName("같은 반 이닝에서 아웃 문구를 1아웃·2아웃·3아웃 순으로 강조")
    void buildEmphasisHtml_incrementsOutsPerHalf() {
        Game game = Mockito.mock(Game.class);
        PlateAppearance pa1 = PlateAppearance.builder()
                .game(game)
                .id(1L)
                .inning(1)
                .isTop(true)
                .sequenceOrder(1)
                .batterName("a")
                .pitcherName("p")
                .resultText("타자1 : 중견수 플라이 아웃")
                .build();
        PlateAppearance pa2 = PlateAppearance.builder()
                .game(game)
                .id(2L)
                .inning(1)
                .isTop(true)
                .sequenceOrder(2)
                .batterName("b")
                .pitcherName("p")
                .resultText("타자2 : 유격수 땅볼 아웃")
                .build();
        PlateAppearance pa3 = PlateAppearance.builder()
                .game(game)
                .id(3L)
                .inning(1)
                .isTop(true)
                .sequenceOrder(3)
                .batterName("c")
                .pitcherName("p")
                .resultText("타자3 : 삼진 아웃")
                .build();

        PlateAppearance.EmphasisHtml em = PlateAppearance.buildEmphasisHtml(List.of(pa1, pa2, pa3));

        assertThat(em.resultById().get(1L)).contains("1아웃").doesNotContain(">아웃<");
        assertThat(em.resultById().get(2L)).contains("2아웃");
        assertThat(em.resultById().get(3L)).contains("3아웃");
    }

    @Test
    @DisplayName("회말로 넘어가면 아웃 카운트가 다시 1부터")
    void buildEmphasisHtml_resetsOnHalfChange() {
        Game game = Mockito.mock(Game.class);
        PlateAppearance topLast = PlateAppearance.builder()
                .game(game)
                .id(10L)
                .inning(1)
                .isTop(true)
                .sequenceOrder(9)
                .batterName("x")
                .pitcherName("p")
                .resultText("세 번째 아웃")
                .build();
        PlateAppearance bottomFirst = PlateAppearance.builder()
                .game(game)
                .id(11L)
                .inning(1)
                .isTop(false)
                .sequenceOrder(1)
                .batterName("y")
                .pitcherName("p")
                .resultText("말 공격 첫 아웃")
                .build();

        PlateAppearance.EmphasisHtml em = PlateAppearance.buildEmphasisHtml(List.of(topLast, bottomFirst));

        assertThat(em.resultById().get(11L)).contains("1아웃");
    }

    @Test
    @DisplayName("한 줄에 아웃이 여러 번 나와도 같은 타석 서술이면 아웃 번호는 한 번만 증가")
    void buildEmphasisHtml_sameLineMultipleOutWord_singleCount() {
        Game game = Mockito.mock(Game.class);
        PlateAppearance pa = PlateAppearance.builder()
                .game(game)
                .id(30L)
                .inning(1)
                .isTop(true)
                .sequenceOrder(1)
                .batterName("최원준")
                .pitcherName("p")
                .resultText("1루수 땅볼 아웃 (1루수 1루 터치아웃)")
                .build();

        PlateAppearance.EmphasisHtml em = PlateAppearance.buildEmphasisHtml(List.of(pa));

        String html = em.resultById().get(30L);
        assertThat(html).contains("터치");
        assertThat(html).contains("1아웃");
        assertThat(html).doesNotContain("2아웃");
        assertThat(html).contains("<strong class=\"out-emphasis\">아웃</strong>");
        assertThat(html.split("1아웃", -1).length - 1).isEqualTo(1);
    }

    @Test
    @DisplayName("타석 결과 다음 주자 줄에서도 아웃 번호가 이어짐")
    void buildEmphasisHtml_runnerLineContinuesCount() {
        Game game = Mockito.mock(Game.class);
        PlateAppearance pa = PlateAppearance.builder()
                .game(game)
                .id(20L)
                .inning(2)
                .isTop(false)
                .sequenceOrder(1)
                .batterName("z")
                .pitcherName("p")
                .resultText("타자 : 희생번트 아웃")
                .runnerPlaysText("2루주자 : 홈으로 아웃")
                .build();

        PlateAppearance.EmphasisHtml em = PlateAppearance.buildEmphasisHtml(List.of(pa));

        assertThat(em.resultById().get(20L)).contains("1아웃");
        assertThat(em.runnerLinesById().get(20L).get(0)).contains("2아웃");
    }

    @Test
    @DisplayName("아웃 번호는 화면(타순) 순이 아니라 sequenceOrder(실제 타석 순)를 따른다")
    void buildEmphasisHtml_chronologicalSequenceOrder_notDisplayOrder() {
        Game game = Mockito.mock(Game.class);
        // 4회말: 실제 순서 seq 31,32 먼저 아웃 → seq 33 손호영(출루 + 주자 2루 터치아웃 = 3아웃)
        PlateAppearance earlier1 = PlateAppearance.builder()
                .game(game)
                .id(31L)
                .inning(4)
                .isTop(false)
                .sequenceOrder(31)
                .batterName("a")
                .pitcherName("p")
                .resultText("중견수 플라이 아웃")
                .build();
        PlateAppearance earlier2 = PlateAppearance.builder()
                .game(game)
                .id(32L)
                .inning(4)
                .isTop(false)
                .sequenceOrder(32)
                .batterName("b")
                .pitcherName("p")
                .resultText("유격수 땅볼 아웃")
                .build();
        PlateAppearance son = PlateAppearance.builder()
                .game(game)
                .id(33L)
                .inning(4)
                .isTop(false)
                .sequenceOrder(33)
                .batterName("손호영")
                .pitcherName("p")
                .resultText("유격수 앞 땅볼로 출루")
                .runnerPlaysText("1루주자 윤동희 : 포스아웃 (유격수->2루수 2루 터치아웃)")
                .build();

        // 화면용 타순 정렬로 손호영 카드가 맨 위(먼저)에 온다고 가정
        List<PlateAppearance> displayOrder = List.of(son, earlier1, earlier2);

        PlateAppearance.EmphasisHtml em = PlateAppearance.buildEmphasisHtml(displayOrder);

        assertThat(em.resultById().get(31L)).contains("1아웃");
        assertThat(em.resultById().get(32L)).contains("2아웃");
        assertThat(em.resultById().get(33L)).doesNotContain("1아웃");
        String runner = em.runnerLinesById().get(33L).get(0);
        assertThat(runner).contains("3아웃");
        assertThat(runner).contains("터치");
        assertThat(runner).contains("<strong class=\"out-emphasis\">아웃</strong>");
        assertThat(runner.split("3아웃", -1).length - 1).isEqualTo(1);
    }

    @Test
    @DisplayName("아웃 글자 없이 '삼진'만 있는 타석도 이닝 카운트에 포함되어 이후 줄이 3아웃이 된다")
    void buildEmphasisHtml_strikeoutWithoutOutWord_thenRunnerThirdOut() {
        Game game = Mockito.mock(Game.class);
        PlateAppearance pa1 = PlateAppearance.builder()
                .game(game)
                .id(101L)
                .inning(4)
                .isTop(false)
                .sequenceOrder(1)
                .batterName("a")
                .pitcherName("p")
                .resultText("헛스윙 삼진")
                .build();
        PlateAppearance pa2 = PlateAppearance.builder()
                .game(game)
                .id(102L)
                .inning(4)
                .isTop(false)
                .sequenceOrder(2)
                .batterName("b")
                .pitcherName("p")
                .resultText("스윙 삼진")
                .build();
        PlateAppearance son = PlateAppearance.builder()
                .game(game)
                .id(103L)
                .inning(4)
                .isTop(false)
                .sequenceOrder(3)
                .batterName("손호영")
                .pitcherName("p")
                .resultText("유격수 앞 땅볼로 출루")
                .runnerPlaysText("1루주자 윤동희 : 포스아웃 (유격수->2루수 2루 터치아웃)")
                .build();

        PlateAppearance.EmphasisHtml em = PlateAppearance.buildEmphasisHtml(List.of(son, pa2, pa1));

        assertThat(em.resultById().get(101L)).contains("1아웃");
        assertThat(em.resultById().get(102L)).contains("2아웃");
        String runner = em.runnerLinesById().get(103L).get(0);
        assertThat(runner).contains("3아웃");
        assertThat(runner).contains("포스");
        assertThat(runner).contains("터치");
    }

    @Test
    @DisplayName("주자 줄에 '아웃' 글자 없이 포스/터치만 있어도 이닝 아웃으로 센다 (땅볼 후 주자 포스 등)")
    void buildEmphasisHtml_runnerLineWithoutOutWord_forcePlayCounts() {
        Game game = Mockito.mock(Game.class);
        PlateAppearance pa1 = PlateAppearance.builder()
                .game(game)
                .id(201L)
                .inning(4)
                .isTop(false)
                .sequenceOrder(1)
                .batterName("x")
                .pitcherName("p")
                .resultText("플라이 아웃")
                .build();
        PlateAppearance pa2 = PlateAppearance.builder()
                .game(game)
                .id(202L)
                .inning(4)
                .isTop(false)
                .sequenceOrder(2)
                .batterName("y")
                .pitcherName("p")
                .resultText("땅볼 아웃")
                .build();
        PlateAppearance son = PlateAppearance.builder()
                .game(game)
                .id(203L)
                .inning(4)
                .isTop(false)
                .sequenceOrder(3)
                .batterName("손호영")
                .pitcherName("p")
                .resultText("유격수 앞 땅볼로 출루")
                .runnerPlaysText("1루주자 윤동희 : 유격수->2루수 2루 터치")
                .build();

        PlateAppearance.EmphasisHtml em = PlateAppearance.buildEmphasisHtml(List.of(son, pa2, pa1));

        String runner = em.runnerLinesById().get(203L).get(0);
        assertThat(runner).contains("3아웃");
        assertThat(runner).contains("터치");
    }
}
