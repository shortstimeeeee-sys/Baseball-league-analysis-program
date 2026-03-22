package com.baseball.presentation.web;

import com.baseball.application.game.GameDetailView;
import com.baseball.application.game.GameRecordImportService;
import com.baseball.application.game.GameRecordTextParser;
import com.baseball.application.game.GameService;
import com.baseball.application.game.GameService.RecordDisplayItem;
import com.baseball.application.game.dto.GameRecordImportDto;
import com.baseball.application.team.TeamService;
import com.baseball.domain.game.BatterSubstitutionType;
import com.baseball.domain.game.Game;
import com.baseball.domain.game.PitcherSubstitution;
import com.baseball.domain.game.SubstitutionKind;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.net.URI;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;

@Slf4j
@Controller
@RequestMapping("/games")
@RequiredArgsConstructor
public class GameController {

    /**
     * 기록 탭·가져오기 화면에서 한 번에 렌더링할 타석 수 상한.
     * 앞쪽만 자르면 9회가 잘려 나가 기록 탭에서 9회 이동·표시가 안 되므로, 초과 시 {@link #truncateRecordItemsToLastPlateAppearances}로 끝 구간을 유지.
     */
    private static final int MAX_PLATE_APPEARANCES_ON_DETAIL = 9999;

    private final GameService gameService;
    private final TeamService teamService;
    private final GameRecordImportService gameRecordImportService;
    private final GameRecordTextParser gameRecordTextParser;
    private final ObjectMapper objectMapper;

    @GetMapping
    /** 경기 목록: 전체 경기. 날짜 필터 시 해당 날짜 경기만 표시 */
    public String list(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                       @RequestParam(required = false, defaultValue = "LEAGUE") String category,
                       Model model) {
        GameService.GameCategory selectedCategory = gameService.parseCategory(category);
        List<Game> games = gameService.findAllByDateAndCategory(date, selectedCategory);
        Map<Long, Integer> displayAwayWalksByGameId = new LinkedHashMap<>();
        Map<Long, Integer> displayHomeWalksByGameId = new LinkedHashMap<>();
        for (Game game : games) {
            if (game == null || game.getId() == null) continue;
            Integer awayWalks = game.getAwayWalks();
            Integer homeWalks = game.getHomeWalks();
            if (awayWalks == null || homeWalks == null) {
                try {
                    var detail = gameService.getDetailWithRecord(game.getId());
                    var pas = detail != null ? detail.plateAppearances() : null;
                    if (pas != null && !pas.isEmpty()) {
                        if (awayWalks == null) awayWalks = countWalkLikePlateAppearances(pas, true);
                        if (homeWalks == null) homeWalks = countWalkLikePlateAppearances(pas, false);
                    }
                } catch (Exception ignored) {
                    // 목록 화면은 표시 우선: 레코드 조회 실패 시 기존 값(또는 null) 유지
                }
            }
            displayAwayWalksByGameId.put(game.getId(), awayWalks);
            displayHomeWalksByGameId.put(game.getId(), homeWalks);
        }
        model.addAttribute("games", games);
        model.addAttribute("selectedDate", date);
        model.addAttribute("availableDates", gameService.getDistinctGameDatesByCategory(selectedCategory));
        model.addAttribute("selectedCategory", gameService.categoryQueryValue(selectedCategory));
        model.addAttribute("selectedCategoryLabel", gameService.categoryDisplayName(selectedCategory));
        model.addAttribute("isLeagueCategory", gameService.isLeagueCategory(selectedCategory));
        model.addAttribute("isExhibitionCategory", gameService.isExhibitionCategory(selectedCategory));
        model.addAttribute("isAllCategory", gameService.isAllCategory(selectedCategory));
        model.addAttribute("displayAwayWalksByGameId", displayAwayWalksByGameId);
        model.addAttribute("displayHomeWalksByGameId", displayHomeWalksByGameId);
        return "game/list";
    }

    /** 경기 상세: 초기 로드는 스코어보드만. 타석/투구는 기록 탭 클릭 시 별도 요청으로 로드 (ERR_INCOMPLETE_CHUNKED_ENCODING 방지) */
    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public String detail(@PathVariable Long id, Model model) {
        var result = gameService.getDetailWithRecord(id);
        model.addAttribute("game", result.game());
        model.addAttribute("displayAwayErrors", result.plateAppearances() != null && !result.plateAppearances().isEmpty()
                ? countTeamErrorsFromPlateAppearances(result.plateAppearances(), false)
                : result.game().getAwayErrors());
        model.addAttribute("displayHomeErrors", result.plateAppearances() != null && !result.plateAppearances().isEmpty()
                ? countTeamErrorsFromPlateAppearances(result.plateAppearances(), true)
                : result.game().getHomeErrors());
        model.addAttribute("displayAwayWalks", resolveDisplayWalks(result.game().getAwayWalks(), result.plateAppearances(), true));
        model.addAttribute("displayHomeWalks", resolveDisplayWalks(result.game().getHomeWalks(), result.plateAppearances(), false));
        model.addAttribute("winningPitcherTeamClass",
                resolvePitcherTeamClass(result.game().getWinningPitcherName(), result.plateAppearances(), result.recordDisplayItems()));
        model.addAttribute("losingPitcherTeamClass",
                resolvePitcherTeamClass(result.game().getLosingPitcherName(), result.plateAppearances(), result.recordDisplayItems()));
        model.addAttribute("savePitcherTeamClass",
                resolvePitcherTeamClass(result.game().getDisplaySavePitcherName(), result.plateAppearances(), result.recordDisplayItems()));
        model.addAttribute("inningScores", result.inningScores());
        model.addAttribute("inningScoreByInning", result.inningScoreByInning());
        model.addAttribute("inningScoreByIndex", result.inningScoreByIndex());
        model.addAttribute("inningsWithError", result.inningsWithError() != null ? result.inningsWithError() : List.of());
        model.addAttribute("scoreboardSituationLabel", result.scoreboardSituationLabel());
        model.addAttribute("plateAppearances", null);
        model.addAttribute("plateAppearanceTotalCount", 0);
        model.addAttribute("plateAppearanceLimit", MAX_PLATE_APPEARANCES_ON_DETAIL);
        return "game/detail";
    }

    /** 기록 탭용 HTML 조각 (별도 요청으로 로드해 초기 응답 크기 최소화). 타석+투수교체를 시간순으로 표시 */
    @GetMapping("/{id}/record-fragment")
    @Transactional(readOnly = true)
    public String recordFragment(@PathVariable Long id, Model model) {
        var result = gameService.getDetailWithRecord(id);
        var allItems = result.recordDisplayItems();
        int totalPa = result.plateAppearances() != null ? result.plateAppearances().size() : 0;
        List<RecordDisplayItem> listToShow = truncateRecordItemsToLastPlateAppearances(allItems, MAX_PLATE_APPEARANCES_ON_DETAIL);
        model.addAttribute("game", result.game());
        model.addAttribute("inningsWithError", result.inningsWithError() != null ? result.inningsWithError() : List.of());
        model.addAttribute("recordDisplayItems", listToShow != null ? listToShow : List.of());
        model.addAttribute("plateAppearanceTotalCount", totalPa);
        model.addAttribute("plateAppearanceLimit", MAX_PLATE_APPEARANCES_ON_DETAIL);
        model.addAttribute("recordConfirmedHalfKeys", new LinkedHashSet<>(result.game().getRecordConfirmedHalfKeySet()));
        addPlateAppearanceEmphasisToModel(model, result);
        return "game/detail-record-fragment :: recordFragment";
    }

    /** 주요 내용 탭용 HTML 조각. 파싱된 내용 중 투수 교체·득점·실책만 표시 */
    @GetMapping("/{id}/highlights-fragment")
    @Transactional(readOnly = true)
    public String highlightsFragment(@PathVariable Long id, Model model) {
        var result = gameService.getDetailWithRecord(id);
        model.addAttribute("game", result.game());
        model.addAttribute("recordDisplayItems", result.keyHighlightItems() != null ? result.keyHighlightItems() : List.<RecordDisplayItem>of());
        addPlateAppearanceEmphasisToModel(model, result);
        return "game/detail-highlights-fragment :: highlightsFragment";
    }

    /** 기록 탭(전력 비교/경기 기록 요약)용 HTML 조각 */
    @GetMapping("/{id}/stats-fragment")
    @Transactional(readOnly = true)
    public String statsFragment(@PathVariable Long id, Model model) {
        var result = gameService.getDetailWithRecord(id);
        var game = result.game();
        var pas = result.plateAppearances() != null ? result.plateAppearances() : List.<com.baseball.domain.game.PlateAppearance>of();
        var items = result.recordDisplayItems() != null ? result.recordDisplayItems() : List.<RecordDisplayItem>of();
        TeamStats away = buildTeamOffenseStats(pas, true);
        TeamStats home = buildTeamOffenseStats(pas, false);
        away = new TeamStats(
                away.hits(), away.homeRuns(), away.steals(), away.strikeouts(), away.doublePlays(),
                countTeamErrorsFromPlateAppearances(pas, false)
        );
        home = new TeamStats(
                home.hits(), home.homeRuns(), home.steals(), home.strikeouts(), home.doublePlays(),
                countTeamErrorsFromPlateAppearances(pas, true)
        );
        List<EventDetailRow> eventRows = buildGameEventRows(game, pas, items);
        List<StatCompareRow> teamCompareRows = buildTeamCompareRows(away, home);
        List<BatterDetailLine> awayBatterDetails = buildBatterDetailLines(pas, true);
        List<BatterDetailLine> homeBatterDetails = buildBatterDetailLines(pas, false);
        List<BatterBoardLine> awayBatterBoards = buildBatterBoardLines(pas, true);
        List<BatterBoardLine> homeBatterBoards = buildBatterBoardLines(pas, false);
        List<PitcherDetailLine> awayPitcherDetails = buildPitcherDetailLines(game, pas, items, false);
        List<PitcherDetailLine> homePitcherDetails = buildPitcherDetailLines(game, pas, items, true);
        PitcherCompare pitchers = buildPitcherCompare(game, pas, items);
        BatterDetailLine awayBatterTotal = buildBatterTotalLine(awayBatterDetails);
        BatterDetailLine homeBatterTotal = buildBatterTotalLine(homeBatterDetails);
        PitcherDetailLine awayPitcherTotal = buildPitcherTotalLine(awayPitcherDetails);
        PitcherDetailLine homePitcherTotal = buildPitcherTotalLine(homePitcherDetails);
        PitcherStatLine winningAwayLine = resolvePitcherLineFromDetailsFirst(
                pitchers.winningAway(), awayPitcherDetails, homePitcherDetails
        );
        PitcherStatLine winningHomeLine = resolvePitcherLineFromDetailsFirst(
                pitchers.winningHome(), awayPitcherDetails, homePitcherDetails
        );
        PitcherStatLine losingAwayLine = resolvePitcherLineFromDetailsFirst(
                pitchers.losingAway(), awayPitcherDetails, homePitcherDetails
        );
        PitcherStatLine losingHomeLine = resolvePitcherLineFromDetailsFirst(
                pitchers.losingHome(), awayPitcherDetails, homePitcherDetails
        );
        model.addAttribute("game", game);
        model.addAttribute("awayStats", away);
        model.addAttribute("homeStats", home);
        model.addAttribute("eventRows", eventRows);
        model.addAttribute("teamCompareRows", teamCompareRows);
        model.addAttribute("pitchers", pitchers);
        model.addAttribute("winningAwayName", winningAwayLine.name());
        model.addAttribute("winningAwaySummary", winningAwayLine.summary());
        model.addAttribute("winningHomeName", winningHomeLine.name());
        model.addAttribute("winningHomeSummary", winningHomeLine.summary());
        model.addAttribute("losingAwayName", losingAwayLine.name());
        model.addAttribute("losingAwaySummary", losingAwayLine.summary());
        model.addAttribute("losingHomeName", losingHomeLine.name());
        model.addAttribute("losingHomeSummary", losingHomeLine.summary());
        model.addAttribute("awayBatterDetails", awayBatterDetails);
        model.addAttribute("homeBatterDetails", homeBatterDetails);
        model.addAttribute("awayBatterTotal", awayBatterTotal);
        model.addAttribute("homeBatterTotal", homeBatterTotal);
        model.addAttribute("awayBatterBoards", awayBatterBoards);
        model.addAttribute("homeBatterBoards", homeBatterBoards);
        model.addAttribute("awayPitcherDetails", awayPitcherDetails);
        model.addAttribute("homePitcherDetails", homePitcherDetails);
        model.addAttribute("awayPitcherTotal", awayPitcherTotal);
        model.addAttribute("homePitcherTotal", homePitcherTotal);
        return "game/detail-stats-fragment :: statsFragment";
    }

    @GetMapping("/new")
    public String createForm(Model model) {
        model.addAttribute("teams", teamService.findAll());
        model.addAttribute("statuses", Game.GameStatus.values());
        model.addAttribute("game", new GameForm());
        return "game/form";
    }

