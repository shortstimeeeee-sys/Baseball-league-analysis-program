package com.baseball.application.player;

import com.baseball.common.NotFoundException;
import com.baseball.domain.player.Player;
import com.baseball.domain.player.PlayerRepository;
import com.baseball.domain.player.Player.Position;
import com.baseball.domain.team.TeamRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PlayerService {

    private final PlayerRepository playerRepository;
    private final TeamRepository teamRepository;

    public List<Player> findAll() {
        return playerRepository.findAllByOrderByNameAsc();
    }

    public List<Player> findByTeamId(Long teamId) {
        return playerRepository.findByTeamIdOrderByNameAsc(teamId);
    }

    public Player getById(Long id) {
        return playerRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("선수를 찾을 수 없습니다. id=" + id));
    }

    @Transactional
    public Player create(String name, Long teamId, Position position, Integer backNumber, LocalDate birthDate, String nationality) {
        var team = teamId != null ? teamRepository.findById(teamId).orElse(null) : null;
        Player player = Player.builder()
                .name(name)
                .team(team)
                .position(position)
                .backNumber(backNumber)
                .birthDate(birthDate)
                .nationality(nationality)
                .build();
        return playerRepository.save(player);
    }

    @Transactional
    public Player update(Long id, String name, Long teamId, Position position, Integer backNumber, LocalDate birthDate, String nationality) {
        Player player = getById(id);
        var team = teamId != null ? teamRepository.findById(teamId).orElse(null) : null;
        player.update(name, position, backNumber, birthDate, nationality);
        player.setTeam(team);
        return player;
    }

    @Transactional
    public void delete(Long id) {
        Player player = getById(id);
        playerRepository.delete(player);
    }
}
