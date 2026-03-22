package com.baseball.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Thymeleaf 템플릿에서 사용할 공통 모델 속성을 제공합니다.
 * Spring 6 / Thymeleaf 3.1+ 에서 #request 등이 기본 비노출되므로 requestURI 등을 모델로 전달합니다.
 */
@ControllerAdvice
public class GlobalControllerAdvice {

    @ModelAttribute("requestURI")
    public String addRequestUriToModel(HttpServletRequest request) {
        return request != null ? request.getRequestURI() : "";
    }
}
