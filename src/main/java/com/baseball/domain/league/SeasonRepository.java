package com.baseball.domain.league;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Year;
import java.util.List;
import java.util.Optional;

public interface SeasonRepository extends JpaRepository<Season, Long> {

    List<Season> findByLeagueIdOrderByYearDesc(Long leagueId);

    Optional<Season> findByLeagueIdAndYear(Long leagueId, Year year);
}
