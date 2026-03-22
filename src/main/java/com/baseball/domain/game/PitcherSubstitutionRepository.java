package com.baseball.domain.game;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PitcherSubstitutionRepository extends JpaRepository<PitcherSubstitution, Long> {

    @Query("SELECT ps FROM PitcherSubstitution ps WHERE ps.game.id = :gameId ORDER BY ps.inning ASC, ps.isTop DESC, COALESCE(ps.afterPaSequenceOrder, 0) ASC, ps.displayOrder ASC, ps.battersFaced ASC")
    List<PitcherSubstitution> findByGameIdOrderByInningAscIsTopDescBattersFacedAsc(@Param("gameId") Long gameId);
}