    @PostMapping
    public String create(@Valid @ModelAttribute("game") GameForm form,
                         BindingResult bindingResult,
                         RedirectAttributes redirect,
                         Model model) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("teams", teamService.findAll());
            model.addAttribute("statuses", Game.GameStatus.values());
            return "game/form";
        }
        GameService.GameCreateResult cr = gameService.create(
                form.getHomeTeamId(), form.getAwayTeamId(), form.getGameDateTime(),
                form.getVenue(), form.getHomeScore(), form.getAwayScore(), form.getStatus(), form.getMemo(),
                form.getHalfInningTransitionNotes(),
                form.getHalfInningBreakNotes(),
                Boolean.TRUE.equals(form.getDoubleheader()), Boolean.TRUE.equals(form.getExhibition()));
        Game game = cr.game();
        redirect.addFlashAttribute("message", cr.mergedIntoExisting()
                ? "같은 날짜·같은 매치업 경기가 있어 정보를 업데이트했습니다."
                : "경기가 등록되었습니다.");
        return "redirect:/games/" + game.getId();
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        Game game = gameService.getById(id);
        GameForm form = new GameForm(
                game.getHomeTeam().getId(), game.getAwayTeam().getId(), game.getGameDateTime(),
                game.getVenue(), game.getHomeScore(), game.getAwayScore(), game.getStatus(), game.getMemo(),
                game.getHalfInningTransitionNotes(),
                game.getHalfInningBreakNotes(),
                game.isDoubleheader(), game.isExhibition());
        model.addAttribute("gameId", id);
        model.addAttribute("teams", teamService.findAll());
        model.addAttribute("statuses", Game.GameStatus.values());
        model.addAttribute("game", form);
        return "game/form";
    }

    @PostMapping("/{id}")
    public String update(@PathVariable Long id,
                         @Valid @ModelAttribute("game") GameForm form,
                         BindingResult bindingResult,
                         RedirectAttributes redirect,
                         Model model) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("gameId", id);
            model.addAttribute("teams", teamService.findAll());
            model.addAttribute("statuses", Game.GameStatus.values());
            return "game/form";
        }
        gameService.updateGameFull(id, form.getHomeScore(), form.getAwayScore(), form.getStatus(), form.getMemo(),
                form.getVenue(), form.getGameDateTime(), Boolean.TRUE.equals(form.getDoubleheader()),
                Boolean.TRUE.equals(form.getExhibition()),
                form.getHalfInningTransitionNotes(), form.getHalfInningBreakNotes());
        redirect.addFlashAttribute("message", "경기 정보가 수정되었습니다.");
        return "redirect:/games/" + id;
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id,
                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                         RedirectAttributes redirect) {
        gameService.delete(id);
        redirect.addFlashAttribute("message", "경기가 삭제되었습니다.");
        if (date != null) {
            return "redirect:/games?date=" + date;
        }
        return "redirect:/games";
    }

    /** 기존 경기에 기록 가져오기 폼. 파싱 구역 아래에 스코어보드·기록(경기 상세와 동일) 표시 */
    @GetMapping("/{id}/record/import")
    @Transactional(readOnly = true)
    public String recordImportForm(@PathVariable Long id, Model model) {
        GameDetailView result = gameService.getDetailWithRecord(id);
        model.addAttribute("game", result.game());
        model.addAttribute("displayAwayErrors", result.plateAppearances() != null && !result.plateAppearances().isEmpty()
                ? countTeamErrorsFromPlateAppearances(result.plateAppearances(), false)
                : result.game().getAwayErrors());
        model.addAttribute("displayHomeErrors", result.plateAppearances() != null && !result.plateAppearances().isEmpty()
                ? countTeamErrorsFromPlateAppearances(result.plateAppearances(), true)
                : result.game().getHomeErrors());
        model.addAttribute("displayAwayWalks", resolveDisplayWalks(result.game().getAwayWalks(), result.plateAppearances(), true));
        model.addAttribute("displayHomeWalks", resolveDisplayWalks(result.game().getHomeWalks(), result.plateAppearances(), false));
        model.addAttribute("winningPitcherTeamClass",
                resolvePitcherTeamClass(result.game().getWinningPitcherName(), result.plateAppearances(), result.recordDisplayItems()));
        model.addAttribute("losingPitcherTeamClass",
                resolvePitcherTeamClass(result.game().getLosingPitcherName(), result.plateAppearances(), result.recordDisplayItems()));
        model.addAttribute("savePitcherTeamClass",
                resolvePitcherTeamClass(result.game().getDisplaySavePitcherName(), result.plateAppearances(), result.recordDisplayItems()));
        model.addAttribute("inningScores", result.inningScores());
        model.addAttribute("inningScoreByInning", result.inningScoreByInning());
        model.addAttribute("inningScoreByIndex", result.inningScoreByIndex());
        model.addAttribute("inningsWithError", result.inningsWithError() != null ? result.inningsWithError() : List.of());
        model.addAttribute("scoreboardSituationLabel", result.scoreboardSituationLabel());
        List<RecordDisplayItem> items = result.recordDisplayItems();
        int totalPa = result.plateAppearances() != null ? result.plateAppearances().size() : 0;
        List<RecordDisplayItem> listToShow = truncateRecordItemsToLastPlateAppearances(items, MAX_PLATE_APPEARANCES_ON_DETAIL);
        model.addAttribute("recordDisplayItems", listToShow != null ? listToShow : List.of());
        model.addAttribute("plateAppearanceTotalCount", totalPa);
        model.addAttribute("plateAppearanceLimit", MAX_PLATE_APPEARANCES_ON_DETAIL);
        return "game/record-import";
    }

    /** 기존 경기에 기록 임포트. format=text 이면 중계 텍스트 파싱. appendOnly=true면 1회 유지. AJAX(Accept: application/json)면 페이지 이동 없이 JSON 응답 */
    @PostMapping("/{id}/record/import")
    public ResponseEntity<?> recordImport(@PathVariable Long id,
                                          @RequestParam("payload") String payload,
                                          @RequestParam(value = "format", required = false, defaultValue = "json") String format,
                                          @RequestParam(value = "appendOnly", required = false) Boolean appendOnly,
                                          @RequestHeader(value = "Accept", required = false) String accept,
                                          RedirectAttributes redirect) {
        boolean jsonResponse = accept != null && accept.contains(MediaType.APPLICATION_JSON_VALUE);
        try {
            Game targetGame = gameService.getById(id);
            if (targetGame.getGameDateTime() == null) {
                String msg = "경기 날짜/시간이 없는 경기는 기록을 가져올 수 없습니다. 먼저 경기 날짜를 설정해 주세요.";
                if (jsonResponse) {
                    return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON)
                            .body(Map.of("success", false, "error", msg));
                }
                redirect.addFlashAttribute("error", msg);
                return ResponseEntity.status(HttpStatus.FOUND).location(URI.create("/games/" + id + "/record/import")).build();
            }
            GameRecordImportDto dto = parseImportPayload(payload, format, false);
            gameRecordImportService.importRecord(dto, id, Boolean.TRUE.equals(appendOnly));
            int lastInning = 1;
            if (dto.getPlateAppearances() != null) {
                for (var row : dto.getPlateAppearances()) {
                    if (row.getInning() > lastInning) {
                        lastInning = row.getInning();
                    }
                }
            }
            if (dto.getHalfInningsWithHeader() != null && !dto.getHalfInningsWithHeader().isEmpty()) {
                for (String key : dto.getHalfInningsWithHeader()) {
                    String part = key.split("_")[0];
                    try {
                        int inn = Integer.parseInt(part);
                        if (inn > lastInning) lastInning = inn;
                    } catch (NumberFormatException ignored) {}
                }
            }
            final int inningToOpen = lastInning;
            boolean gameCompleted = dto.getGameInfo() != null && dto.getGameInfo().getStatus() == Game.GameStatus.COMPLETED;
            if (jsonResponse) {
                Game refreshed = gameService.getById(id);
                Map<String, Object> json = new LinkedHashMap<>();
                json.put("success", true);
                json.put("message", "경기 기록이 반영되었습니다.");
                json.put("gameId", id);
                json.put("lastInning", inningToOpen);
                json.put("gameCompleted", gameCompleted);
                json.put("confirmedHalfKeys", parseRecordConfirmedHalfKeysToList(refreshed.getRecordConfirmedHalfKeys()));
                return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(json);
            }
            redirect.addFlashAttribute("message", "경기 기록이 반영되었습니다.");
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create("/games/" + id)).build();
        } catch (Throwable e) {
            log.error("record import failed gameId={}", id, e);
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            String errMsg = "text".equalsIgnoreCase(format) ? "텍스트 파싱 오류: " + msg : "오류: " + msg;
            String safeMsg = errMsg != null && !errMsg.isBlank() ? errMsg : "오류가 발생했습니다.";
            if (jsonResponse) {
                return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("success", false, "error", safeMsg));
            }
            redirect.addFlashAttribute("error", safeMsg);
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create("/games/" + id + "/record/import")).build();
        }
    }

    /** JSON: { "halfKeys": ["1_true","2_false"] } — 파싱 병합 시 해당 반은 삭제·덮어쓰기 안 함 */
    @PostMapping(value = "/{id}/record/confirm-halves", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<Map<String, Object>> confirmRecordHalves(@PathVariable Long id,
                                                                     @RequestBody(required = false) HalfKeysPayload body) {
        List<String> keys = body != null ? body.halfKeys() : null;
        if (keys == null || keys.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "halfKeys 배열이 필요합니다."));
        }
        gameService.addRecordConfirmedHalfKeys(id, keys);
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PostMapping(value = "/{id}/record/unconfirm-halves", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<Map<String, Object>> unconfirmRecordHalves(@PathVariable Long id,
                                                                       @RequestBody(required = false) HalfKeysPayload body) {
        List<String> keys = body != null ? body.halfKeys() : null;
        if (keys == null || keys.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "halfKeys 배열이 필요합니다."));
        }
        gameService.removeRecordConfirmedHalfKeys(id, keys);
        return ResponseEntity.ok(Map.of("success", true));
    }

    private record HalfKeysPayload(@JsonProperty("halfKeys") List<String> halfKeys) {}

    /** {@code record_confirmed_half_keys} 쉼표 구분 문자열 → JSON용 리스트 */
    private static List<String> parseRecordConfirmedHalfKeysToList(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ArrayList<>();
        }
        List<String> out = new ArrayList<>();
        for (String part : raw.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    /** 새 경기 기록 가져오기 폼 */
    @GetMapping("/import")
    public String importForm(Model model) {
        model.addAttribute("teams", teamService.findAll());
        return "game/import";
    }

    /** 새 경기 생성 + 기록 임포트. format=text 이면 중계 텍스트 파싱, 아니면 JSON. from=home 이면 오류 시 대시보드로 복귀 */
    @PostMapping("/import")
    public String importNew(@RequestParam("payload") String payload,
                            @RequestParam(value = "format", required = false, defaultValue = "json") String format,
                            @RequestParam(value = "gameDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate gameDate,
                            @RequestParam(value = "gameTime", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime gameTime,
                            @RequestParam(value = "doubleheader", required = false) Boolean doubleheader,
                            @RequestParam(value = "exhibition", required = false) Boolean exhibition,
                            @RequestParam(value = "from", required = false) String from,
                            RedirectAttributes redirect) {
        String errorRedirect = "home".equalsIgnoreCase(from) ? "redirect:/" : "redirect:/games/import";
        try {
            GameRecordImportDto dto = parseImportPayload(payload, format, true);
            if (gameDate == null) {
                redirect.addFlashAttribute("error", "경기 날짜는 필수입니다.");
                return errorRedirect;
            }
            if (dto.getGameInfo() == null) {
                redirect.addFlashAttribute("error", "gameInfo가 없습니다. JSON이면 gameInfo를 포함해 주세요.");
                return errorRedirect;
            }
            if (dto.getGameInfo().getHomeTeamId() == null && (dto.getGameInfo().getHomeTeamName() == null || dto.getGameInfo().getHomeTeamName().isBlank())) {
                redirect.addFlashAttribute("error", "홈팀 정보가 없습니다. 중계 텍스트에는 'N회말 팀명 공격'이 있어야 합니다.");
                return errorRedirect;
            }
            if (dto.getGameInfo().getAwayTeamId() == null && (dto.getGameInfo().getAwayTeamName() == null || dto.getGameInfo().getAwayTeamName().isBlank())) {
                redirect.addFlashAttribute("error", "원정팀 정보가 없습니다. 중계 텍스트에는 'N회초 팀명 공격'이 있어야 합니다.");
                return errorRedirect;
            }
            applyImportedGameDateTime(dto, gameDate, gameTime);
            // 파서가 "경기가 종료" 등으로 COMPLETED를 주면 유지. 없으면 진행중.
            if (dto.getGameInfo() != null) {
                if (dto.getGameInfo().getStatus() == null) {
                    dto.getGameInfo().setStatus(Game.GameStatus.IN_PROGRESS);
                }
                dto.getGameInfo().setDoubleheader(Boolean.TRUE.equals(doubleheader));
                dto.getGameInfo().setExhibition(Boolean.TRUE.equals(exhibition));
            }
            GameRecordImportService.ImportRecordResult ir = gameRecordImportService.importRecord(dto, null, false);
            Game game = ir.game();
            redirect.addFlashAttribute("message", ir.mergedIntoExisting()
                    ? "같은 날짜·같은 매치업 경기가 있어 기록을 업데이트했습니다."
                    : "경기가 등록되었습니다. 아래에서 이어서 기록을 추가할 수 있습니다.");
            return "redirect:/games/" + game.getId() + "/record/import";
        } catch (Exception e) {
            redirect.addFlashAttribute("error", "text".equalsIgnoreCase(format) ? "텍스트 파싱 오류: " + e.getMessage() : "JSON 형식 오류: " + e.getMessage());
            return errorRedirect;
        }
    }

    @PostMapping("/{id}/finalize")
    public String finalizeGame(@PathVariable Long id, RedirectAttributes redirect) {
        gameService.updateResult(id, null, null, Game.GameStatus.COMPLETED, null);
        redirect.addFlashAttribute("message", "경기가 완료 처리되었습니다.");
        return "redirect:/games/" + id;
    }

    @PostMapping("/{id}/record/clear")
    public String clearRecordData(@PathVariable Long id, RedirectAttributes redirect) {
        gameService.clearRecordData(id);
        redirect.addFlashAttribute("message", "중계 기록이 모두 초기화되었습니다.");
        return "redirect:/games/" + id;
    }

    @PostMapping("/cleanup/unrecorded")
    public String cleanupUnrecordedGames(RedirectAttributes redirect) {
        int deleted = gameService.deleteUnrecordedGames();
        redirect.addFlashAttribute("message", "기록 없는 임의 경기 " + deleted + "건을 삭제했습니다.");
        return "redirect:/games";
    }

    /**
     * 기록 임포트 payload를 공통으로 파싱합니다.
     *
     * @param payload          원본 문자열 (텍스트 또는 JSON)
     * @param format           "text" 또는 "json"
     * @param keepParsedTeam   true 이면 텍스트 파싱에서 추출한 팀 정보를 유지, false 이면 team 정보는 비웁니다.
     */
    private GameRecordImportDto parseImportPayload(String payload, String format, boolean keepParsedTeam) throws Exception {
        GameRecordImportDto dto;
        if ("text".equalsIgnoreCase(format) && payload != null && !payload.isBlank()) {
            dto = gameRecordTextParser.parse(payload);
            if (!keepParsedTeam) {
                if (dto.getGameInfo() == null) {
                    dto.setGameInfo(new GameRecordImportDto.GameInfo());
                }
                dto.getGameInfo().setHomeTeamId(null);
                dto.getGameInfo().setAwayTeamId(null);
                dto.getGameInfo().setHomeTeamName(null);
                dto.getGameInfo().setAwayTeamName(null);
            }
        } else {
            dto = objectMapper.readValue(payload, GameRecordImportDto.class);
        }
        return dto;
    }

    private static void addPlateAppearanceEmphasisToModel(Model model, GameDetailView result) {
        model.addAttribute("plateAppearanceResultHtml",
                result.plateAppearanceResultHtml() != null ? result.plateAppearanceResultHtml() : Map.of());
        model.addAttribute("plateAppearanceRunnerPlaysHtml",
                result.plateAppearanceRunnerPlaysHtml() != null ? result.plateAppearanceRunnerPlaysHtml() : Map.of());
    }

    /**
     * 타석이 max 초과면 앞부분을 버리고 최근 max개 타석이 포함된 구간만 반환.
     * 앞에서만 자르면 9회가 DOM에 없어 기록 탭의 N회 이동·필터가 동작하지 않음.
     */
    private static List<RecordDisplayItem> truncateRecordItemsToLastPlateAppearances(
            List<RecordDisplayItem> allItems, int maxPlateAppearances) {
        if (allItems == null || allItems.isEmpty()) {
            return List.of();
        }
        long totalPa = allItems.stream().filter(i -> i.plateAppearance() != null).count();
        if (totalPa <= maxPlateAppearances) {
            return allItems;
        }
        int paCount = 0;
        int startIdx = 0;
        for (int i = allItems.size() - 1; i >= 0; i--) {
            if (allItems.get(i).plateAppearance() != null) {
                paCount++;
                if (paCount == maxPlateAppearances) {
                    startIdx = i;
                    break;
                }
            }
        }
        return new ArrayList<>(allItems.subList(startIdx, allItems.size()));
    }

    private void applyImportedGameDateTime(GameRecordImportDto dto, LocalDate gameDate, LocalTime gameTime) {
        if (dto == null) return;
        if (dto.getGameInfo() == null) dto.setGameInfo(new GameRecordImportDto.GameInfo());
        LocalDate baseDate = gameDate != null ? gameDate : LocalDate.now();
        LocalTime baseTime = gameTime != null ? gameTime : LocalTime.of(18, 30);
        dto.getGameInfo().setGameDateTime(LocalDateTime.of(baseDate, baseTime));
    }

    /** 저장된 B 값이 비어 있는 구경기 대응: 타석 결과에서 사사구(볼넷+사구) 개수 계산 */
    private int countWalkLikePlateAppearances(List<com.baseball.domain.game.PlateAppearance> plateAppearances, boolean top) {
        if (plateAppearances == null || plateAppearances.isEmpty()) return 0;
        int count = 0;
        for (var pa : plateAppearances) {
            if (pa == null || Boolean.TRUE.equals(pa.getIsTop()) != top) continue;
            String result = pa.getResultText();
            if (result == null || result.isBlank()) continue;
            if (result.contains("볼넷")
                    || result.contains("고의4구")
                    || result.contains("고의 4구")
                    || result.contains("자동 고의4구")
                    || result.contains("자동고의4구")
                    || result.contains("몸에 맞는 볼")
                    || result.contains("몸에 맞는볼")
                    || result.contains("사구")
                    || result.contains("데드볼")) {
                count++;
            }
        }
        return count;
    }

    private Integer resolveDisplayWalks(Integer gameWalks, List<com.baseball.domain.game.PlateAppearance> plateAppearances, boolean top) {
        if (gameWalks != null) return gameWalks;
        if (plateAppearances == null || plateAppearances.isEmpty()) return null;
        return countWalkLikePlateAppearances(plateAppearances, top);
    }

    /** 화면 표시용 E 계산: 타석 단위로 중복 제거(결과/주자플레이에 중복 기재되어도 1건) */
    private int countTeamErrorsFromPlateAppearances(List<com.baseball.domain.game.PlateAppearance> plateAppearances, boolean homeDefense) {
        if (plateAppearances == null || plateAppearances.isEmpty()) return 0;
        int count = 0;
        for (var pa : plateAppearances) {
            if (pa == null || pa.getIsTop() == null) continue;
            boolean isHomeFieldingHalf = Boolean.TRUE.equals(pa.getIsTop()); // 초(원정 공격) = 홈 수비
            if (isHomeFieldingHalf != homeDefense) continue;
            int paError = countErrorOccurrences(pa.getResultText());
            var plays = pa.getRunnerPlaysList();
            if (plays != null) {
                for (String play : plays) {
                    paError = Math.max(paError, countErrorOccurrences(play));
                }
            }
            count += (paError > 0 ? 1 : 0);
        }
        return count;
    }

    private int countErrorOccurrences(String text) {
        if (text == null || text.isBlank() || text.contains("무실책")) return 0;
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf("실책", idx)) != -1) {
            count++;
            idx += 2;
        }
        return count;
    }

    private TeamStats buildTeamOffenseStats(List<com.baseball.domain.game.PlateAppearance> plateAppearances, boolean topOffense) {
        int hits = 0, homeRuns = 0, steals = 0, strikeouts = 0, doublePlays = 0;
        for (var pa : plateAppearances) {
            if (pa == null || pa.getIsTop() == null) continue;
            if (Boolean.TRUE.equals(pa.getIsTop()) != topOffense) continue;
            String result = pa.getResultText() != null ? pa.getResultText() : "";
            if (isHitResult(result)) hits++;
            if (result.contains("홈런")) homeRuns++;
            if (result.contains("삼진")) strikeouts++;
            if (result.contains("병살")) doublePlays++;
            var plays = pa.getRunnerPlaysList();
            if (plays != null) {
                for (String play : plays) {
                    if (play == null) continue;
                    if (play.contains("도루")) steals++;
                    if (play.contains("병살")) doublePlays++;
                }
            }
        }
        return new TeamStats(hits, homeRuns, steals, strikeouts, doublePlays, 0);
    }

    private boolean isHitResult(String result) {
        if (result == null) return false;
        if (result.contains("1루타") || result.contains("2루타") || result.contains("3루타")
                || result.contains("홈런") || result.contains("내야안타") || result.contains("번트안타")) {
            return true;
        }
        // "좌전 안타", "우전안타", "중전 안타" 같은 일반 안타 표기도 피안타로 집계
        return result.contains("안타");
    }

    private List<StatCompareRow> buildTeamCompareRows(TeamStats away, TeamStats home) {
        List<StatCompareRow> rows = new ArrayList<>();
        addNonZeroStatRow(rows, "안타", away.hits(), home.hits());
        addNonZeroStatRow(rows, "홈런", away.homeRuns(), home.homeRuns());
        addNonZeroStatRow(rows, "도루", away.steals(), home.steals());
        addNonZeroStatRow(rows, "삼진", away.strikeouts(), home.strikeouts());
        addNonZeroStatRow(rows, "병살", away.doublePlays(), home.doublePlays());
        addNonZeroStatRow(rows, "실책", away.errors(), home.errors());
        return rows;
    }

    private void addNonZeroStatRow(List<StatCompareRow> rows, String label, int awayValue, int homeValue) {
        if (awayValue == 0 && homeValue == 0) return;
        rows.add(statRow(label, awayValue, homeValue));
    }

    private StatCompareRow statRow(String label, int awayValue, int homeValue) {
        int max = Math.max(awayValue, homeValue);
        return new StatCompareRow(label, awayValue, homeValue, max);
    }

    private List<EventDetailRow> buildGameEventRows(Game game, List<com.baseball.domain.game.PlateAppearance> plateAppearances,
                                                    List<RecordDisplayItem> recordItems) {
        var away = new LinkedHashMap<String, EventAccumulator>();
        var home = new LinkedHashMap<String, EventAccumulator>();
        for (String key : List.of("결승타", "2루타", "3루타", "홈런", "도루", "실책", "주루사", "병살타", "폭투")) {
            away.put(key, new EventAccumulator());
            home.put(key, new EventAccumulator());
        }
        GameWinningHit gwh = resolveGameWinningHit(game, plateAppearances);
        if (gwh != null) {
            if (gwh.awayOffense()) away.get("결승타").add(gwh.batterName(), gwh.inning());
            else home.get("결승타").add(gwh.batterName(), gwh.inning());
        }
        Map<Long, String> attributedPitcherByPaId = buildAttributedPitcherByPaId(recordItems);
        for (var pa : plateAppearances) {
            if (pa == null || pa.getIsTop() == null) continue;
            boolean awayOffense = Boolean.TRUE.equals(pa.getIsTop());
            boolean homeDefense = awayOffense;
            int inning = pa.getInning() != null ? pa.getInning() : 0;
            String batter = pa.getBatterName() != null && !pa.getBatterName().isBlank() ? pa.getBatterName() : "-";
            String pitcher = resolveAttributedPitcher(pa, attributedPitcherByPaId);
            String result = pa.getResultText() != null ? pa.getResultText() : "";
            var offenseMap = awayOffense ? away : home;
            var defenseMap = homeDefense ? home : away;

            if (result.contains("2루타")) offenseMap.get("2루타").add(batter, inning);
            if (result.contains("3루타")) offenseMap.get("3루타").add(batter, inning);
            if (result.contains("홈런")) offenseMap.get("홈런").add(batter, inning);
            if (result.contains("병살")) offenseMap.get("병살타").add(batter, inning);
            if (countErrorOccurrences(result) > 0) defenseMap.get("실책").add(batter, inning);
            if (isBaserunningOutEvent(result)) offenseMap.get("주루사").add(batter, inning);
            if (result.contains("폭투")) defenseMap.get("폭투").add(pitcher, inning);

            var plays = pa.getRunnerPlaysList();
            if (plays != null) {
                for (String play : plays) {
                    if (play == null) continue;
                    if (isStealSuccessEvent(play)) {
                        String runner = extractRunnerNameFromPlay(play);
                        offenseMap.get("도루").add(runner != null ? runner : batter, inning);
                    }
                    if (countErrorOccurrences(play) > 0) defenseMap.get("실책").add(batter, inning);
                    if (isBaserunningOutEvent(play)) {
                        String runner = extractRunnerNameFromPlay(play);
                        offenseMap.get("주루사").add(runner != null ? runner : batter, inning);
                    }
                    if (play.contains("폭투")) defenseMap.get("폭투").add(pitcher, inning);
                }
            }
        }
        List<EventDetailRow> rows = new ArrayList<>();
        // 결승타는 항상 맨 위
        {
            EventAccumulator a = away.get("결승타");
            EventAccumulator h = home.get("결승타");
            rows.add(new EventDetailRow(
                    "결승타",
                    a.count(),
                    a.format(),
                    h.count(),
                    h.format()
            ));
        }
        // 요청 우선순위: 2루타, 3루타, 홈런, 도루는 대상자가 있을 때만 노출
        for (String key : List.of("2루타", "3루타", "홈런", "도루")) {
            EventAccumulator a = away.get(key);
            EventAccumulator h = home.get(key);
            if (a.count() == 0 && h.count() == 0) continue;
            rows.add(new EventDetailRow(
                    key,
                    a.count(),
                    a.format(),
                    h.count(),
                    h.format()
            ));
        }
        for (String key : List.of("실책", "주루사", "병살타", "폭투")) {
            EventAccumulator a = away.get(key);
            EventAccumulator h = home.get(key);
            rows.add(new EventDetailRow(
                    key,
                    a.count(),
                    a.format(),
                    h.count(),
                    h.format()
            ));
        }
        return rows;
    }

    /**
     * 결승타 계산:
     * - 승리팀이 동점/열세(<=)에서 우세(>)로 바꾸는 "최종 리드 전환" 타석의 타자를 결승타로 간주.
     * - 한 타석에서 여러 점이 나도 해당 타석 타자 1명으로 기록.
     */
    private GameWinningHit resolveGameWinningHit(Game game, List<com.baseball.domain.game.PlateAppearance> plateAppearances) {
        if (game == null || plateAppearances == null || plateAppearances.isEmpty()) return null;
        boolean debug43 = game.getId() != null && game.getId().equals(43L);
        Integer awayFinal = game.getAwayScore();
        Integer homeFinal = game.getHomeScore();
        if (awayFinal == null || homeFinal == null || awayFinal.equals(homeFinal)) return null;
        boolean awayWin = awayFinal > homeFinal;

        List<com.baseball.domain.game.PlateAppearance> ordered = new ArrayList<>(plateAppearances);
        Map<Long, String> batterNameById = buildBatterNameById(plateAppearances);
        Map<Integer, String> awayLineupByOrder = buildLineupByBatterOrder(plateAppearances, true, batterNameById);
        Map<Integer, String> homeLineupByOrder = buildLineupByBatterOrder(plateAppearances, false, batterNameById);
        ordered.sort((a, b) -> {
            int ai = a.getInning() != null ? a.getInning() : 0;
            int bi = b.getInning() != null ? b.getInning() : 0;
            if (ai != bi) return Integer.compare(ai, bi);
            boolean at = Boolean.TRUE.equals(a.getIsTop());
            boolean bt = Boolean.TRUE.equals(b.getIsTop());
            if (at != bt) return at ? -1 : 1; // 초 -> 말
            int as = a.getSequenceOrder() != null ? a.getSequenceOrder() : 0;
            int bs = b.getSequenceOrder() != null ? b.getSequenceOrder() : 0;
            if (as != bs) return Integer.compare(as, bs);
            long aid = a.getId() != null ? a.getId() : 0L;
            long bid = b.getId() != null ? b.getId() : 0L;
            return Long.compare(aid, bid);
        });

        int away = 0, home = 0;
        List<com.baseball.domain.game.PlateAppearance> timeline = new ArrayList<>();
        List<Integer> awayAfter = new ArrayList<>();
        List<Integer> homeAfter = new ArrayList<>();
        List<Integer> runsByPa = new ArrayList<>();
        List<Boolean> topByPa = new ArrayList<>();
        for (var pa : ordered) {
            if (pa == null || pa.getIsTop() == null) {
                continue;
            }
            int runs = countRunsInPa(pa);
            boolean top = Boolean.TRUE.equals(pa.getIsTop());
            if (top) away += runs;
            else home += runs;
            timeline.add(pa);
            awayAfter.add(away);
            homeAfter.add(home);
            runsByPa.add(runs);
            topByPa.add(top);
            if (debug43) {
                log.info("[GWH-43] {}회{} seq={} batter={} runs={} result={} plays={}",
                        pa.getInning(),
                        top ? "초" : "말",
                        pa.getSequenceOrder(),
                        resolveBatterNameWithoutOrder(pa, batterNameById),
                        runs,
                        pa.getResultText(),
                        pa.getRunnerPlaysList());
            }
        }
        for (int i = 0; i < timeline.size(); i++) {
            var pa = timeline.get(i);
            int runs = runsByPa.get(i);
            if (runs <= 0) continue;
            boolean top = topByPa.get(i);
            int awayNow = awayAfter.get(i);
            int homeNow = homeAfter.get(i);
            int awayBefore = awayNow - (top ? runs : 0);
            int homeBefore = homeNow - (!top ? runs : 0);

            boolean leadChanged = awayWin
                    ? (top && awayBefore <= homeBefore && awayNow > homeNow)
                    : (!top && homeBefore <= awayBefore && homeNow > awayNow);
            if (debug43) {
                log.info("[GWH-43] idx={} {}회{} batter={} before={}:{} after={}:{} leadChanged={}",
                        i,
                        pa.getInning(),
                        top ? "초" : "말",
                        resolveBatterNameWithoutOrder(pa, batterNameById),
                        awayBefore, homeBefore,
                        awayNow, homeNow,
                        leadChanged);
            }
            if (!leadChanged) continue;

            boolean keptLeadToEnd = true;
            for (int j = i; j < timeline.size(); j++) {
                int a = awayAfter.get(j);
                int h = homeAfter.get(j);
                if (awayWin) {
                    if (a <= h) { keptLeadToEnd = false; break; }
                } else {
                    if (h <= a) { keptLeadToEnd = false; break; }
                }
            }
            if (keptLeadToEnd) {
                int inning = pa.getInning() != null ? pa.getInning() : 0;
                boolean topHalf = Boolean.TRUE.equals(pa.getIsTop());
                // 사용자 기준 보정:
                // 리드 전환 타석이 안타가 아닐 수 있어(예: 병살타/땅볼 타점) 체감과 어긋난다.
                // 이 경우 같은 공격 흐름에서 "득점이 난 첫 안타 타석"을 우선 결승타 타자로 본다.
                var winnerPa = pa;
                if (!isHitResult(pa.getResultText())) {
                    for (int k = i + 1; k < timeline.size(); k++) {
                        var candidate = timeline.get(k);
                        if (candidate == null || candidate.getIsTop() == null) continue;
                        if (!topByPa.get(k).equals(top)) break; // 공수 전환되면 종료
                        if (runsByPa.get(k) <= 0) continue;
                        if (!isHitResult(candidate.getResultText())) continue;
                        winnerPa = candidate;
                        break;
                    }
                }
                int winnerIdx = timeline.indexOf(winnerPa);
                if (winnerIdx < 0) winnerIdx = i;
                // 디버그/표시 경로와 동일한 해석 함수를 우선 사용해
                // 결승타 선택 단계에서만 이름이 비는 불일치를 막는다.
                String batter = resolveBatterNameWithoutOrder(winnerPa, batterNameById);
                if (debug43) {
                    log.info("[GWH-43] selected inning={} half={} leadIdx={} batter={}",
                            inning,
                            topHalf ? "초" : "말",
                            i,
                            batter);
                }
                if (batter == null || batter.isBlank()) batter = "-";
                return new GameWinningHit(awayWin, batter, inning);
            }
        }
        return null;
    }

    /**
     * 타석의 batterName이 비어 있을 때 같은 팀 타순 라인업으로 복원한다.
     * - 초 공격: 원정 라인업
     * - 말 공격: 홈 라인업
     */
    private String resolveBatterName(com.baseball.domain.game.PlateAppearance pa,
                                     Map<Long, String> batterNameById,
                                     Map<Integer, String> awayLineupByOrder,
                                     Map<Integer, String> homeLineupByOrder) {
        if (pa == null) return "-";
        String resolved = resolveBatterNameWithoutOrder(pa, batterNameById);
        if (resolved != null) return resolved;
        Integer order = pa.getBatterOrder();
        if (order != null) {
            boolean top = Boolean.TRUE.equals(pa.getIsTop());
            String fallback = sanitizePlayerName(top ? awayLineupByOrder.get(order) : homeLineupByOrder.get(order));
            if (fallback != null) return fallback;
        }
        return "-";
    }

    private Map<Integer, String> buildLineupByBatterOrder(
            List<com.baseball.domain.game.PlateAppearance> plateAppearances,
            boolean topOffense,
            Map<Long, String> batterNameById) {
        Map<Integer, String> byOrder = new LinkedHashMap<>();
        if (plateAppearances == null || plateAppearances.isEmpty()) return byOrder;
        for (var pa : plateAppearances) {
            if (pa == null || pa.getBatterOrder() == null || pa.getIsTop() == null) continue;
            if (Boolean.TRUE.equals(pa.getIsTop()) != topOffense) continue;
            String name = resolveBatterNameWithoutOrder(pa, batterNameById);
            if (name == null || name.isBlank() || "-".equals(name)) continue;
            byOrder.putIfAbsent(pa.getBatterOrder(), name);
        }
        return byOrder;
    }

    private Map<Long, String> buildBatterNameById(List<com.baseball.domain.game.PlateAppearance> plateAppearances) {
        Map<Long, String> byId = new LinkedHashMap<>();
        if (plateAppearances == null || plateAppearances.isEmpty()) return byId;
        for (var pa : plateAppearances) {
            if (pa == null || pa.getBatter() == null || pa.getBatter().getId() == null) continue;
            String name = sanitizePlayerName(pa.getBatterName());
            if (name == null && pa.getBatter() != null) {
                name = sanitizePlayerName(pa.getBatter().getName());
            }
            if (name == null || name.isBlank()) continue;
            byId.putIfAbsent(pa.getBatter().getId(), name);
        }
        return byId;
    }

    private String extractBatterNameFromRunnerPlays(com.baseball.domain.game.PlateAppearance pa) {
        if (pa == null) return null;
        var plays = pa.getRunnerPlaysList();
        if (plays == null || plays.isEmpty()) return null;
        java.util.regex.Pattern p1 = java.util.regex.Pattern.compile("1\\s*([^:()\\s]+)\\s*:");
        java.util.regex.Pattern p2 = java.util.regex.Pattern.compile("1루주자\\s*([^:()\\s]+)\\s*:");
        for (String play : plays) {
            if (play == null || play.isBlank()) continue;
            java.util.regex.Matcher m1 = p1.matcher(play);
            if (m1.find()) {
                String name = sanitizePlayerName(m1.group(1));
                if (name != null) return name;
            }
            java.util.regex.Matcher m2 = p2.matcher(play);
            if (m2.find()) {
                String name = sanitizePlayerName(m2.group(1));
                if (name != null) return name;
            }
        }
        return null;
    }

    private String extractBatterNameFromResultText(com.baseball.domain.game.PlateAppearance pa) {
        if (pa == null || pa.getResultText() == null || pa.getResultText().isBlank()) return null;
        String text = pa.getResultText().trim();
        // 보통 "전준우 3루수 땅볼" 형태이므로 선두 토큰을 타자명으로 복원
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^([가-힣A-Za-z]{2,})\\s+")
                .matcher(text);
        if (!m.find()) return null;
        return sanitizePlayerName(m.group(1));
    }

    private String resolveBatterNameWithoutOrder(com.baseball.domain.game.PlateAppearance pa,
                                                 Map<Long, String> batterNameById) {
        if (pa == null) return null;
        if (pa.getBatter() != null) {
            String byEntity = sanitizePlayerName(pa.getBatter().getName());
            if (byEntity != null) return byEntity;
        }
        Long batterId = pa.getBatter() != null ? pa.getBatter().getId() : null;
        if (batterId != null && batterNameById != null) {
            String byId = sanitizePlayerName(batterNameById.get(batterId));
            if (byId != null) return byId;
        }
        String direct = sanitizePlayerName(pa.getBatterName());
        if (direct != null) return direct;
        String byResultText = extractBatterNameFromResultText(pa);
        if (byResultText != null) return byResultText;
        return extractBatterNameFromRunnerPlays(pa);
    }

    private String sanitizePlayerName(String raw) {
        if (raw == null) return null;
        String name = raw.trim();
        if (name.isEmpty() || "-".equals(name)) return null;
        // 로그 인코딩 깨짐/제어문자 케이스 방어
        name = name.replaceAll("[\\p{Cntrl}]", "").trim();
        if (name.isEmpty() || "-".equals(name)) return null;
        return name;
    }

    private int cyclicOrder(int order) {
        int normalized = order % 9;
        if (normalized <= 0) normalized += 9;
        return normalized;
    }
    private int countRunsInPa(com.baseball.domain.game.PlateAppearance pa) {
        int runs = countRunEventsInText(pa.getResultText());
        var plays = pa.getRunnerPlaysList();
        if (plays != null) {
            for (String play : plays) {
                runs += countRunEventsInText(play);
            }
        }
        return runs;
    }

    /**
     * 득점 이벤트 카운트:
     * - 우선 '홈인' 횟수 사용
     * - 홈인이 없으면 '득점' 사용(단, '득점권'은 제외)
     */
    private int countRunEventsInText(String text) {
        if (text == null || text.isBlank()) return 0;
        String normalized = text.replace(" ", "");
        int runnerHome = countRegex(normalized, ":홈(?!런)");
        if (runnerHome > 0) return runnerHome;
        int homeIn = countRegex(normalized, "홈\\s*인");
        if (homeIn > 0) return homeIn;
        int homeToScore = countRegex(normalized, "홈으로[^\\n]{0,10}?득점");
        if (homeToScore > 0) return homeToScore;
        int score = countOccurrences(normalized, "득점");
        int inScoringPosition = countOccurrences(normalized, "득점권");
        return Math.max(0, score - inScoringPosition);
    }

    private int countRegex(String text, String regex) {
        if (text == null || text.isBlank()) return 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(regex).matcher(text);
        int c = 0;
        while (m.find()) c++;
        return c;
    }

    /** 투수 교체만 투수 추적에 사용 (야수 교체는 제외) */
    private static boolean isPitcherRoleSubstitution(PitcherSubstitution s) {
        return s != null && s.getKind() == SubstitutionKind.PITCHER;
    }

    private Map<Long, String> buildAttributedPitcherByPaId(List<RecordDisplayItem> recordItems) {
        Map<Long, String> out = new LinkedHashMap<>();
        if (recordItems == null || recordItems.isEmpty()) return out;
        String currentHomePitcher = null;
        String currentAwayPitcher = null;
        String initialHomePitcher = null;
        String initialAwayPitcher = null;
        for (var item : recordItems) {
            if (item == null || item.substitution() == null || item.substitution().getIsTop() == null) continue;
            if (!isPitcherRoleSubstitution(item.substitution())) continue;
            if (Boolean.TRUE.equals(item.substitution().getIsTop())) {
                if (initialHomePitcher == null || initialHomePitcher.isBlank()) initialHomePitcher = item.substitution().getPitcherOutName();
            } else {
                if (initialAwayPitcher == null || initialAwayPitcher.isBlank()) initialAwayPitcher = item.substitution().getPitcherOutName();
            }
        }
        for (var item : recordItems) {
            if (item == null) continue;
            if (item.substitution() != null && item.substitution().getIsTop() != null && isPitcherRoleSubstitution(item.substitution())) {
                if (Boolean.TRUE.equals(item.substitution().getIsTop())) currentHomePitcher = item.substitution().getPitcherInName();
                else currentAwayPitcher = item.substitution().getPitcherInName();
            }
            if (item.plateAppearance() != null && item.plateAppearance().getIsTop() != null) {
                var pa = item.plateAppearance();
                if (Boolean.TRUE.equals(pa.getIsTop()) && (currentHomePitcher == null || currentHomePitcher.isBlank())) {
                    currentHomePitcher = initialHomePitcher;
                }
                if (!Boolean.TRUE.equals(pa.getIsTop()) && (currentAwayPitcher == null || currentAwayPitcher.isBlank())) {
                    currentAwayPitcher = initialAwayPitcher;
                }
                String attributed = Boolean.TRUE.equals(pa.getIsTop()) ? currentHomePitcher : currentAwayPitcher;
                if (pa.getId() != null && attributed != null && !attributed.isBlank()) {
                    out.put(pa.getId(), attributed);
                }
            }
        }
        return out;
    }

    private String resolveAttributedPitcher(com.baseball.domain.game.PlateAppearance pa, Map<Long, String> attributedPitcherByPaId) {
        if (pa != null && pa.getId() != null) {
            String attributed = attributedPitcherByPaId.get(pa.getId());
            if (attributed != null && !attributed.isBlank()) return attributed;
        }
        if (pa != null && pa.getPitcherName() != null && !pa.getPitcherName().isBlank()) return pa.getPitcherName();
        return "-";
    }

    private PitcherCompare buildPitcherCompare(Game game, List<com.baseball.domain.game.PlateAppearance> plateAppearances,
                                               List<RecordDisplayItem> recordItems) {
        String winning = game.getWinningPitcherName();
        String losing = game.getLosingPitcherName();
        PitcherStatLine winningAway = PitcherStatLine.empty();
        PitcherStatLine winningHome = PitcherStatLine.empty();
        PitcherStatLine losingAway = PitcherStatLine.empty();
        PitcherStatLine losingHome = PitcherStatLine.empty();
        if (winning != null && !winning.isBlank()) {
            Boolean homePitcher = isHomePitcher(winning, plateAppearances);
            PitcherStatLine line = buildPitcherStatLine(winning, plateAppearances, recordItems);
            if (Boolean.TRUE.equals(homePitcher)) winningHome = line;
            else if (Boolean.FALSE.equals(homePitcher)) winningAway = line;
            else winningHome = line;
        }
        if (losing != null && !losing.isBlank()) {
            Boolean homePitcher = isHomePitcher(losing, plateAppearances);
            PitcherStatLine line = buildPitcherStatLine(losing, plateAppearances, recordItems);
            if (Boolean.TRUE.equals(homePitcher)) losingHome = line;
            else if (Boolean.FALSE.equals(homePitcher)) losingAway = line;
            else losingAway = line;
        }
        return new PitcherCompare(winningAway, winningHome, losingAway, losingHome);
    }

    /** 초(원정 공격)에서 던졌으면 홈 투수, 말(홈 공격)에서 던졌으면 어웨이 투수 */
    private Boolean isHomePitcher(String pitcherName, List<com.baseball.domain.game.PlateAppearance> plateAppearances) {
        if (pitcherName == null || pitcherName.isBlank() || plateAppearances == null) return null;
        for (var pa : plateAppearances) {
            if (pa == null || pa.getPitcherName() == null || pa.getIsTop() == null) continue;
            if (samePitcherName(pitcherName, pa.getPitcherName())) {
                return Boolean.TRUE.equals(pa.getIsTop());
            }
        }
        return null;
    }

    private String resolvePitcherTeamClass(String pitcherName, List<com.baseball.domain.game.PlateAppearance> plateAppearances,
                                           List<RecordDisplayItem> recordItems) {
        if (pitcherName == null || pitcherName.isBlank()) return "";
        Boolean homePitcher = isHomePitcher(pitcherName, plateAppearances);
        if (homePitcher == null && recordItems != null) {
            String currentHomePitcher = null;
            String currentAwayPitcher = null;
            String initialHomePitcher = null;
            String initialAwayPitcher = null;
            for (var item : recordItems) {
                if (item == null || item.substitution() == null || item.substitution().getIsTop() == null) continue;
                if (!isPitcherRoleSubstitution(item.substitution())) continue;
                if (Boolean.TRUE.equals(item.substitution().getIsTop())) {
                    if (initialHomePitcher == null || initialHomePitcher.isBlank()) initialHomePitcher = item.substitution().getPitcherOutName();
                } else {
                    if (initialAwayPitcher == null || initialAwayPitcher.isBlank()) initialAwayPitcher = item.substitution().getPitcherOutName();
                }
            }
            for (var item : recordItems) {
                if (item == null) continue;
                if (item.substitution() != null && item.substitution().getIsTop() != null && isPitcherRoleSubstitution(item.substitution())) {
                    if (Boolean.TRUE.equals(item.substitution().getIsTop())) currentHomePitcher = item.substitution().getPitcherInName();
                    else currentAwayPitcher = item.substitution().getPitcherInName();
                }
                if (item.plateAppearance() != null && item.plateAppearance().getIsTop() != null) {
                    if (Boolean.TRUE.equals(item.plateAppearance().getIsTop()) && (currentHomePitcher == null || currentHomePitcher.isBlank())) {
                        currentHomePitcher = initialHomePitcher;
                    }
                    if (!Boolean.TRUE.equals(item.plateAppearance().getIsTop()) && (currentAwayPitcher == null || currentAwayPitcher.isBlank())) {
                        currentAwayPitcher = initialAwayPitcher;
                    }
                    String attributed = Boolean.TRUE.equals(item.plateAppearance().getIsTop()) ? currentHomePitcher : currentAwayPitcher;
                    if (samePitcherName(pitcherName, attributed)) {
                        homePitcher = Boolean.TRUE.equals(item.plateAppearance().getIsTop());
                        break;
                    }
                }
            }
        }
        if (Boolean.TRUE.equals(homePitcher)) return "score-pitcher-home";
        if (Boolean.FALSE.equals(homePitcher)) return "score-pitcher-away";
        return "";
    }

    private boolean samePitcherName(String expected, String actual) {
        if (expected == null || actual == null) return false;
        String a = normalizePitcherName(expected);
        String b = normalizePitcherName(actual);
        if (a.isEmpty() || b.isEmpty()) return false;
        return a.equals(b) || a.contains(b) || b.contains(a);
    }

    private String normalizePitcherName(String name) {
        if (name == null) return "";
        String n = name.trim();
        // "로드리게스(W, 3-1)" 같은 접미 정보 제거
        int p = n.indexOf('(');
        if (p > 0) n = n.substring(0, p).trim();
        n = n.replace(" ", "");
        return n;
    }

    private PitcherStatLine buildPitcherStatLine(String pitcherName, List<com.baseball.domain.game.PlateAppearance> plateAppearances,
                                                 List<RecordDisplayItem> recordItems) {
        int outs = 0;
        int strikeouts = 0;
        int walks = 0;
        int hitsAllowed = 0;
        int earnedRuns = 0;
        int matchedPa = 0;
        String currentHomePitcher = null;
        String currentAwayPitcher = null;
        String initialHomePitcher = null;
        String initialAwayPitcher = null;
        for (var item : recordItems) {
            if (item == null || item.substitution() == null) continue;
            var sub = item.substitution();
            if (!isPitcherRoleSubstitution(sub)) continue;
            if (sub.getIsTop() == null) continue;
            if (Boolean.TRUE.equals(sub.getIsTop())) {
                if (initialHomePitcher == null || initialHomePitcher.isBlank()) initialHomePitcher = sub.getPitcherOutName();
            } else {
                if (initialAwayPitcher == null || initialAwayPitcher.isBlank()) initialAwayPitcher = sub.getPitcherOutName();
            }
        }
        for (var item : recordItems) {
            if (item == null) continue;
            if (item.substitution() != null) {
                var sub = item.substitution();
                if (isPitcherRoleSubstitution(sub) && sub.getIsTop() != null) {
                    if (Boolean.TRUE.equals(sub.getIsTop())) currentHomePitcher = sub.getPitcherInName();
                    else currentAwayPitcher = sub.getPitcherInName();
                }
            }
            if (item.plateAppearance() != null) {
                var pa = item.plateAppearance();
                String attributed = null;
                if (pa.getIsTop() != null) {
                    if (Boolean.TRUE.equals(pa.getIsTop()) && (currentHomePitcher == null || currentHomePitcher.isBlank())) {
                        currentHomePitcher = initialHomePitcher;
                    }
                    if (!Boolean.TRUE.equals(pa.getIsTop()) && (currentAwayPitcher == null || currentAwayPitcher.isBlank())) {
                        currentAwayPitcher = initialAwayPitcher;
                    }
                    attributed = Boolean.TRUE.equals(pa.getIsTop()) ? currentHomePitcher : currentAwayPitcher;
                }
                if ((attributed == null || attributed.isBlank()) && pa.getPitcherName() != null) {
                    attributed = pa.getPitcherName();
                }
                boolean matchesAttributed = samePitcherName(pitcherName, attributed);
                boolean matchesRawPitcher = samePitcherName(pitcherName, pa.getPitcherName());
                if (!matchesAttributed && !matchesRawPitcher) continue;
                matchedPa++;
                String result = pa.getResultText() != null ? pa.getResultText() : "";
                int resultOuts = estimateOutsFromText(result);
                int runnerOuts = 0;
                if (result.contains("삼진")) strikeouts++;
                if (isWalkLikeResult(result)) walks++;
                if (isHitResult(result)) hitsAllowed++;
                earnedRuns += countOccurrences(result, "홈인");
                var plays = pa.getRunnerPlaysList();
                if (plays != null) {
                    for (String play : plays) {
                        if (play == null) continue;
                        runnerOuts += estimateOutsFromText(play);
                        earnedRuns += countOccurrences(play, "홈인");
                    }
                }
                int paOuts = resultOuts + runnerOuts;
                // 결과 줄과 주자 줄이 같은 아웃 이벤트를 중복 서술하는 경우가 많아 단일 아웃 플레이는 중복 제거
                if (resultOuts > 0 && runnerOuts > 0 && !result.contains("병살") && !result.contains("삼중살")) {
                    paOuts = Math.max(resultOuts, runnerOuts);
                }
                outs += Math.min(3, Math.max(0, paOuts));
            }
        }
        if (matchedPa == 0) {
        if (plateAppearances != null) {
            for (var pa : plateAppearances) {
                if (pa == null || pa.getPitcherName() == null) continue;
                if (!samePitcherName(pitcherName, pa.getPitcherName())) continue;
                matchedPa++;
                String result = pa.getResultText() != null ? pa.getResultText() : "";
                outs += countOccurrences(result, "아웃");
                if (result.contains("삼진")) strikeouts++;
                if (isWalkLikeResult(result)) walks++;
                earnedRuns += countOccurrences(result, "홈인");
                var plays = pa.getRunnerPlaysList();
                if (plays != null) {
                    for (String play : plays) {
                        if (play == null) continue;
                        outs += countOccurrences(play, "아웃");
                        earnedRuns += countOccurrences(play, "홈인");
                    }
                }
            }
        }
        }
        if (matchedPa == 0) {
            return new PitcherStatLine(pitcherName, "기록 없음");
        }
        String ip = formatInningsPitched(outs);
        String summary = ip + "이닝 / 피안타 " + hitsAllowed + " / 삼진 " + strikeouts + " / 볼넷 " + walks + " / 자책 " + earnedRuns;
        return new PitcherStatLine(pitcherName, summary);
    }

    private String formatInningsPitched(int outs) {
        int whole = Math.max(0, outs) / 3;
        int rem = Math.max(0, outs) % 3;
        return whole + "." + rem;
    }

    private boolean isWalkLikeResult(String result) {
        if (result == null || result.isBlank()) return false;
        return result.contains("볼넷")
                || result.contains("고의4구")
                || result.contains("고의 4구")
                || result.contains("자동 고의4구")
                || result.contains("자동고의4구")
                || result.contains("몸에 맞는 볼")
                || result.contains("몸에 맞는볼")
                || result.contains("사구")
                || result.contains("데드볼");
    }

    private int countOccurrences(String text, String needle) {
        if (text == null || needle == null || needle.isEmpty()) return 0;
        int c = 0, i = 0;
        while ((i = text.indexOf(needle, i)) != -1) {
            c++;
            i += needle.length();
        }
        return c;
    }

    private int estimateOutsFromText(String text) {
        if (text == null || text.isBlank()) return 0;
        if (text.contains("삼중살")) return 3;
        if (text.contains("병살")) return 2;
        if (text.contains("아웃")) return 1;
        return 0;
    }

    private boolean isBaserunningOutEvent(String text) {
        if (text == null || text.isBlank()) return false;
        return text.contains("주루사")
                || text.contains("견제사")
                || text.contains("견제 아웃")
                || text.contains("도루 실패")
                || text.contains("도루실패")
                || (text.contains("태그아웃") && text.contains("루주자"));
    }

    private boolean isStealSuccessEvent(String text) {
        if (text == null || text.isBlank()) return false;
        return text.contains("도루") && !text.contains("도루 실패") && !text.contains("도루실패");
    }

    private String extractRunnerNameFromPlay(String play) {
        if (play == null || play.isBlank()) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^[123]루주자\\s+([^:]+?)\\s*:")
                .matcher(play.trim());
        if (!m.find()) return null;
        String name = m.group(1) != null ? m.group(1).trim() : null;
        return (name == null || name.isBlank()) ? null : name;
    }

    private List<BatterDetailLine> buildBatterDetailLines(List<com.baseball.domain.game.PlateAppearance> plateAppearances, boolean awayOffense) {
        Map<String, BatterAccumulator> statsByKey = new LinkedHashMap<>();
        Map<String, String> displayNameByKey = new LinkedHashMap<>();
        Map<String, String> roleByKey = new LinkedHashMap<>();
        Map<String, Integer> batterOrderByKey = new LinkedHashMap<>();
        Map<String, Integer> firstSeenIndexByKey = new LinkedHashMap<>();
        Map<Integer, LinkedHashSet<String>> keysByOrder = new LinkedHashMap<>();
        LinkedHashSet<String> keysWithoutOrder = new LinkedHashSet<>();
        Map<Long, String> batterNameById = buildBatterNameById(plateAppearances);
        Map<Long, Integer> batterOrderById = new LinkedHashMap<>();
        if (plateAppearances == null) return List.of();
        for (var pa : plateAppearances) {
            if (pa == null || pa.getBatter() == null || pa.getBatter().getId() == null || pa.getBatterOrder() == null) continue;
            batterOrderById.putIfAbsent(pa.getBatter().getId(), pa.getBatterOrder());
        }
        List<com.baseball.domain.game.PlateAppearance> ordered = new ArrayList<>(plateAppearances);
        ordered.sort((a, b) -> {
            int ai = a != null && a.getInning() != null ? a.getInning() : 0;
            int bi = b != null && b.getInning() != null ? b.getInning() : 0;
            if (ai != bi) return Integer.compare(ai, bi);
            boolean at = a != null && Boolean.TRUE.equals(a.getIsTop());
            boolean bt = b != null && Boolean.TRUE.equals(b.getIsTop());
            if (at != bt) return at ? -1 : 1;
            int as = a != null && a.getSequenceOrder() != null ? a.getSequenceOrder() : 0;
            int bs = b != null && b.getSequenceOrder() != null ? b.getSequenceOrder() : 0;
            if (as != bs) return Integer.compare(as, bs);
            long aid = a != null && a.getId() != null ? a.getId() : 0L;
            long bid = b != null && b.getId() != null ? b.getId() : 0L;
            return Long.compare(aid, bid);
        });
        int seenIndex = 0;
        for (var pa : ordered) {
            if (pa == null || pa.getIsTop() == null) continue;
            if (Boolean.TRUE.equals(pa.getIsTop()) != awayOffense) continue;
            String name = null;
            Long batterId = (pa.getBatter() != null ? pa.getBatter().getId() : null);
            if (batterId != null) {
                name = sanitizePlayerName(batterNameById.get(batterId));
            }
            if (name == null) {
                name = resolveBatterNameWithoutOrder(pa, batterNameById);
            }
            if (name == null) name = "-";
            String batterKey = batterId != null ? "ID:" + batterId : "NM:" + name;
            displayNameByKey.putIfAbsent(batterKey, name);
            firstSeenIndexByKey.putIfAbsent(batterKey, seenIndex++);
            BatterAccumulator acc = statsByKey.computeIfAbsent(batterKey, k -> new BatterAccumulator());
            Integer order = pa.getBatterOrder();
            if (order == null && batterId != null) {
                order = batterOrderById.get(batterId);
            }
            if (order != null) {
                keysByOrder.computeIfAbsent(order, k -> new LinkedHashSet<>()).add(batterKey);
                batterOrderByKey.putIfAbsent(batterKey, order);
            } else {
                keysWithoutOrder.add(batterKey);
            }
            roleByKey.putIfAbsent(batterKey, buildBatterRoleLabel(pa, order));
            String result = pa.getResultText() != null ? pa.getResultText() : "";
            int runsInPa = countRunsInPa(pa);

            if (isOfficialAtBatResult(result)) acc.atBats++;
            if (isHitResult(result)) {
                acc.hits++;
                if (result.contains("홈런")) acc.homeRuns++;
            }
            if (isWalkLikeResult(result)) acc.walks++;
            if (result.contains("삼진")) acc.strikeouts++;
            acc.rbis += runsInPa;

            var plays = pa.getRunnerPlaysList();
            if (plays != null) {
                for (String play : plays) {
                    if (play == null) continue;
                    String runner = extractRunnerNameFromPlay(play);
                    if (runner != null && runner.equals(name) && (play.contains("홈인") || play.contains(":홈"))) {
                        acc.runs++;
                    }
                    if (runner != null && runner.equals(name) && isStealSuccessEvent(play)) {
                        acc.steals++;
                    }
                }
            }
            if (result.contains("홈런")) {
                acc.runs++;
            }
        }

        // 타순별로 "경기 등장 순서" 그대로 정렬:
        // 주전(첫 등장) -> 교체1 -> 교체2 ...
        inferUnknownSubstitutionRoles(ordered, awayOffense, displayNameByKey, roleByKey, batterOrderByKey, firstSeenIndexByKey);
        normalizeRoleLabelsByOrder(keysByOrder, roleByKey);
        List<String> orderedKeys = new ArrayList<>();
        LinkedHashSet<String> emitted = new LinkedHashSet<>();
        for (int order = 1; order <= 9; order++) {
            var keys = keysByOrder.get(order);
            if (keys == null || keys.isEmpty()) continue;
            for (String key : keys) {
                if (key == null) continue;
                if (emitted.add(key)) orderedKeys.add(key);
            }
        }
        // 타순 미확인 선수는 뒤로
        for (String key : keysWithoutOrder) {
            if (key == null) continue;
            if (emitted.add(key)) orderedKeys.add(key);
        }
        // 혹시 누락된 키가 있으면 마지막에 보강
        for (String key : statsByKey.keySet()) {
            if (emitted.add(key)) orderedKeys.add(key);
        }

        List<BatterDetailLine> out = new ArrayList<>();
        for (String key : orderedKeys) {
            BatterAccumulator a = statsByKey.get(key);
            if (a == null) continue;
            String name = displayNameByKey.getOrDefault(key, "-");
            String role = roleByKey.getOrDefault(key, "-");
            String avg = a.atBats > 0
                    ? String.format(Locale.ROOT, "%.3f", (double) a.hits / (double) a.atBats)
                    : ".000";
            out.add(new BatterDetailLine(name, role, a.atBats, a.runs, a.hits, a.rbis, a.homeRuns, a.walks, a.strikeouts, a.steals, avg));
        }
        return out;
    }

    private List<PitcherDetailLine> buildPitcherDetailLines(Game game,
                                                             List<com.baseball.domain.game.PlateAppearance> plateAppearances,
                                                             List<RecordDisplayItem> recordItems,
                                                             boolean homePitcher) {
        Map<String, PitcherAccumulator> byName = new LinkedHashMap<>();
        if (plateAppearances == null) return List.of();
        Map<Long, String> attributedPitcherByPaId = buildAttributedPitcherByPaId(recordItems);
        Map<String, Integer> outsUsedByHalf = new LinkedHashMap<>();

        for (var pa : plateAppearances) {
            if (pa == null || pa.getIsTop() == null) continue;
            // 원정 공격(초)이면 홈 투수, 홈 공격(말)이면 어웨이 투수
            boolean thisIsHomePitcher = Boolean.TRUE.equals(pa.getIsTop());
            if (thisIsHomePitcher != homePitcher) continue;

            String name = sanitizePlayerName(resolveAttributedPitcher(pa, attributedPitcherByPaId));
            if (name == null) name = sanitizePlayerName(pa.getPitcherName());
            if (name == null) name = "-";

            PitcherAccumulator acc = byName.computeIfAbsent(name, k -> new PitcherAccumulator());
            String result = pa.getResultText() != null ? pa.getResultText() : "";
            int runsInPa = countRunsInPa(pa);

            acc.games = 1;
            acc.battersFaced++;
            acc.pitches += (pa.getPitches() != null ? pa.getPitches().size() : 0);
            if (isOfficialAtBatResult(result)) acc.atBats++;
            if (isHitResult(result)) {
                acc.hitsAllowed++;
                if (result.contains("홈런")) acc.homeRunsAllowed++;
            }
            if (isWalkLikeResult(result)) acc.walks++;
            if (result.contains("삼진")) acc.strikeouts++;
            acc.runsAllowed += runsInPa;
            acc.earnedRuns += paHasError(pa) ? 0 : runsInPa;

            int resultOuts = estimateOutsFromText(result);
            int runnerOuts = 0;
            var plays = pa.getRunnerPlaysList();
            if (plays != null) {
                for (String play : plays) {
                    if (play == null) continue;
                    runnerOuts += estimateOutsFromText(play);
                }
            }
            int paOuts = resultOuts + runnerOuts;
            if (resultOuts > 0 && runnerOuts > 0 && !result.contains("병살") && !result.contains("삼중살")) {
                paOuts = Math.max(resultOuts, runnerOuts);
            }
            int safeOuts = Math.min(3, Math.max(0, paOuts));
            if (pa.getInning() != null) {
                String halfKey = pa.getInning() + "_" + Boolean.TRUE.equals(pa.getIsTop());
                int used = outsUsedByHalf.getOrDefault(halfKey, 0);
                int remaining = Math.max(0, 3 - used);
                int credited = Math.min(remaining, safeOuts);
                acc.outs += credited;
                outsUsedByHalf.put(halfKey, used + credited);
            } else {
                acc.outs += safeOuts;
            }
        }

        List<PitcherDetailLine> out = new ArrayList<>();
        for (var e : byName.entrySet()) {
            String name = e.getKey();
            PitcherAccumulator a = e.getValue();
            int wins = samePitcherName(game.getWinningPitcherName(), name) ? 1 : 0;
            int losses = samePitcherName(game.getLosingPitcherName(), name) ? 1 : 0;
            out.add(new PitcherDetailLine(
                    name, formatInningsPitched(a.outs), a.hitsAllowed, a.runsAllowed, a.earnedRuns,
                    a.walks, a.strikeouts, a.homeRunsAllowed, a.battersFaced, a.atBats, a.pitches,
                    a.games, wins, losses
            ));
        }
        return out;
    }

    private List<BatterBoardLine> buildBatterBoardLines(List<com.baseball.domain.game.PlateAppearance> plateAppearances, boolean awayOffense) {
        if (plateAppearances == null) return List.of();
        Map<String, String> displayNameByKey = new LinkedHashMap<>();
        Map<String, String> roleByKey = new LinkedHashMap<>();
        Map<String, Integer> batterOrderByKey = new LinkedHashMap<>();
        Map<Integer, LinkedHashSet<String>> keysByOrder = new LinkedHashMap<>();
        LinkedHashSet<String> keysWithoutOrder = new LinkedHashSet<>();
        Map<String, List<List<String>>> marksByKey = new LinkedHashMap<>();
        Map<Long, String> batterNameById = buildBatterNameById(plateAppearances);
        Map<Long, Integer> batterOrderById = new LinkedHashMap<>();
        for (var pa : plateAppearances) {
            if (pa == null || pa.getBatter() == null || pa.getBatter().getId() == null || pa.getBatterOrder() == null) continue;
            batterOrderById.putIfAbsent(pa.getBatter().getId(), pa.getBatterOrder());
        }

        List<com.baseball.domain.game.PlateAppearance> ordered = new ArrayList<>(plateAppearances);
        ordered.sort((a, b) -> {
            int ai = a != null && a.getInning() != null ? a.getInning() : 0;
            int bi = b != null && b.getInning() != null ? b.getInning() : 0;
            if (ai != bi) return Integer.compare(ai, bi);
            boolean at = a != null && Boolean.TRUE.equals(a.getIsTop());
            boolean bt = b != null && Boolean.TRUE.equals(b.getIsTop());
            if (at != bt) return at ? -1 : 1;
            int as = a != null && a.getSequenceOrder() != null ? a.getSequenceOrder() : 0;
            int bs = b != null && b.getSequenceOrder() != null ? b.getSequenceOrder() : 0;
            if (as != bs) return Integer.compare(as, bs);
            long aid = a != null && a.getId() != null ? a.getId() : 0L;
            long bid = b != null && b.getId() != null ? b.getId() : 0L;
            return Long.compare(aid, bid);
        });

        for (var pa : ordered) {
            if (pa == null || pa.getIsTop() == null) continue;
            if (Boolean.TRUE.equals(pa.getIsTop()) != awayOffense) continue;
            String name = null;
            Long batterId = (pa.getBatter() != null ? pa.getBatter().getId() : null);
            if (batterId != null) {
                name = sanitizePlayerName(batterNameById.get(batterId));
            }
            if (name == null) name = resolveBatterNameWithoutOrder(pa, batterNameById);
            if (name == null) name = "-";
            String batterKey = batterId != null ? "ID:" + batterId : "NM:" + name;
            displayNameByKey.putIfAbsent(batterKey, name);

            Integer order = pa.getBatterOrder();
            if (order == null && batterId != null) order = batterOrderById.get(batterId);
            if (order != null) {
                keysByOrder.computeIfAbsent(order, k -> new LinkedHashSet<>()).add(batterKey);
                batterOrderByKey.putIfAbsent(batterKey, order);
            } else {
                keysWithoutOrder.add(batterKey);
            }
            roleByKey.putIfAbsent(batterKey, buildBatterRoleLabel(pa, order));

            int inning = pa.getInning() != null ? pa.getInning() : 0;
            if (inning < 1 || inning > 9) continue;
            List<List<String>> byInning = marksByKey.computeIfAbsent(batterKey, k -> {
                List<List<String>> arr = new ArrayList<>();
                for (int i = 0; i <= 9; i++) arr.add(new ArrayList<>());
                return arr;
            });
            String mark = toBoardMark(pa.getResultText());
            byInning.get(inning).add(mark);
        }

        List<String> orderedKeys = new ArrayList<>();
        Map<String, Integer> firstSeenIndexByKey = new LinkedHashMap<>();
        int seq = 0;
        for (var pa : ordered) {
            if (pa == null || pa.getIsTop() == null) continue;
            if (Boolean.TRUE.equals(pa.getIsTop()) != awayOffense) continue;
            String name = null;
            Long batterId = (pa.getBatter() != null ? pa.getBatter().getId() : null);
            if (batterId != null) name = sanitizePlayerName(batterNameById.get(batterId));
            if (name == null) name = resolveBatterNameWithoutOrder(pa, batterNameById);
            if (name == null) name = "-";
            String batterKey = batterId != null ? "ID:" + batterId : "NM:" + name;
            firstSeenIndexByKey.putIfAbsent(batterKey, seq++);
        }
        inferUnknownSubstitutionRoles(ordered, awayOffense, displayNameByKey, roleByKey, batterOrderByKey, firstSeenIndexByKey);
        normalizeRoleLabelsByOrder(keysByOrder, roleByKey);
        LinkedHashSet<String> emitted = new LinkedHashSet<>();
        for (int order = 1; order <= 9; order++) {
            var keys = keysByOrder.get(order);
            if (keys == null || keys.isEmpty()) continue;
            for (String key : keys) {
                if (key == null) continue;
                if (emitted.add(key)) orderedKeys.add(key);
            }
        }
        for (String key : keysWithoutOrder) {
            if (key == null) continue;
            if (emitted.add(key)) orderedKeys.add(key);
        }
        for (String key : displayNameByKey.keySet()) {
            if (emitted.add(key)) orderedKeys.add(key);
        }

        List<BatterBoardLine> out = new ArrayList<>();
        for (String key : orderedKeys) {
            List<List<String>> byInning = marksByKey.get(key);
            if (byInning == null) {
                byInning = new ArrayList<>();
                for (int i = 0; i <= 9; i++) byInning.add(new ArrayList<>());
            }
            List<String> inningMarks = new ArrayList<>();
            inningMarks.add("");
            for (int inning = 1; inning <= 9; inning++) {
                List<String> marks = byInning.get(inning);
                inningMarks.add((marks == null || marks.isEmpty()) ? "" : String.join(" ", marks));
            }
            out.add(new BatterBoardLine(displayNameByKey.getOrDefault(key, "-"), roleByKey.getOrDefault(key, "-"), inningMarks));
        }
        return out;
    }

    private String buildBatterRoleLabel(com.baseball.domain.game.PlateAppearance pa, Integer order) {
        if (pa == null) return "-";
        if (Boolean.TRUE.equals(pa.getBatterIsStarter())) {
            return order != null ? String.valueOf(order) : "주전";
        }
        BatterSubstitutionType subType = pa.getBatterSubstitutionType();
        if (subType == BatterSubstitutionType.PINCH_HITTER) return "대타";
        if (subType == BatterSubstitutionType.PINCH_RUNNER) return "대주자";
        if (Boolean.FALSE.equals(pa.getBatterIsStarter())) return "교체";
        return order != null ? String.valueOf(order) : "-";
    }

    /**
     * 과거 데이터처럼 교체유형이 null인 경우 화면 표시용 추론:
     * - 첫 등장 직전 플레이에 해당 이름이 '루주자'로 보이면 대주자
     * - 아니면 같은 타순에서 앞선 선수가 이미 있으면 대타
     * - 그래도 불명확하면 교체
     */
    private void inferUnknownSubstitutionRoles(List<com.baseball.domain.game.PlateAppearance> ordered,
                                               boolean awayOffense,
                                               Map<String, String> displayNameByKey,
                                               Map<String, String> roleByKey,
                                               Map<String, Integer> batterOrderByKey,
                                               Map<String, Integer> firstSeenIndexByKey) {
        if (ordered == null || ordered.isEmpty() || roleByKey == null || roleByKey.isEmpty()) return;
        for (Map.Entry<String, String> e : roleByKey.entrySet()) {
            String key = e.getKey();
            String role = e.getValue();
            if (key == null) continue;
            if ("대타".equals(role) || "대주자".equals(role)) continue;
            String name = displayNameByKey.getOrDefault(key, "");
            Integer firstSeen = firstSeenIndexByKey.get(key);
            if (firstSeen == null) continue;
            Integer order = batterOrderByKey.get(key);

            boolean hasPreviousInSameOrder = order != null
                    && hasPreviousDifferentBatterInSameOrder(batterOrderByKey, firstSeenIndexByKey, key, order, firstSeen);

            // 같은 타순 이전 선수가 없으면 주전으로 간주(숫자 유지)
            if (!hasPreviousInSameOrder) continue;

            if (appearedAsRunnerBeforeAtBat(ordered, awayOffense, name, firstSeen)) {
                roleByKey.put(key, "대주자");
                continue;
            }
            roleByKey.put(key, "대타");
        }
    }

    private boolean appearedAsRunnerBeforeAtBat(List<com.baseball.domain.game.PlateAppearance> ordered,
                                                boolean awayOffense,
                                                String name,
                                                int firstSeenIndex) {
        if (name == null || name.isBlank()) return false;
        int offenseIdx = -1;
        for (var pa : ordered) {
            if (pa == null || pa.getIsTop() == null) continue;
            if (Boolean.TRUE.equals(pa.getIsTop()) != awayOffense) continue;
            offenseIdx++;
            if (offenseIdx >= firstSeenIndex) break;
            var plays = pa.getRunnerPlaysList();
            if (plays == null) continue;
            for (String play : plays) {
                if (play == null) continue;
                if (play.contains("루주자 " + name) || play.contains("루주자:" + name) || play.contains("루주자 " + name + " :")) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasPreviousDifferentBatterInSameOrder(Map<String, Integer> batterOrderByKey,
                                                           Map<String, Integer> firstSeenIndexByKey,
                                                           String selfKey,
                                                           int order,
                                                           int selfFirstSeen) {
        for (Map.Entry<String, Integer> e : batterOrderByKey.entrySet()) {
            String k = e.getKey();
            Integer o = e.getValue();
            if (k == null || o == null) continue;
            if (k.equals(selfKey)) continue;
            if (o != order) continue;
            Integer seen = firstSeenIndexByKey.get(k);
            if (seen != null && seen < selfFirstSeen) return true;
        }
        return false;
    }

    /**
     * 구경기/누락 데이터에서 교체유형 플래그가 비어 있더라도
     * 같은 타순의 첫 선수만 숫자(주전), 이후 선수는 최소 "교체"로 보정한다.
     */
    private void normalizeRoleLabelsByOrder(Map<Integer, LinkedHashSet<String>> keysByOrder, Map<String, String> roleByKey) {
        if (keysByOrder == null || keysByOrder.isEmpty() || roleByKey == null) return;
        for (Map.Entry<Integer, LinkedHashSet<String>> e : keysByOrder.entrySet()) {
            Integer order = e.getKey();
            LinkedHashSet<String> keys = e.getValue();
            if (order == null || keys == null || keys.isEmpty()) continue;
            boolean first = true;
            String starterNumber = String.valueOf(order);
            for (String key : keys) {
                if (key == null) continue;
                if (first) {
                    first = false;
                    continue;
                }
                String role = roleByKey.get(key);
                if (role == null || role.isBlank() || starterNumber.equals(role)) {
                    roleByKey.put(key, "교체");
                }
            }
        }
    }

    private String toBoardMark(String result) {
        if (result == null || result.isBlank()) return "";
        String t = result.trim();
        if ((t.contains("낫 아웃") || t.contains("낫아웃"))
                && (t.contains("1루 터치아웃") || t.contains("1루 터치 아웃"))) {
            return "낫1";
        }
        if (t.contains("삼진")) return "삼진";
        if (t.contains("볼넷") || t.contains("고의4구") || t.contains("고의 4구")) return "4구";
        if (t.contains("몸에 맞는 볼") || t.contains("사구") || t.contains("데드볼")) return "사구";
        if (t.contains("홈런")) return "홈";
        if (t.contains("라인드라이브")) {
            if (t.contains("유격수")) return "유직";
            if (t.contains("3루수")) return "3직";
            if (t.contains("2루수")) return "2직";
            if (t.contains("1루수")) return "1직";
            if (t.contains("투수")) return "투직";
            if (t.contains("좌익수")) return "좌직";
            if (t.contains("중견수")) return "중직";
            if (t.contains("우익수")) return "우직";
            return "직";
        }
        if (t.contains("좌익수") && t.contains("3루타")) return "좌3";
        if (t.contains("우익수") && t.contains("3루타")) return "우3";
        if (t.contains("중견수") && t.contains("3루타")) return "중3";
        if (t.contains("좌익수") && t.contains("2루타")) return "좌2";
        if (t.contains("우익수") && t.contains("2루타")) return "우2";
        if (t.contains("중견수") && t.contains("2루타")) return "중2";
        if (t.contains("좌익수") && (t.contains("1루타") || t.contains("내야안타") || t.contains("번트안타"))) return "좌1";
        if (t.contains("우익수") && (t.contains("1루타") || t.contains("내야안타") || t.contains("번트안타"))) return "우1";
        if (t.contains("중견수") && (t.contains("1루타") || t.contains("내야안타") || t.contains("번트안타"))) return "중1";
        if (t.contains("3루타")) return "3타";
        if (t.contains("2루타")) return "2타";
        if (t.contains("1루타") || t.contains("내야안타") || t.contains("번트안타")) return "1타";
        if (t.contains("병살")) return "병살";
        if (t.contains("희생플라이") || t.contains("희비")) return "희비";
        if (t.contains("희생번트") || t.contains("희타")) return "희타";
        if (t.contains("중견수") && t.contains("플라이")) return "중플";
        if (t.contains("좌익수") && t.contains("플라이")) return "좌플";
        if (t.contains("우익수") && t.contains("플라이")) return "우플";
        if (t.contains("3루수") && t.contains("플라이")) return "3플";
        if (t.contains("2루수") && t.contains("플라이")) return "2플";
        if (t.contains("1루수") && t.contains("플라이")) return "1플";
        if (t.contains("투수") && t.contains("플라이")) return "투플";
        if (t.contains("포수") && t.contains("플라이")) return "포플";
        if (t.contains("유격수") && t.contains("땅볼")) return "유땅";
        if (t.contains("유격수")) return "유비";
        if (t.contains("2루수") && t.contains("땅볼")) return "2땅";
        if (t.contains("2루수")) return "2비";
        if (t.contains("3루수") && t.contains("땅볼")) return "3땅";
        if (t.contains("3루수")) return "3비";
        if (t.contains("1루수") && t.contains("땅볼")) return "1땅";
        if (t.contains("1루수")) return "1비";
        if (t.contains("투수") && t.contains("땅볼")) return "투땅";
        if (t.contains("투수")) return "투비";
        if (t.contains("포수") && t.contains("파울플라이")) return "포플";
        if (t.contains("포수")) return "포플";
        if (t.contains("좌익수") && t.contains("2루타")) return "좌2";
        if (t.contains("우익수") && t.contains("2루타")) return "우2";
        if (t.contains("중견수") && t.contains("2루타")) return "중2";
        if (t.contains("아웃")) return "아웃";
        return t.length() > 4 ? t.substring(0, 4) : t;
    }

    private boolean isOfficialAtBatResult(String result) {
        if (result == null || result.isBlank()) return false;
        if (isWalkLikeResult(result)) return false;
        return !result.contains("희생번트")
                && !result.contains("희생플라이")
                && !result.contains("희타")
                && !result.contains("희비")
                && !result.contains("타격방해");
    }

    private boolean paHasError(com.baseball.domain.game.PlateAppearance pa) {
        if (pa == null) return false;
        String result = pa.getResultText();
        if (result != null && result.contains("실책") && !result.contains("무실책")) return true;
        var plays = pa.getRunnerPlaysList();
        if (plays == null) return false;
        for (String play : plays) {
            if (play != null && play.contains("실책") && !play.contains("무실책")) return true;
        }
        return false;
    }

    private record TeamStats(int hits, int homeRuns, int steals, int strikeouts, int doublePlays, int errors) {}
    private record StatCompareRow(String label, int awayValue, int homeValue, int maxValue) {}
    private record EventDetailRow(String label, int awayCount, String awayNames, int homeCount, String homeNames) {}
    private record GameWinningHit(boolean awayOffense, String batterName, int inning) {}
    private static class EventAccumulator {
        private int count;
        private final LinkedHashMap<String, LinkedHashSet<Integer>> byName = new LinkedHashMap<>();

        void add(String name, int inning) {
            count++;
            String key = (name == null || name.isBlank() || "-".equals(name)) ? null : name.trim();
            if (key == null) return;
            byName.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(inning);
        }

        int count() {
            return count;
        }

        String format() {
            if (byName.isEmpty()) return "-";
            List<String> parts = new ArrayList<>();
            for (var e : byName.entrySet()) {
                List<String> innings = e.getValue().stream()
                        .filter(i -> i != null && i > 0)
                        .map(i -> i + "회")
                        .toList();
                if (innings.isEmpty()) parts.add(e.getKey());
                else parts.add(e.getKey() + "(" + String.join(", ", innings) + ")");
            }
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < parts.size(); i++) {
                if (i > 0) {
                    if (i % 3 == 0) out.append('\n');
                    else out.append(", ");
                }
                out.append(parts.get(i));
            }
            return out.toString();
        }
    }
    private record PitcherStatLine(String name, String summary) {
        static PitcherStatLine empty() { return new PitcherStatLine("-", "-"); }
    }
    private record PitcherCompare(PitcherStatLine winningAway, PitcherStatLine winningHome,
                                  PitcherStatLine losingAway, PitcherStatLine losingHome) {}
    private static class BatterAccumulator {
        int atBats;
        int runs;
        int hits;
        int rbis;
        int homeRuns;
        int walks;
        int strikeouts;
        int steals;
    }
    private static class PitcherAccumulator {
        int outs;
        int hitsAllowed;
        int runsAllowed;
        int earnedRuns;
        int walks;
        int strikeouts;
        int homeRunsAllowed;
        int battersFaced;
        int atBats;
        int pitches;
        int games;
    }
    private record BatterDetailLine(String name, String role, int atBats, int runs, int hits, int rbis, int homeRuns,
                                    int walks, int strikeouts, int steals, String avg) {}
    private record BatterBoardLine(String name, String role, List<String> inningMarks) {}
    private record PitcherDetailLine(String name, String innings, int hitsAllowed, int runsAllowed, int earnedRuns,
                                     int walks, int strikeouts, int homeRunsAllowed, int battersFaced, int atBats,
                                     int pitches, int games, int wins, int losses) {}

    private String resolvePitcherSummaryWithFallback(PitcherStatLine line,
                                                     List<PitcherDetailLine> awayPitcherDetails,
                                                     List<PitcherDetailLine> homePitcherDetails) {
        if (line == null) return "-";
        String name = line.name();
        if (name == null || name.isBlank() || "-".equals(name)) return line.summary();
        if (awayPitcherDetails != null) {
            for (PitcherDetailLine row : awayPitcherDetails) {
                if (row == null) continue;
                if (samePitcherName(name, row.name())) {
                    return formatPitcherSummaryFromDetail(row);
                }
            }
        }
        if (homePitcherDetails != null) {
            for (PitcherDetailLine row : homePitcherDetails) {
                if (row == null) continue;
                if (samePitcherName(name, row.name())) {
                    return formatPitcherSummaryFromDetail(row);
                }
            }
        }
        // 상세 표에도 없을 때만 기존 요약 문자열 사용
        return line.summary() != null ? line.summary() : "기록 없음";
    }

    private PitcherStatLine resolvePitcherLineFromDetailsFirst(PitcherStatLine line,
                                                               List<PitcherDetailLine> awayPitcherDetails,
                                                               List<PitcherDetailLine> homePitcherDetails) {
        if (line == null) return PitcherStatLine.empty();
        String name = line.name();
        if (name == null || name.isBlank() || "-".equals(name)) return line;
        PitcherDetailLine detail = findPitcherDetailByName(name, awayPitcherDetails);
        if (detail == null) detail = findPitcherDetailByName(name, homePitcherDetails);
        if (detail != null) {
            return new PitcherStatLine(detail.name(), formatPitcherSummaryFromDetail(detail));
        }
        return line;
    }

    private PitcherDetailLine findPitcherDetailByName(String name, List<PitcherDetailLine> rows) {
        if (name == null || name.isBlank() || rows == null || rows.isEmpty()) return null;
        for (PitcherDetailLine row : rows) {
            if (row == null) continue;
            if (samePitcherName(name, row.name())) return row;
        }
        return null;
    }

    private String formatPitcherSummaryFromDetail(PitcherDetailLine row) {
        if (row == null) return "기록 없음";
        return row.innings() + "이닝 / 피안타 " + row.hitsAllowed()
                + " / 삼진 " + row.strikeouts()
                + " / 볼넷 " + row.walks()
                + " / 자책 " + row.earnedRuns();
    }

    private BatterDetailLine buildBatterTotalLine(List<BatterDetailLine> rows) {
        if (rows == null || rows.isEmpty()) return null;
        int atBats = 0, runs = 0, hits = 0, rbis = 0, homeRuns = 0, walks = 0, strikeouts = 0, steals = 0;
        for (BatterDetailLine row : rows) {
            if (row == null) continue;
            atBats += row.atBats();
            runs += row.runs();
            hits += row.hits();
            rbis += row.rbis();
            homeRuns += row.homeRuns();
            walks += row.walks();
            strikeouts += row.strikeouts();
            steals += row.steals();
        }
        String avg = atBats > 0
                ? String.format(Locale.ROOT, "%.3f", (double) hits / (double) atBats)
                : ".000";
        return new BatterDetailLine("-", "합계", atBats, runs, hits, rbis, homeRuns, walks, strikeouts, steals, avg);
    }

    private PitcherDetailLine buildPitcherTotalLine(List<PitcherDetailLine> rows) {
        if (rows == null || rows.isEmpty()) return null;
        int outs = 0, hitsAllowed = 0, runsAllowed = 0, earnedRuns = 0, walks = 0, strikeouts = 0;
        int homeRunsAllowed = 0, battersFaced = 0, atBats = 0, pitches = 0, games = 0, wins = 0, losses = 0;
        for (PitcherDetailLine row : rows) {
            if (row == null) continue;
            outs += parseInningsToOuts(row.innings());
            hitsAllowed += row.hitsAllowed();
            runsAllowed += row.runsAllowed();
            earnedRuns += row.earnedRuns();
            walks += row.walks();
            strikeouts += row.strikeouts();
            homeRunsAllowed += row.homeRunsAllowed();
            battersFaced += row.battersFaced();
            atBats += row.atBats();
            pitches += row.pitches();
            games += row.games();
            wins += row.wins();
            losses += row.losses();
        }
        return new PitcherDetailLine("합계", formatInningsPitched(outs), hitsAllowed, runsAllowed, earnedRuns, walks,
                strikeouts, homeRunsAllowed, battersFaced, atBats, pitches, games, wins, losses);
    }

    private int parseInningsToOuts(String innings) {
        if (innings == null || innings.isBlank()) return 0;
        String[] parts = innings.trim().split("\\.");
        try {
            int whole = Integer.parseInt(parts[0]);
            int decimal = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            if (decimal < 0) decimal = 0;
            if (decimal > 2) decimal = 2;
            return (whole * 3) + decimal;
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    public static class GameForm {
        @NotNull(message = "홈 팀을 선택해 주세요.")
        private Long homeTeamId;
        @NotNull(message = "원정 팀을 선택해 주세요.")
        private Long awayTeamId;
        @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm[:ss]")
        private LocalDateTime gameDateTime;
        private String venue;
        private Integer homeScore;
        private Integer awayScore;
        private Game.GameStatus status;
        private String memo;
        /** 초·말 교대 등 안내 — 중계 탭 상단 별도 구역에만 표시 */
        private String halfInningTransitionNotes;
        /** 회초/말 끝 공·교체 메모 (형식은 폼 도움말 참고) */
        private String halfInningBreakNotes;
        private Boolean doubleheader;
        private Boolean exhibition;

        public GameForm() {
            this.doubleheader = false;
            this.exhibition = false;
        }

        public GameForm(Long homeTeamId, Long awayTeamId, LocalDateTime gameDateTime, String venue,
                        Integer homeScore, Integer awayScore, Game.GameStatus status, String memo,
                        String halfInningTransitionNotes,
                        String halfInningBreakNotes,
                        Boolean doubleheader, Boolean exhibition) {
            this.homeTeamId = homeTeamId;
            this.awayTeamId = awayTeamId;
            this.gameDateTime = gameDateTime;
            this.venue = venue;
            this.homeScore = homeScore;
            this.awayScore = awayScore;
            this.status = status;
            this.memo = memo;
            this.halfInningTransitionNotes = halfInningTransitionNotes;
            this.halfInningBreakNotes = halfInningBreakNotes;
            this.doubleheader = doubleheader;
            this.exhibition = exhibition;
        }

        public Long getHomeTeamId() { return homeTeamId; }
        public void setHomeTeamId(Long homeTeamId) { this.homeTeamId = homeTeamId; }
        public Long getAwayTeamId() { return awayTeamId; }
        public void setAwayTeamId(Long awayTeamId) { this.awayTeamId = awayTeamId; }
        public LocalDateTime getGameDateTime() { return gameDateTime; }
        public void setGameDateTime(LocalDateTime gameDateTime) { this.gameDateTime = gameDateTime; }
        public String getVenue() { return venue; }
        public void setVenue(String venue) { this.venue = venue; }
        public Integer getHomeScore() { return homeScore; }
        public void setHomeScore(Integer homeScore) { this.homeScore = homeScore; }
        public Integer getAwayScore() { return awayScore; }
        public void setAwayScore(Integer awayScore) { this.awayScore = awayScore; }
        public Game.GameStatus getStatus() { return status; }
        public void setStatus(Game.GameStatus status) { this.status = status; }
        public String getMemo() { return memo; }
        public void setMemo(String memo) { this.memo = memo; }
        public String getHalfInningTransitionNotes() { return halfInningTransitionNotes; }
        public void setHalfInningTransitionNotes(String halfInningTransitionNotes) { this.halfInningTransitionNotes = halfInningTransitionNotes; }
        public String getHalfInningBreakNotes() { return halfInningBreakNotes; }
        public void setHalfInningBreakNotes(String halfInningBreakNotes) { this.halfInningBreakNotes = halfInningBreakNotes; }
        public Boolean getDoubleheader() { return doubleheader; }
        public void setDoubleheader(Boolean doubleheader) { this.doubleheader = doubleheader; }
        public Boolean getExhibition() { return exhibition; }
        public void setExhibition(Boolean exhibition) { this.exhibition = exhibition; }
    }
}
