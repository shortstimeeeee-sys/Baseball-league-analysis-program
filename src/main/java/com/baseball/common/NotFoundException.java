package com.baseball.common;

/**
 * 도메인 객체를 찾을 수 없을 때 사용하는 공통 예외입니다.
 * 서비스 레이어에서 사용하고, 전역 예외 처리기에서 404로 매핑합니다.
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}

