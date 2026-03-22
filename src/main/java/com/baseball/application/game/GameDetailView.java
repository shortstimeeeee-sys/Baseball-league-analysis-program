package com.baseball.application.game;

import com.baseball.domain.game.Game;
import com.baseball.domain.game.InningScore;
import com.baseball.domain.game.PlateAppearance;

import java.util.List;
import java.util.Map;

/**
 * 경기 상세·중계·기록 가져오기 화면용 묶음 DTO.
 * {@link GameService#getDetailWithRecord(Long)} 반환 타입 (IDE/분석기가 중첩 record를 잘못 파싱하는 문제를 피하기 위해 최상위 record로 둠).
 */
public record GameDetailView(
        Game game,
        List<InningScore> inningScores,
        Map<Integer, InningScore> inningScoreByInning,
        List<InningScore> inningScoreByIndex,
        List<PlateAppearance> plateAppearances,
        List<String> inningsWithError,
        List<GameService.RecordDisplayItem> recordDisplayItems,
        List<GameService.RecordDisplayItem> keyHighlightItems,
        String scoreboardSituationLabel) {
}
