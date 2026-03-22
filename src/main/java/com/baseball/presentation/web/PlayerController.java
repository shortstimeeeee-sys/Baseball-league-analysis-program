package com.baseball.presentation.web;

import com.baseball.application.player.PlayerService;
import com.baseball.application.team.TeamService;
import com.baseball.domain.player.Player;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;

@Controller
@RequestMapping("/players")
@RequiredArgsConstructor
public class PlayerController {

    private final PlayerService playerService;
    private final TeamService teamService;

    @GetMapping
    public String list(@RequestParam(required = false) Long teamId, Model model) {
        model.addAttribute("teams", teamService.findAll());
        if (teamId != null) {
            model.addAttribute("players", playerService.findByTeamId(teamId));
            model.addAttribute("selectedTeamId", teamId);
        } else {
            model.addAttribute("players", playerService.findAll());
        }
        return "player/list";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Model model) {
        model.addAttribute("player", playerService.getById(id));
        return "player/detail";
    }

    @GetMapping("/new")
    public String createForm(@RequestParam(required = false) Long teamId,
                             @RequestParam(required = false) Player.Position position,
                             Model model) {
        model.addAttribute("teams", teamService.findAll());
        model.addAttribute("positions", Player.Position.values());
        model.addAttribute("player", new PlayerForm(null, teamId, position, null, null, null));
        return "player/form";
    }

    @PostMapping
    public String create(@Valid @ModelAttribute("player") PlayerForm form,
                         BindingResult bindingResult,
                         RedirectAttributes redirect,
                         Model model) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("teams", teamService.findAll());
            model.addAttribute("positions", Player.Position.values());
            return "player/form";
        }
        Player player = playerService.create(
                form.name(), form.teamId(), form.position(),
                form.backNumber(), form.birthDate(), form.nationality());
        redirect.addFlashAttribute("message", "선수가 등록되었습니다.");
        return "redirect:/players/" + player.getId();
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        Player player = playerService.getById(id);
        Long teamId = player.getTeam() != null ? player.getTeam().getId() : null;
        PlayerForm form = new PlayerForm(player.getName(), teamId, player.getPosition(),
                player.getBackNumber(), player.getBirthDate(), player.getNationality());
        model.addAttribute("playerId", id);
        model.addAttribute("teams", teamService.findAll());
        model.addAttribute("positions", Player.Position.values());
        model.addAttribute("player", form);
        return "player/form";
    }

    @PostMapping("/{id}")
    public String update(@PathVariable Long id,
                         @Valid @ModelAttribute("player") PlayerForm form,
                         BindingResult bindingResult,
                         RedirectAttributes redirect,
                         Model model) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("playerId", id);
            model.addAttribute("teams", teamService.findAll());
            model.addAttribute("positions", Player.Position.values());
            return "player/form";
        }
        playerService.update(id, form.name(), form.teamId(), form.position(),
                form.backNumber(), form.birthDate(), form.nationality());
        redirect.addFlashAttribute("message", "선수가 수정되었습니다.");
        return "redirect:/players/" + id;
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes redirect) {
        playerService.delete(id);
        redirect.addFlashAttribute("message", "선수가 삭제되었습니다.");
        return "redirect:/players";
    }

    public record PlayerForm(
            @NotBlank(message = "선수 이름을 입력해 주세요.")
            @Size(max = 100, message = "선수 이름은 100자 이하여야 합니다.")
            String name,
            Long teamId,
            Player.Position position,
            Integer backNumber,
            LocalDate birthDate,
            @Size(max = 50, message = "국적은 50자 이하여야 합니다.")
            String nationality
    ) {
        public PlayerForm() {
            this(null, null, null, null, null, null);
        }
    }
}
