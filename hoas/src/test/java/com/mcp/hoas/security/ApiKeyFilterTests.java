package com.mcp.hoas.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ApiKeyFilterTests {

	private static final String KEY = "k".repeat(ApiKeyFilter.MIN_KEY_LENGTH);

	private final ApiKeyFilter filter = new ApiKeyFilter(KEY);

	@Test
	void refusesToStartWithoutAStrongKey() {
		assertThatIllegalStateException().isThrownBy(() -> new ApiKeyFilter(""));
		assertThatIllegalStateException().isThrownBy(() -> new ApiKeyFilter(null));
		assertThatIllegalStateException().isThrownBy(() -> new ApiKeyFilter("short"));
	}

	@Test
	void rejectsMissingAndWrongKeys() throws Exception {
		assertThat(status(null, null)).isEqualTo(401);
		assertThat(status("Authorization", "Bearer wrong")).isEqualTo(401);
		assertThat(status("Authorization", KEY)).isEqualTo(401);
		assertThat(status("Authorization", "Bearer " + KEY + "x")).isEqualTo(401);
		assertThat(status("X-API-Key", "wrong")).isEqualTo(401);
	}

	@Test
	void doesNotAcceptTheKeyInTheUrl() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
		request.setParameter("apiKey", KEY);
		request.setQueryString("apiKey=" + KEY);
		MockHttpServletResponse response = new MockHttpServletResponse();
		filter.doFilter(request, response, new MockFilterChain());
		assertThat(response.getStatus()).isEqualTo(401);
	}

	@Test
	void acceptsBearerAndApiKeyHeaders() throws Exception {
		assertThat(status("Authorization", "Bearer " + KEY)).isEqualTo(200);
		assertThat(status("Authorization", "bearer " + KEY)).isEqualTo(200);
		assertThat(status("X-API-Key", KEY)).isEqualTo(200);
	}

	private int status(String header, String value) throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
		if (header != null) {
			request.addHeader(header, value);
		}
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();
		filter.doFilter(request, response, chain);
		// The chain is only invoked for authenticated requests
		assertThat(chain.getRequest() != null).isEqualTo(response.getStatus() == 200);
		return response.getStatus();
	}

}
