package com.baseball.domain.game;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface GameRepository extends JpaRepository<Game, Long> {

    @EntityGraph(attributePaths = {"homeTeam", "awayTeam"})
    List<Game> findAllByOrderByGameDateTimeDesc();
    @EntityGraph(attributePaths = {"homeTeam", "awayTeam"})
    List<Game> findByExhibitionFalseOrderByGameDateTimeDesc();
    @EntityGraph(attributePaths = {"homeTeam", "awayTeam"})
    List<Game> findByExhibitionTrueOrderByGameDateTimeDesc();

    @EntityGraph(attributePaths = {"homeTeam", "awayTeam"})
    List<Game> findByStatusOrderByGameDateTimeDesc(Game.GameStatus status);
    @EntityGraph(attributePaths = {"homeTeam", "awayTeam"})
    List<Game> findByStatusAndExhibitionFalseOrderByGameDateTimeDesc(Game.GameStatus status);
    @EntityGraph(attributePaths = {"homeTeam", "awayTeam"})
    List<Game> findByStatusAndExhibitionTrueOrderByGameDateTimeDesc(Game.GameStatus status);

    @EntityGraph(attributePaths = {"homeTeam", "awayTeam"})
    @Query("SELECT g FROM Game g WHERE g.status = :status AND g.gameDateTime >= :start AND g.gameDateTime < :end ORDER BY g.gameDateTime DESC")
    List<Game> findByStatusAndGameDateTimeBetweenOrderByGameDateTimeDesc(
            @Param("status") Game.GameStatus status,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);

    @EntityGraph(attributePaths = {"homeTeam", "awayTeam"})
    @Query("SELECT g FROM Game g WHERE g.gameDateTime >= :start AND g.gameDateTime < :end ORDER BY g.gameDateTime DESC")
    List<Game> findByGameDateTimeBetweenOrderByGameDateTimeDesc(
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);
    @EntityGraph(attributePaths = {"homeTeam", "awayTeam"})
    @Query("SELECT g FROM Game g WHERE g.exhibition = false AND g.gameDateTime >= :start AND g.gameDateTime < :end ORDER BY g.gameDateTime DESC")
    List<Game> findByExhibitionFalseAndGameDateTimeBetweenOrderByGameDateTimeDesc(
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);
    @EntityGraph(attributePaths = {"homeTeam", "awayTeam"})
    @Query("SELECT g FROM Game g WHERE g.exhibition = true AND g.gameDateTime >= :start AND g.gameDateTime < :end ORDER BY g.gameDateTime DESC")
    List<Game> findByExhibitionTrueAndGameDateTimeBetweenOrderByGameDateTimeDesc(
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);

    /** 상세 화면용: 팀 정보까지 한 번에 조회 (LazyInitializationException 방지) */
    @EntityGraph(attributePaths = {"homeTeam", "awayTeam"})
    @Query("SELECT g FROM Game g WHERE g.id = :id")
    Optional<Game> findByIdWithTeams(@Param("id") Long id);

    @EntityGraph(attributePaths = {"homeTeam", "awayTeam"})
    List<Game> findByHomeTeamIdOrAwayTeamIdOrderByGameDateTimeDesc(Long homeTeamId, Long awayTeamId);

    @EntityGraph(attributePaths = {"homeTeam", "awayTeam"})
    @Query("SELECT g FROM Game g WHERE g.homeTeam.id = :homeTeamId AND g.awayTeam.id = :awayTeamId AND g.gameDateTime >= :start AND g.gameDateTime < :end ORDER BY g.gameDateTime DESC")
    List<Game> findByHomeAwayAndGameDateTimeBetweenOrderByGameDateTimeDesc(
            @Param("homeTeamId") Long homeTeamId,
            @Param("awayTeamId") Long awayTeamId,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);

    @Query("SELECT g.id FROM Game g WHERE NOT EXISTS (SELECT 1 FROM PlateAppearance pa WHERE pa.game.id = g.id) AND NOT EXISTS (SELECT 1 FROM InningScore i WHERE i.game.id = g.id)")
    List<Long> findIdsWithoutAnyRecordData();
}
