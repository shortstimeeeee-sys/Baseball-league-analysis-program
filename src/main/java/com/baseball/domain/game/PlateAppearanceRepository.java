package com.baseball.domain.game;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PlateAppearanceRepository extends JpaRepository<PlateAppearance, Long> {

    /** 1회초 → 1회말 → 2회초 → … 순서. 각 이닝·초/말 안에서는 등장 순서(sequenceOrder)로 정렬 → 1회가 6번에서 끝났으면 2회는 7번·8번·9번·1번… 순으로 표시 */
    @Query("SELECT pa FROM PlateAppearance pa LEFT JOIN FETCH pa.pitches WHERE pa.game.id = :gameId ORDER BY pa.inning ASC, pa.isTop DESC, pa.sequenceOrder ASC")
    List<PlateAppearance> findByGameIdWithPitches(@Param("gameId") Long gameId);

    @Query("SELECT pa FROM PlateAppearance pa WHERE pa.game.id = :gameId ORDER BY pa.inning ASC, pa.isTop DESC, pa.sequenceOrder ASC")
    List<PlateAppearance> findByGameIdOrderByInningAscIsTopDescSequenceOrderAsc(@Param("gameId") Long gameId);

    List<PlateAppearance> findByGameIdAndInningOrderByIsTopDescSequenceOrderAsc(Long gameId, Integer inning);
}
