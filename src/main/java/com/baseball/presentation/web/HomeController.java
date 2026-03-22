package com.baseball.presentation.web;

import com.baseball.application.league.LeagueService;
import com.baseball.application.team.TeamService;
import com.baseball.application.player.PlayerService;
import com.baseball.application.game.GameService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@RequiredArgsConstructor
public class HomeController {

    private final LeagueService leagueService;
    private final TeamService teamService;
    private final PlayerService playerService;
    private final GameService gameService;

    @GetMapping("/")
    public String home(Model model) {
        int leagueCount = leagueService.findAll().size();
        int teamCount = teamService.findAll().size();
        int playerCount = playerService.findAll().size();
        int gameCount = gameService.findAll().size();
        model.addAttribute("leagueCount", leagueCount);
        model.addAttribute("teamCount", teamCount);
        model.addAttribute("playerCount", playerCount);
        model.addAttribute("gameCount", gameCount);
        model.addAttribute("tagline", String.format("리그 %d · 팀 %d · 선수 %d · 경기 %d건", leagueCount, teamCount, playerCount, gameCount));
        return "home";
    }
}
