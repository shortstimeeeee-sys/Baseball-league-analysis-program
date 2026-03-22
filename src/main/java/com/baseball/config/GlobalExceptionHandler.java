package com.baseball.config;

import com.baseball.common.NotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * 전역 예외 처리기.
 * - NotFoundException → 404 페이지
 * - IllegalArgumentException 등 기타 예외 → 500 에러 페이지
 */
@Slf4j
@ControllerAdvice
public class GlobalExceptionHandler {

    @ResponseStatus(NOT_FOUND)
    @ExceptionHandler(NotFoundException.class)
    public String handleNotFound(NotFoundException ex, Model model) {
        log.warn("NotFoundException: {}", ex.getMessage());
        model.addAttribute("message", ex.getMessage());
        return "error/404";
    }

    @ResponseStatus(INTERNAL_SERVER_ERROR)
    @ExceptionHandler(IllegalArgumentException.class)
    public String handleIllegalArgument(IllegalArgumentException ex, Model model) {
        log.error("IllegalArgumentException", ex);
        model.addAttribute("message", ex.getMessage());
        return "error/500";
    }

    @ResponseStatus(INTERNAL_SERVER_ERROR)
    @ExceptionHandler(Exception.class)
    public String handleException(Exception ex, Model model) {
        log.error("Unexpected exception", ex);
        model.addAttribute("message", "알 수 없는 오류가 발생했습니다.");
        model.addAttribute("exceptionMessage", ex.getMessage());
        model.addAttribute("exceptionClass", ex.getClass().getSimpleName());
        return "error/500";
    }
}

