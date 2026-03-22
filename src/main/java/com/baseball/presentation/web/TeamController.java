package com.baseball.presentation.web;

import com.baseball.application.league.LeagueService;
import com.baseball.application.player.PlayerService;
import com.baseball.application.team.TeamService;
import com.baseball.domain.team.Team;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/teams")
@RequiredArgsConstructor
public class TeamController {

    private final TeamService teamService;
    private final LeagueService leagueService;
    private final PlayerService playerService;

    @GetMapping
    public String list(@RequestParam(required = false) Long leagueId, Model model) {
        model.addAttribute("leagues", leagueService.findAll());
        if (leagueId != null) {
            model.addAttribute("teams", teamService.findByLeagueId(leagueId));
            model.addAttribute("selectedLeagueId", leagueId);
        } else {
            model.addAttribute("teams", teamService.findAll());
        }
        return "team/list";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Model model) {
        Team team = teamService.getById(id);
        model.addAttribute("team", team);
        var players = playerService.findByTeamId(id);
        model.addAttribute("players", players);
        model.addAttribute("pitchers", players.stream()
                .filter(p -> p.getPosition() == com.baseball.domain.player.Player.Position.PITCHER)
                .toList());
        model.addAttribute("batters", players.stream()
                .filter(p -> p.getPosition() == null || p.getPosition() != com.baseball.domain.player.Player.Position.PITCHER)
                .toList());
        return "team/detail";
    }

    @GetMapping("/new")
    public String createForm(Model model) {
        model.addAttribute("leagues", leagueService.findAll());
        model.addAttribute("team", new TeamForm());
        return "team/form";
    }

    @PostMapping
    public String create(@Valid @ModelAttribute("team") TeamForm form,
                         BindingResult bindingResult,
                         RedirectAttributes redirect,
                         Model model) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("leagues", leagueService.findAll());
            return "team/form";
        }
        Team team = teamService.create(
                form.name(), form.shortName(), form.leagueId(),
                form.foundedYear(), form.homeStadium(), form.logoUrl());
        redirect.addFlashAttribute("message", "팀이 등록되었습니다.");
        return "redirect:/teams/" + team.getId();
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        Team team = teamService.getById(id);
        Long leagueId = team.getLeague() != null ? team.getLeague().getId() : null;
        TeamForm form = new TeamForm(team.getName(), team.getShortName(), leagueId,
                team.getFoundedYear(), team.getHomeStadium(), team.getLogoUrl());
        model.addAttribute("teamId", id);
        model.addAttribute("leagues", leagueService.findAll());
        model.addAttribute("team", form);
        return "team/form";
    }

    @PostMapping("/{id}")
    public String update(@PathVariable Long id,
                         @Valid @ModelAttribute("team") TeamForm form,
                         BindingResult bindingResult,
                         RedirectAttributes redirect,
                         Model model) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("teamId", id);
            model.addAttribute("leagues", leagueService.findAll());
            return "team/form";
        }
        teamService.update(id, form.name(), form.shortName(), form.leagueId(),
                form.foundedYear(), form.homeStadium(), form.logoUrl());
        redirect.addFlashAttribute("message", "팀이 수정되었습니다.");
        return "redirect:/teams/" + id;
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes redirect) {
        teamService.delete(id);
        redirect.addFlashAttribute("message", "팀이 삭제되었습니다.");
        return "redirect:/teams";
    }

    public record TeamForm(
            @NotBlank(message = "팀 이름을 입력해 주세요.")
            @Size(max = 100, message = "팀 이름은 100자 이하여야 합니다.")
            String name,
            @Size(max = 20, message = "약칭은 20자 이하여야 합니다.")
            String shortName,
            Long leagueId,
            Integer foundedYear,
            @Size(max = 200, message = "홈구장은 200자 이하여야 합니다.")
            String homeStadium,
            @Size(max = 500, message = "로고 URL은 500자 이하여야 합니다.")
            String logoUrl
    ) {
        public TeamForm() {
            this(null, null, null, null, null, null);
        }
    }
}
