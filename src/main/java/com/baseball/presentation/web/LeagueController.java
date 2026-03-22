package com.baseball.presentation.web;

import com.baseball.application.league.LeagueService;
import com.baseball.domain.league.League;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import jakarta.validation.Valid;

@Controller
@RequestMapping("/leagues")
@RequiredArgsConstructor
public class LeagueController {

    private final LeagueService leagueService;

    @GetMapping
    public String list(Model model) {
        model.addAttribute("leagues", leagueService.findAll());
        return "league/list";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Model model) {
        model.addAttribute("league", leagueService.getById(id));
        return "league/detail";
    }

    @GetMapping("/new")
    public String createForm(Model model) {
        model.addAttribute("league", new LeagueForm());
        return "league/form";
    }

    @PostMapping
    public String create(@Valid @ModelAttribute("league") LeagueForm form,
                         BindingResult bindingResult,
                         RedirectAttributes redirect) {
        if (bindingResult.hasErrors()) {
            return "league/form";
        }
        League league = leagueService.create(form.name(), form.country(), form.description());
        redirect.addFlashAttribute("message", "리그가 등록되었습니다.");
        return "redirect:/leagues/" + league.getId();
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        League league = leagueService.getById(id);
        LeagueForm form = new LeagueForm(league.getName(), league.getCountry(), league.getDescription());
        model.addAttribute("leagueId", id);
        model.addAttribute("league", form);
        return "league/form";
    }

    @PostMapping("/{id}")
    public String update(@PathVariable Long id,
                         @Valid @ModelAttribute("league") LeagueForm form,
                         BindingResult bindingResult,
                         RedirectAttributes redirect,
                         Model model) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("leagueId", id);
            return "league/form";
        }
        leagueService.update(id, form.name(), form.country(), form.description());
        redirect.addFlashAttribute("message", "리그가 수정되었습니다.");
        return "redirect:/leagues/" + id;
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes redirect) {
        leagueService.delete(id);
        redirect.addFlashAttribute("message", "리그가 삭제되었습니다.");
        return "redirect:/leagues";
    }

    public record LeagueForm(
            @NotBlank(message = "리그 이름을 입력해 주세요.")
            @Size(max = 100, message = "리그 이름은 100자 이하여야 합니다.")
            String name,
            @Size(max = 50, message = "국가는 50자 이하여야 합니다.")
            String country,
            @Size(max = 500, message = "설명은 500자 이하여야 합니다.")
            String description
    ) {
        public LeagueForm() {
            this(null, null, null);
        }
    }
}
