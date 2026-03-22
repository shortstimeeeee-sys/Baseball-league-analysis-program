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

/**
 * 기존 H2 파일 DB에 games 신규 boolean 컬럼이 없을 때 /games 조회 500 방지.
 * Hibernate ddl-auto 이후 실행되도록 ApplicationRunner 사용.
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
@ConditionalOnProperty(name = "spring.datasource.url", matchIfMissing = false)
@DependsOn("entityManagerFactory")
public class H2GamesDhColumnFix implements ApplicationRunner {

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
            jdbc.execute("ALTER TABLE GAMES ADD COLUMN IF NOT EXISTS DH_FLAG BOOLEAN DEFAULT FALSE NOT NULL");
            jdbc.execute("ALTER TABLE GAMES ADD COLUMN IF NOT EXISTS EXHIBITION_FLAG BOOLEAN DEFAULT FALSE NOT NULL");
            jdbc.execute("ALTER TABLE GAMES ADD COLUMN IF NOT EXISTS HALF_INNING_BREAK_NOTES VARCHAR(10000)");
            jdbc.execute("ALTER TABLE GAMES ADD COLUMN IF NOT EXISTS HALF_INNING_TRANSITION_NOTES VARCHAR(10000)");
            jdbc.execute("ALTER TABLE GAMES ADD COLUMN IF NOT EXISTS RECORD_CONFIRMED_HALF_KEYS VARCHAR(4000)");
            if (log.isDebugEnabled()) {
                log.debug("H2 games boolean 컬럼(dh_flag, exhibition_flag) 확인 완료");
            }
        } catch (Exception ex) {
            if (log.isDebugEnabled()) {
                log.debug("games boolean 컬럼 보장 스킵: {}", ex.getMessage());
            }
        }
    }
}
