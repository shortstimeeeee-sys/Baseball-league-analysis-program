package com.baseball.application.game;

import com.baseball.application.game.dto.GameRecordImportDto;
import com.baseball.domain.game.*;
import com.baseball.domain.league.League;
import com.baseball.domain.league.LeagueRepository;
import com.baseball.domain.player.Player;
import com.baseball.domain.player.PlayerRepository;
import com.baseball.domain.team.Team;
import com.baseball.domain.team.TeamRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 파싱된 경기 기록(GameRecordImportDto)을 DB에 반영합니다.
 * - 새 경기: gameInfo에 리그명·팀명(leagueName, homeTeamName, awayTeamName)만 넣어도 됨. 없으면 리그/팀을 찾거나 생성한 뒤 경기+기록 생성.
 * - 기존 경기: existingGameId에 기록만 덮어쓰기.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GameRecordImportService {

    /** importRecord 결과: 신규 생성인지 기존 경기 병합인지 */
    public record ImportRecordResult(Game game, boolean mergedIntoExisting) {}

    private final GameRepository gameRepository;
    private final TeamRepository teamRepository;
    private final LeagueRepository leagueRepository;
    private final InningScoreRepository inningScoreRepository;
    private final PlateAppearanceRepository plateAppearanceRepository;
    private final PitcherSubstitutionRepository pitcherSubstitutionRepository;
    private final PitchRepository pitchRepository;
    private final PlayerRepository playerRepository;
    private final WinningLosingSaveResolver winningLosingSaveResolver;

    /**
     * @param dto 파싱된 경기 기록
     * @param existingGameId 기존 경기 ID. null이면 새 경기 생성, 있으면 해당 경기 기록만 갱신
     * @param appendOnly true면 1회는 절대 삭제하지 않고 2회 이후만 추가·갱신 (이어서 넣기)
     * @return 저장/갱신된 Game
     */
    @Transactional
    public ImportRecordResult importRecord(GameRecordImportDto dto, Long existingGameId, boolean appendOnly) {
        if (dto != null) {
            NineInningEndRule.applyToDto(dto);
        }
        Game game;
        if (existingGameId != null) {
            game = gameRepository.findById(existingGameId)
                    .orElseThrow(() -> new IllegalArgumentException("경기를 찾을 수 없습니다. id=" + existingGameId));
            mergeGameRecord(game, dto, appendOnly);
            return new ImportRecordResult(game, false);
        }
        Game existingByIdentity = findExistingGameForImport(dto.getGameInfo()).orElse(null);
        if (existingByIdentity != null) {
            game = existingByIdentity;
            mergeGameRecord(game, dto, appendOnly);
            return new ImportRecordResult(game, true);
        }
        game = createGameFromDto(dto.getGameInfo());
        updateGameBoxScore(game, dto.getGameInfo());
        saveInningScores(game, dto.getInningScores());
        savePlateAppearances(game, dto.getPlateAppearances());
        savePitcherSubstitutions(game, dto.getPitcherSubstitutions() != null ? dto.getPitcherSubstitutions() : List.of());
        List<PlateAppearance> allPa = plateAppearanceRepository.findByGameIdOrderByInningAscIsTopDescSequenceOrderAsc(game.getId());
        try {
            recomputePitcherSubstitutionBattersFaced(game.getId(), allPa);
        } catch (Exception ex) {
            if (log.isWarnEnabled()) log.warn("투수 교체 battersFaced 재계산 실패 gameId={}", game.getId(), ex);
        }
        fillPitcherResultsFromRecord(game, allPa);
        return new ImportRecordResult(game, false);
    }

    /**
     * 같은 날짜·같은 홈/원정·같은 리그(둘 다 리그가 있으면 동일해야 함) 경기가 있으면 재사용.
     * 더블헤더(true)로 표시된 신규 등록은 병합하지 않고 별도 경기로 둠.
     */
    private Optional<Game> findExistingGameForImport(GameRecordImportDto.GameInfo info) {
        if (info == null) return Optional.empty();
        if (Boolean.TRUE.equals(info.getDoubleheader())) {
            return Optional.empty();
        }
        Team homeTeam;
        Team awayTeam;
        if (info.getHomeTeamId() != null && info.getAwayTeamId() != null) {
            homeTeam = teamRepository.findById(info.getHomeTeamId()).orElse(null);
            awayTeam = teamRepository.findById(info.getAwayTeamId()).orElse(null);
        } else if (notBlank(info.getHomeTeamName()) && notBlank(info.getAwayTeamName())) {
            League league = resolveLeague(info.getLeagueName());
            homeTeam = resolveOrCreateTeam(info.getHomeTeamName(), league);
            awayTeam = resolveOrCreateTeam(info.getAwayTeamName(), league);
        } else {
            return Optional.empty();
        }
        if (homeTeam == null || awayTeam == null) return Optional.empty();
        if (!teamsInSameLeagueForMatchup(homeTeam, awayTeam)) {
            return Optional.empty();
        }
        LocalDate baseDate = (info.getGameDateTime() != null ? info.getGameDateTime().toLocalDate() : LocalDate.now());
        LocalDateTime start = baseDate.atStartOfDay();
        LocalDateTime end = baseDate.plusDays(1).atStartOfDay();
        List<Game> list = gameRepository.findByHomeAwayAndGameDateTimeBetweenOrderByGameDateTimeDesc(
                homeTeam.getId(), awayTeam.getId(), start, end);
        return pickGameForMerge(list, Boolean.TRUE.equals(info.getExhibition()));
    }

    /** 둘 다 리그가 있으면 같은 리그여야 같은 매치업으로 병합. 하나라도 없으면 기존 호환을 위해 병합 허용 */
    private boolean teamsInSameLeagueForMatchup(Team home, Team away) {
        if (home.getLeague() == null || away.getLeague() == null) {
            return true;
        }
        return java.util.Objects.equals(home.getLeague().getId(), away.getLeague().getId());
    }

    /** 첫 번째 슬롯(더블헤더 아님) 경기가 있으면 그걸로 병합, 없으면 목록 첫 행 */
    private Optional<Game> pickGameForMerge(List<Game> games, boolean exhibition) {
        if (games == null || games.isEmpty()) {
            return Optional.empty();
        }
        List<Game> sameCategory = games.stream()
                .filter(g -> g.isExhibition() == exhibition)
                .toList();
        if (sameCategory.isEmpty()) {
            return Optional.empty();
        }
        Optional<Game> firstSlot = sameCategory.stream().filter(g -> !g.isDoubleheader()).findFirst();
        return firstSlot.or(() -> Optional.of(sameCategory.get(0)));
    }

    /** 기존 경기: appendOnly면 1회는 절대 삭제하지 않고 2회 이후만 추가·갱신. 아니면 헤더·타석에 나타난 반 이닝만 갱신 */
    private void mergeGameRecord(Game game, GameRecordImportDto dto, boolean appendOnly) {
        List<GameRecordImportDto.PlateAppearanceRow> newRows = dto.getPlateAppearances() != null ? dto.getPlateAppearances() : List.of();
        List<GameRecordImportDto.PitcherSubstitutionRow> newSubs = dto.getPitcherSubstitutions() != null ? dto.getPitcherSubstitutions() : List.of();
        Set<String> withHeader = dto.getHalfInningsWithHeader() != null ? new HashSet<>(dto.getHalfInningsWithHeader()) : new HashSet<>();
        // 파서가 halfInningsWithHeader에 일부 반만 넣고 타석은 말(isTop=false)까지 생성한 경우, 병합 필터에 걸려 홈 공격만 저장에서 빠질 수 있음.
        // 타석·투수교체 행에 실제로 있는 (이닝_isTop)은 항상 병합 키에 포함한다. (신규 경기 생성 경로는 필터가 없어 동일 텍스트는 정상이었을 수 있음)
        for (GameRecordImportDto.PlateAppearanceRow r : newRows) {
            withHeader.add(r.getInning() + "_" + r.isTop());
        }
        for (GameRecordImportDto.PitcherSubstitutionRow s : newSubs) {
            withHeader.add(s.getInning() + "_" + s.isTop());
        }
        if (!withHeader.isEmpty()) {
            List<PlateAppearance> existing = plateAppearanceRepository.findByGameIdOrderByInningAscIsTopDescSequenceOrderAsc(game.getId());
            for (PlateAppearance pa : existing) {
                if (appendOnly && pa.getInning() != null && pa.getInning() == 1) continue;
                String key = (pa.getInning() != null ? pa.getInning() : 0) + "_" + Boolean.TRUE.equals(pa.getIsTop());
                if (withHeader.contains(key) && !game.isRecordHalfKeyConfirmed(key)) {
                    plateAppearanceRepository.delete(pa);
                }
            }
        }
        List<GameRecordImportDto.PlateAppearanceRow> toSave = newRows.stream()
                .filter(r -> withHeader.contains(r.getInning() + "_" + r.isTop()))
                .filter(r -> !game.isRecordHalfKeyConfirmed(r.getInning() + "_" + r.isTop()))
                .filter(r -> !appendOnly || r.getInning() >= 2)
                .collect(Collectors.toList());
        savePlateAppearances(game, toSave);
        if (!withHeader.isEmpty()) {
            List<PitcherSubstitution> existingSubs = pitcherSubstitutionRepository.findByGameIdOrderByInningAscIsTopDescBattersFacedAsc(game.getId());
            for (PitcherSubstitution ps : existingSubs) {
                if (appendOnly && ps.getInning() != null && ps.getInning() == 1) continue;
                String key = ps.getInning() + "_" + Boolean.TRUE.equals(ps.getIsTop());
                if (withHeader.contains(key) && !game.isRecordHalfKeyConfirmed(key)) {
                    pitcherSubstitutionRepository.delete(ps);
                }
            }
            List<GameRecordImportDto.PitcherSubstitutionRow> toSaveSubs = newSubs.stream()
                    .filter(s -> withHeader.contains(s.getInning() + "_" + s.isTop()))
                    .filter(s -> !game.isRecordHalfKeyConfirmed(s.getInning() + "_" + s.isTop()))
                    .filter(s -> !appendOnly || s.getInning() >= 2)
                    .collect(Collectors.toList());
            savePitcherSubstitutions(game, toSaveSubs);
        }
        plateAppearanceRepository.flush();
        List<PlateAppearance> allPa = plateAppearanceRepository.findByGameIdOrderByInningAscIsTopDescSequenceOrderAsc(game.getId());
        recomputeInningScoresAndBoxScore(game, allPa);
        try {
            recomputePitcherSubstitutionBattersFaced(game.getId(), allPa);
        } catch (Exception ex) {
            // 재계산 실패 시에도 import는 성공 처리 (N타자 상대 후만 0으로 남을 수 있음)
            if (log.isWarnEnabled()) log.warn("투수 교체 battersFaced 재계산 실패 gameId={}", game.getId(), ex);
        }
        if (dto.getGameInfo() != null && dto.getGameInfo().getStatus() == Game.GameStatus.COMPLETED) {
            game.updateResult(game.getHomeScore(), game.getAwayScore(), Game.GameStatus.COMPLETED, game.getMemo());
        }
        if (dto.getGameInfo() != null) {
            GameRecordImportDto.GameInfo gi = dto.getGameInfo();
            game.updatePitcherResults(gi.getWinningPitcherName(), gi.getLosingPitcherName(), gi.getSavePitcherName());
            game.clearSavePitcherWhenSameAsWinningPitcher();
        }
        fillPitcherResultsFromRecord(game, allPa);
    }

    /** 파싱으로 채워지지 않은 승리/패전/세이브 투수를 기록(타석·득점)만으로 추정해 채운다 */
    private void fillPitcherResultsFromRecord(Game game, List<PlateAppearance> allPa) {
        if (game == null || allPa == null || allPa.isEmpty()) return;
        try {
            WinningLosingSaveResolver.Result r = winningLosingSaveResolver.resolve(game, allPa);
            String w = (game.getWinningPitcherName() != null && !game.getWinningPitcherName().isBlank()) ? game.getWinningPitcherName() : r.winningPitcherName();
            String l = (game.getLosingPitcherName() != null && !game.getLosingPitcherName().isBlank()) ? game.getLosingPitcherName() : r.losingPitcherName();
            String s = (game.getSavePitcherName() != null && !game.getSavePitcherName().isBlank()) ? game.getSavePitcherName() : r.savePitcherName();
            if (PitcherNameNormalizer.samePitcher(w, s)) {
                s = null;
            }
            game.updatePitcherResults(w, l, s);
            // updatePitcherResults는 save가 null이면 세이브 필드를 건드리지 않음 → 중복 제거·추정 결과가 null이면 명시적으로 비움
            if (s == null) {
                game.setSavePitcherName(null);
            }
            game.clearSavePitcherWhenSameAsWinningPitcher();
        } catch (Exception ex) {
            if (log.isDebugEnabled()) log.debug("기록으로 승리/패전/세이브 투수 추정 실패 gameId={}", game.getId(), ex);
        }
    }

    private Game createGameFromDto(GameRecordImportDto.GameInfo info) {
        if (info == null) {
            throw new IllegalArgumentException("경기 정보(gameInfo)가 필요합니다.");
        }
        Team homeTeam;
        Team awayTeam;
        if (info.getHomeTeamId() != null && info.getAwayTeamId() != null) {
            homeTeam = teamRepository.findById(info.getHomeTeamId())
                    .orElseThrow(() -> new IllegalArgumentException("홈 팀을 찾을 수 없습니다. id=" + info.getHomeTeamId()));
            awayTeam = teamRepository.findById(info.getAwayTeamId())
                    .orElseThrow(() -> new IllegalArgumentException("원정 팀을 찾을 수 없습니다. id=" + info.getAwayTeamId()));
        } else if (notBlank(info.getHomeTeamName()) && notBlank(info.getAwayTeamName())) {
            League league = resolveLeague(info.getLeagueName());
            homeTeam = resolveOrCreateTeam(info.getHomeTeamName(), league);
            awayTeam = resolveOrCreateTeam(info.getAwayTeamName(), league);
        } else {
            throw new IllegalArgumentException("경기 정보에 홈/원정 팀이 필요합니다. homeTeamId/awayTeamId 또는 homeTeamName/awayTeamName을 넣어 주세요.");
        }

        Game game = Game.builder()
                .homeTeam(homeTeam)
                .awayTeam(awayTeam)
                .gameDateTime(info.getGameDateTime() != null ? info.getGameDateTime() : java.time.LocalDateTime.now())
                .venue(info.getVenue())
                .homeScore(info.getHomeScore() != null ? info.getHomeScore() : 0)
                .awayScore(info.getAwayScore() != null ? info.getAwayScore() : 0)
                .homeHits(info.getHomeHits())
                .awayHits(info.getAwayHits())
                .homeErrors(info.getHomeErrors())
                .awayErrors(info.getAwayErrors())
                .homeWalks(info.getHomeWalks())
                .awayWalks(info.getAwayWalks())
                .status(info.getStatus() != null ? info.getStatus() : Game.GameStatus.COMPLETED)
                .winningPitcherName(info.getWinningPitcherName())
                .losingPitcherName(info.getLosingPitcherName())
                .savePitcherName(info.getSavePitcherName())
                .doubleheader(Boolean.TRUE.equals(info.getDoubleheader()))
                .exhibition(Boolean.TRUE.equals(info.getExhibition()))
                .build();
        return gameRepository.save(game);
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /** 리그명으로 찾거나 없으면 생성 */
    private League resolveLeague(String leagueName) {
        if (leagueName == null || leagueName.isBlank()) {
            return null;
        }
        return leagueRepository.findFirstByName(leagueName.trim())
                .orElseGet(() -> leagueRepository.save(League.builder()
                        .name(leagueName.trim())
                        .build()));
    }

    /** 팀명(및 리그)으로 찾거나 없으면 생성 */
    private Team resolveOrCreateTeam(String teamName, League league) {
        if (teamName == null || teamName.isBlank()) {
            throw new IllegalArgumentException("팀 이름이 비어 있습니다.");
        }
        String name = teamName.trim();
        Optional<Team> existing = league != null
                ? teamRepository.findFirstByNameAndLeagueId(name, league.getId())
                : teamRepository.findFirstByName(name);
        if (existing.isPresent()) {
            return existing.get();
        }
        Team team = Team.builder()
                .name(name)
                .league(league)
                .build();
        return teamRepository.save(team);
    }

    private void clearGameRecord(Game game) {
        List<PlateAppearance> list = plateAppearanceRepository.findByGameIdOrderByInningAscIsTopDescSequenceOrderAsc(game.getId());
        for (PlateAppearance pa : list) {
            plateAppearanceRepository.delete(pa);
        }
        inningScoreRepository.findByGameIdOrderByInning(game.getId()).forEach(inningScoreRepository::delete);
    }

    private void updateGameBoxScore(Game game, GameRecordImportDto.GameInfo info) {
        if (info == null) return;
        game.updateResult(
                info.getHomeScore() != null ? info.getHomeScore() : game.getHomeScore(),
                info.getAwayScore() != null ? info.getAwayScore() : game.getAwayScore(),
                info.getStatus() != null ? info.getStatus() : game.getStatus(),
                game.getMemo()
        );
        game.updateBoxScore(info.getHomeHits(), info.getAwayHits(), info.getHomeErrors(), info.getAwayErrors(),
                info.getHomeWalks(), info.getAwayWalks());
    }

    private void saveInningScores(Game game, List<GameRecordImportDto.InningScoreRow> rows) {
        if (rows == null) return;
        for (GameRecordImportDto.InningScoreRow row : rows) {
            InningScore is = InningScore.builder()
                    .game(game)
                    .inning(row.getInning())
                    .homeRunsInInning(row.getHomeRunsInInning())
                    .awayRunsInInning(row.getAwayRunsInInning())
                    .build();
            inningScoreRepository.save(is);
        }
    }

    /** 병합된 전체 타석 목록으로 이닝별 득점·총점·안타·실책 재계산 후 저장 */
    private void recomputeInningScoresAndBoxScore(Game game, List<PlateAppearance> allPa) {
        inningScoreRepository.findByGameIdOrderByInning(game.getId()).forEach(inningScoreRepository::delete);
        Map<Integer, int[]> runsByInning = new TreeMap<>();
        for (int i = 1; i <= 9; i++) runsByInning.put(i, new int[]{0, 0});
        int awayHits = 0, homeHits = 0, awayErrors = 0, homeErrors = 0;
        for (PlateAppearance pa : allPa) {
            Integer inn = pa.getInning();
            if (inn == null || inn < 1 || inn > 9) continue;
            int runs = count홈인InPa(pa);
            int[] r = runsByInning.get(inn);
            if (Boolean.TRUE.equals(pa.getIsTop())) {
                r[0] += runs;
                if (isHit(pa.getResultText())) awayHits++;
                homeErrors += count실책InPa(pa);
            } else {
                r[1] += runs;
                if (isHit(pa.getResultText())) homeHits++;
                awayErrors += count실책InPa(pa);
            }
        }
        for (Map.Entry<Integer, int[]> e : runsByInning.entrySet()) {
            int[] r = e.getValue();
            inningScoreRepository.save(InningScore.builder()
                    .game(game)
                    .inning(e.getKey())
                    .awayRunsInInning(r[0])
                    .homeRunsInInning(r[1])
                    .build());
        }
        int totalAway = runsByInning.values().stream().mapToInt(r -> r[0]).sum();
        int totalHome = runsByInning.values().stream().mapToInt(r -> r[1]).sum();
        game.updateResult(totalHome, totalAway, game.getStatus(), game.getMemo());
        game.updateBoxScore(homeHits, awayHits, homeErrors, awayErrors, game.getHomeWalks(), game.getAwayWalks());
    }

    private int count홈인InPa(PlateAppearance pa) {
        String text = (pa.getResultText() != null ? pa.getResultText() : "") + " " +
                (pa.getRunnerPlaysList() != null ? String.join(" ", pa.getRunnerPlaysList()) : "");
        int c = 0, i = 0;
        while ((i = text.indexOf("홈인", i)) != -1) { c++; i += 2; }
        return c;
    }

    private boolean isHit(String resultText) {
        if (resultText == null) return false;
        return resultText.contains("1루타") || resultText.contains("2루타") || resultText.contains("3루타")
                || resultText.contains("홈런") || resultText.contains("내야안타") || resultText.contains("번트안타");
    }

    /** 타석 단위 실책 집계: 결과/주자플레이 중 실책이 하나라도 있으면 1건 */
    private int count실책InPa(PlateAppearance pa) {
        int count = count실책InLine(pa.getResultText());
        if (pa.getRunnerPlaysList() != null) {
            for (String play : pa.getRunnerPlaysList()) {
                count = Math.max(count, count실책InLine(play));
            }
        }
        return count > 0 ? 1 : 0;
    }

    private boolean hasRealError(String text) {
        if (text == null || text.contains("무실책")) return false;
        return text.contains("실책");
    }

    /** 한 줄당 최대 1건으로 실책 이벤트 계산 */
    private int count실책InLine(String text) {
        return hasRealError(text) ? 1 : 0;
    }

    private int countOccurrences(String text, String sub) {
        if (text == null || sub == null || sub.isEmpty()) return 0;
        int c = 0, i = 0;
        while ((i = text.indexOf(sub, i)) != -1) { c++; i += sub.length(); }
        return c;
    }

    private void savePlateAppearances(Game game, List<GameRecordImportDto.PlateAppearanceRow> rows) {
        if (rows == null) return;
        // 동일 팀/타순 첫 등장 타자를 주전으로 저장
        Map<String, String> starterBatterBySideAndOrder = new HashMap<>();
        // 동일 수비팀 첫 등장 투수를 선발로 저장
        Map<String, String> starterPitcherByDefenseSide = new HashMap<>();
        for (GameRecordImportDto.PlateAppearanceRow row : rows) {
            Team batterTeam = row.isTop() ? game.getAwayTeam() : game.getHomeTeam();
            Team pitcherTeam = row.isTop() ? game.getHomeTeam() : game.getAwayTeam();
            Optional<Player> batterOpt = resolveOrCreatePlayer(row.getBatterName(), batterTeam, null);
            Optional<Player> pitcherOpt = resolveOrCreatePlayer(row.getPitcherName(), pitcherTeam, Player.Position.PITCHER);

            String runnerText = (row.getRunnerPlays() != null && !row.getRunnerPlays().isEmpty())
                    ? String.join("\n", row.getRunnerPlays()) : null;

            Boolean batterIsStarter = row.getBatterIsStarter();
            if (batterIsStarter == null) {
                if (row.getBatterOrder() != null) {
                    String offenseSide = row.isTop() ? "AWAY_OFF" : "HOME_OFF";
                    String key = offenseSide + "_" + row.getBatterOrder();
                    String seen = starterBatterBySideAndOrder.get(key);
                    String batterName = row.getBatterName() != null ? row.getBatterName().trim() : "";
                    if (seen == null) {
                        starterBatterBySideAndOrder.put(key, batterName);
                        batterIsStarter = true;
                    } else {
                        batterIsStarter = seen.equals(batterName);
                    }
                } else {
                    batterIsStarter = null;
                }
            }

            Boolean pitcherIsStarter = row.getPitcherIsStarter();
            if (pitcherIsStarter == null) {
                String defenseSide = row.isTop() ? "HOME_DEF" : "AWAY_DEF";
                String seenPitcher = starterPitcherByDefenseSide.get(defenseSide);
                String pitcherName = row.getPitcherName() != null ? row.getPitcherName().trim() : "";
                if (seenPitcher == null) {
                    starterPitcherByDefenseSide.put(defenseSide, pitcherName);
                    pitcherIsStarter = true;
                } else {
                    pitcherIsStarter = seenPitcher.equals(pitcherName);
                }
            }

            PlateAppearance pa = PlateAppearance.builder()
                    .game(game)
                    .inning(row.getInning())
                    .isTop(row.isTop())
                    .sequenceOrder(row.getSequenceOrder())
                    .batterName(row.getBatterName())
                    .pitcherName(row.getPitcherName())
                    .batterOrder(row.getBatterOrder())
                    .batterIsStarter(batterIsStarter)
                    .batterSubstitutionType(parseBatterSubstitutionType(row.getBatterSubstitutionType()))
                    .pitcherIsStarter(pitcherIsStarter)
                    .resultText(row.getResultText())
                    .runnerPlaysText(runnerText)
                    .batter(batterOpt.orElse(null))
                    .pitcher(pitcherOpt.orElse(null))
                    .build();
            pa = plateAppearanceRepository.save(pa);

            if (row.getPitches() != null) {
                for (GameRecordImportDto.PitchRow pr : row.getPitches()) {
                    Pitch p = Pitch.builder()
                            .plateAppearance(pa)
                            .pitchOrder(pr.getPitchOrder())
                            .ballCountAfter(pr.getBallCountAfter())
                            .strikeCountAfter(pr.getStrikeCountAfter())
                            .pitchType(pr.getPitchType())
                            .speedKmh(pr.getSpeedKmh())
                            .resultText(pr.getResultText())
                            .build();
                    pa.addPitch(p);
                    pitchRepository.save(p);
                }
            }
        }
    }

    private void savePitcherSubstitutions(Game game, List<GameRecordImportDto.PitcherSubstitutionRow> rows) {
        if (rows == null) return;
        for (GameRecordImportDto.PitcherSubstitutionRow row : rows) {
            int bf = Math.max(0, row.getBattersFaced());
            SubstitutionKind kind = row.getKind() != null ? row.getKind() : SubstitutionKind.PITCHER;
            int inning = row.getInning();
            if (inning < 1) {
                inning = 1;
            }
            PitcherSubstitution ps = PitcherSubstitution.builder()
                    .game(game)
                    .inning(inning)
                    .isTop(row.isTop())
                    .kind(kind)
                    .positionLabel(row.getPositionLabel())
                    .displayOrder(Math.max(0, row.getDisplayOrder()))
                    .pitcherOutName(row.getPitcherOutName() != null ? row.getPitcherOutName().trim() : "")
                    .pitcherInName(row.getPitcherInName() != null ? row.getPitcherInName().trim() : "")
                    .battersFaced(bf)
                    .afterPaSequenceOrder(row.getAfterPaSequenceOrder())
                    .build();
            pitcherSubstitutionRepository.save(ps);
        }
    }

    /** DB에 저장된 전체 타석 기준으로 투수 교체의 N타자 상대 후 재계산 (이어서 추가 시 파싱 텍스트에 1회가 없어도 정확히 반영) */
    private void recomputePitcherSubstitutionBattersFaced(Long gameId, List<PlateAppearance> allPa) {
        if (gameId == null) return;
        List<PitcherSubstitution> subs = pitcherSubstitutionRepository.findByGameIdOrderByInningAscIsTopDescBattersFacedAsc(gameId);
        if (subs == null || subs.isEmpty()) return;
        for (PitcherSubstitution ps : subs) {
            if (ps.getKind() == SubstitutionKind.FIELD || ps.getKind() == SubstitutionKind.RUNNER) {
                continue;
            }
            Integer inn = ps.getInning();
            if (inn == null || inn < 1) continue;
            int prevInning = inn - 1;
            Boolean prevIsTop = ps.getIsTop();
            long count = (allPa != null ? allPa.stream()
                    .filter(pa -> pa != null && prevInning == (pa.getInning() != null ? pa.getInning() : 0)
                            && java.util.Objects.equals(prevIsTop, pa.getIsTop()))
                    .count() : 0L);
            ps.setBattersFaced((int) count);
            pitcherSubstitutionRepository.save(ps);
        }
    }

    /**
     * 임포트 시 선수 목록을 누적하기 위해 업서트.
     * - 같은 팀에 같은 이름 선수가 있으면 재사용
     * - 없으면 새로 생성해 팀에 귀속
     * - position이 주어지면(투수 등) 비어 있을 때만 채움
     */
    private Optional<Player> resolveOrCreatePlayer(String name, Team team, Player.Position position) {
        if (name == null || name.isBlank() || team == null || team.getId() == null) return Optional.empty();
        String trimmed = name.trim();
        Optional<Player> existing = playerRepository.findByNameAndTeamId(trimmed, team.getId());
        if (existing.isPresent()) {
            Player p = existing.get();
            if (position != null && p.getPosition() == null) {
                p.update(null, position, null, null, null);
                playerRepository.save(p);
            }
            return existing;
        }
        Player created = Player.builder()
                .name(trimmed)
                .team(team)
                .position(position)
                .build();
        return Optional.of(playerRepository.save(created));
    }

    private BatterSubstitutionType parseBatterSubstitutionType(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return BatterSubstitutionType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
