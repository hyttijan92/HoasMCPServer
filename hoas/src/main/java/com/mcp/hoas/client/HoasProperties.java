package com.mcp.hoas.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "hoas")
public record HoasProperties(String baseUrl, String username, String password) {

	public HoasProperties {
		if (baseUrl == null || baseUrl.isBlank()) {
			baseUrl = "https://booking-hoas.tampuuri.fi";
		}
		baseUrl = baseUrl.replaceAll("/+$", "");
	}

}
