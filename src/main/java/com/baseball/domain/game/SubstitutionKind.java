package com.baseball.domain.game;

/**
 * 경기 중 교체 종류. 투수 교체와 야수(포지션) 교체를 구분한다.
 */
public enum SubstitutionKind {
    /** 투수 교체 (기존 동작) */
    PITCHER,
    /** 유격수·3루수 등 수비 위치별 선수 교체 */
    FIELD,
    /** 1·2·3루주자 OUT → 대주자 IN 등 주자 교체 */
    RUNNER
}
