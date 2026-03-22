package com.baseball.domain.league;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LeagueRepository extends JpaRepository<League, Long> {

    List<League> findAllByOrderByNameAsc();

    java.util.Optional<League> findFirstByName(String name);
}
