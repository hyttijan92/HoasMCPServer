package com.mcp.hoas.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects every request that does not carry the server's API key, before it reaches the MCP endpoint. The key is
 * accepted as {@code Authorization: Bearer <key>} or {@code X-API-Key: <key>}, never from the URL.
 * <p>
 * The server refuses to start without a key, so a misconfigured deployment cannot run unprotected.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiKeyFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(ApiKeyFilter.class);

	static final int MIN_KEY_LENGTH = 32;

	private static final String BEARER = "Bearer ";

	private final byte[] expectedDigest;

	public ApiKeyFilter(@Value("${hoas.mcp.api-key:}") String apiKey) {
		if (apiKey == null || apiKey.isBlank()) {
			throw new IllegalStateException(
					"HOAS_MCP_API_KEY is not set. Generate one with: openssl rand -base64 32");
		}
		if (apiKey.length() < MIN_KEY_LENGTH) {
			throw new IllegalStateException("HOAS_MCP_API_KEY must be at least " + MIN_KEY_LENGTH
					+ " characters. Generate one with: openssl rand -base64 32");
		}
		this.expectedDigest = sha256(apiKey);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String presented = presentedKey(request);
		// Comparing fixed-length digests in constant time leaks neither the key's content nor its length
		if (presented != null && MessageDigest.isEqual(expectedDigest, sha256(presented))) {
			chain.doFilter(request, response);
			return;
		}
		log.warn("Rejected unauthenticated {} {} from {}", request.getMethod(), request.getRequestURI(),
				request.getRemoteAddr());
		response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
		response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.getWriter().write("{\"error\":\"unauthorized\"}");
	}

	private static String presentedKey(HttpServletRequest request) {
		String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (authorization != null && authorization.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
			return authorization.substring(BEARER.length()).trim();
		}
		String apiKey = request.getHeader("X-API-Key");
		return apiKey != null ? apiKey.trim() : null;
	}

	private static byte[] sha256(String value) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
