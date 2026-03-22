package com.baseball.application.league;

import com.baseball.common.NotFoundException;
import com.baseball.domain.league.League;
import com.baseball.domain.league.LeagueRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LeagueService {

    private final LeagueRepository leagueRepository;

    public List<League> findAll() {
        return leagueRepository.findAllByOrderByNameAsc();
    }

    public League getById(Long id) {
        return leagueRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("리그를 찾을 수 없습니다. id=" + id));
    }

    @Transactional
    public League create(String name, String country, String description) {
        League league = League.builder()
                .name(name)
                .country(country)
                .description(description)
                .build();
        return leagueRepository.save(league);
    }

    @Transactional
    public League update(Long id, String name, String country, String description) {
        League league = getById(id);
        league.update(name, country, description);
        return league;
    }

    @Transactional
    public void delete(Long id) {
        League league = getById(id);
        leagueRepository.delete(league);
    }
}
