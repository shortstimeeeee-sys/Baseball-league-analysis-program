package com.baseball.domain.game;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InningScoreRepository extends JpaRepository<InningScore, Long> {

    List<InningScore> findByGameIdOrderByInning(Long gameId);
}
