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
 * Hibernate가 예전에 만든 CHECK 제약(예: substitution_kind IN ('PITCHER','FIELD') 만 허용)이
 * {@code RUNNER} 등 신규 ENUM 추가 후에도 갱신되지 않아 INSERT 시 23513이 날 수 있어, CHECK 제약을 제거한다.
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
            dropCheckConstraintsOnPitcherSubstitutions(jdbc, table);
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

    /**
     * Hibernate ddl-auto:update가 남긴 CHECK 제약이 ENUM 값 추가 전 스키마를 묶고 있으면 INSERT 23513.
     * 데이터 무결성은 애플리케이션·JPA 매핑으로 유지한다.
     */
    private static void dropCheckConstraintsOnPitcherSubstitutions(JdbcTemplate jdbc, String table) {
        try {
            List<String> checkNames = jdbc.query(
                    "SELECT CONSTRAINT_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                            + "WHERE UPPER(TABLE_NAME) = UPPER(?) AND CONSTRAINT_TYPE = 'CHECK'",
                    (rs, rowNum) -> rs.getString(1),
                    table);
            for (String name : checkNames) {
                if (name == null || name.isBlank()) {
                    continue;
                }
                jdbc.execute("ALTER TABLE " + table + " DROP CONSTRAINT \"" + name.replace("\"", "\"\"") + "\"");
                if (log.isInfoEnabled()) {
                    log.info("H2 pitcher_substitutions CHECK 제약 제거: {} (substitution_kind·ENUM 확장 호환)", name);
                }
            }
        } catch (Exception ex) {
            if (log.isWarnEnabled()) {
                log.warn("H2 pitcher_substitutions CHECK 제약 제거 실패(수동 확인): {}", ex.getMessage());
            }
            try {
                jdbc.execute("ALTER TABLE " + table + " DROP CONSTRAINT IF EXISTS CONSTRAINT_B0F");
            } catch (Exception ignored) {
                // H2 버전에 따라 IF EXISTS 미지원일 수 있음
            }
        }
    }
}
