package com.baseball.config;

import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * H2 콘솔 iframe 사용 시 X-Frame-Options 이슈 방지 (개발 환경)
 */
@Profile("!prod")
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class H2ConsoleConfig implements Filter {

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
        HttpServletResponse response = (HttpServletResponse) res;
        HttpServletRequest request = (HttpServletRequest) req;
        if (request.getRequestURI().startsWith("/h2-console")) {
            response.setHeader("X-Frame-Options", "SAMEORIGIN");
        }
        chain.doFilter(req, res);
    }
}
