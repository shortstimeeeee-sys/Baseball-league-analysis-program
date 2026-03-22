package com.baseball.application.team;

import com.baseball.common.NotFoundException;
import com.baseball.domain.league.League;
import com.baseball.domain.league.LeagueRepository;
import com.baseball.domain.team.Team;
import com.baseball.domain.team.TeamRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TeamService {

    private final TeamRepository teamRepository;
    private final LeagueRepository leagueRepository;

    public List<Team> findAll() {
        return teamRepository.findAllByOrderByNameAsc();
    }

    public List<Team> findByLeagueId(Long leagueId) {
        return teamRepository.findByLeagueIdOrderByNameAsc(leagueId);
    }

    public Team getById(Long id) {
        return teamRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("팀을 찾을 수 없습니다. id=" + id));
    }

    @Transactional
    public Team create(String name, String shortName, Long leagueId, Integer foundedYear, String homeStadium, String logoUrl) {
        League league = leagueId != null ? leagueRepository.findById(leagueId).orElse(null) : null;
        Team team = Team.builder()
                .name(name)
                .shortName(shortName)
                .league(league)
                .foundedYear(foundedYear)
                .homeStadium(homeStadium)
                .logoUrl(logoUrl)
                .build();
        return teamRepository.save(team);
    }

    @Transactional
    public Team update(Long id, String name, String shortName, Long leagueId, Integer foundedYear, String homeStadium, String logoUrl) {
        Team team = getById(id);
        League league = leagueId != null ? leagueRepository.findById(leagueId).orElse(null) : null;
        team.update(name, shortName, foundedYear, homeStadium, logoUrl);
        team.setLeague(league);
        return team;
    }

    @Transactional
    public void delete(Long id) {
        Team team = getById(id);
        teamRepository.delete(team);
    }
}
