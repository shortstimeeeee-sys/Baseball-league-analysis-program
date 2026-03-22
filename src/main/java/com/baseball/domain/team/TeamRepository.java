package com.baseball.domain.team;

import com.baseball.domain.league.League;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TeamRepository extends JpaRepository<Team, Long> {

    List<Team> findAllByOrderByNameAsc();

    List<Team> findByLeagueOrderByNameAsc(League league);

    List<Team> findByLeagueIdOrderByNameAsc(Long leagueId);

    java.util.Optional<Team> findFirstByNameAndLeagueId(String name, Long leagueId);

    java.util.Optional<Team> findFirstByName(String name);
}
