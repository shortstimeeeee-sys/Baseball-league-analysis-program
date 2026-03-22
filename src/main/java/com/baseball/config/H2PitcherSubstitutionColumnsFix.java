package com.baseball.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;

/**
 * 기존 H2 파일 DB에 pitcher_substitutions 신규 컬럼이 없을 때 조회 500 방지.
 * {@link H2GamesDhColumnFix}와 동일하게 ddl-auto 이후 실행.
 */
@Slf4j
@Component
@Order(2)
@RequiredArgsConstructor
@ConditionalOnProperty(name = "spring.datasource.url", matchIfMissing = false)
@DependsOn("entityManagerFactory")
public class H2PitcherSubstitutionColumnsFix implements ApplicationRunner {

    private final DataSource dataSource;

    @Override
    public void run(ApplicationArguments args) {
        try (Connection conn = dataSource.getConnection()) {
            String url = conn.getMetaData().getURL();
            if (url == null || !url.toLowerCase().contains("jdbc:h2")) {
                return;
            }
        } catch (Exception e) {
            return;
        }
        try {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            String table = resolvePitcherSubstitutionTableName(jdbc);
            if (table == null) {
                if (log.isDebugEnabled()) {
                    log.debug("pitcher_substitutions 테이블을 찾지 못해 컬럼 보정 스킵");
                }
                return;
            }
            // H2 2.x: IF NOT EXISTS
            jdbc.execute("ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS substitution_kind VARCHAR(20)");
            jdbc.execute("ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS position_label VARCHAR(40)");
            jdbc.execute("ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS display_order INTEGER NOT NULL DEFAULT 0");
            if (log.isDebugEnabled()) {
                log.debug("H2 pitcher_substitutions 컬럼(substitution_kind, position_label, display_order) 확인 완료 table={}", table);
            }
        } catch (Exception ex) {
            if (log.isWarnEnabled()) {
                log.warn("pitcher_substitutions 컬럼 보정 실패(수동 ALTER 필요할 수 있음): {}", ex.getMessage());
            }
        }
    }

    private static String resolvePitcherSubstitutionTableName(JdbcTemplate jdbc) {
        List<String> names = jdbc.query(
                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE UPPER(TABLE_NAME) = 'PITCHER_SUBSTITUTIONS'",
                (rs, rowNum) -> rs.getString(1));
        if (!names.isEmpty()) {
            return names.get(0);
        }
        names = jdbc.query(
                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE UPPER(TABLE_NAME) LIKE '%PITCHER%SUBSTITUTION%'",
                (rs, rowNum) -> rs.getString(1));
        return names.isEmpty() ? null : names.get(0);
    }
}
