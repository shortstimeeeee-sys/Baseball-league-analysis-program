package com.baseball.domain.player;

import com.baseball.domain.team.Team;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PlayerRepository extends JpaRepository<Player, Long> {

    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = "team")
    List<Player> findAllByOrderByNameAsc();

    List<Player> findByTeamOrderByNameAsc(Team team);

    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = "team")
    @Query("SELECT p FROM Player p WHERE p.team.id = :teamId ORDER BY p.name ASC")
    List<Player> findByTeamIdOrderByNameAsc(@Param("teamId") Long teamId);

    List<Player> findByName(String name);

    Optional<Player> findByNameAndTeamId(String name, Long teamId);
}
