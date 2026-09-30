package ru.pulsedoma.common;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;

import java.io.IOException;
import java.util.UUID;

public final class CorrelationIdFilter implements Filter {
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String supplied = ((HttpServletRequest) request).getHeader("X-Correlation-Id");
        String id = supplied != null && supplied.matches("[A-Za-z0-9_-]{1,64}") ? supplied : UUID.randomUUID().toString();
        ((HttpServletResponse) response).setHeader("X-Correlation-Id", id);
        try (MDC.MDCCloseable ignored = MDC.putCloseable("correlationId", id)) {
            chain.doFilter(request, response);
        }
    }
}
