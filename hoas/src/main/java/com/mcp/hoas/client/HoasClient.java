package com.mcp.hoas.client;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Browser-like session against booking-hoas.tampuuri.fi (a CodeIgniter app with no API). Keeps the session
 * cookies, logs in lazily and logs in again when the session has expired.
 */
@Component
public class HoasClient {

	private static final Logger log = LoggerFactory.getLogger(HoasClient.class);

	private static final String LOGIN_PATH = "/auth/login";

	private static final String USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36";

	private final HoasProperties properties;

	private final HttpClient http;

	private boolean loggedIn;

	public HoasClient(HoasProperties properties) {
		this.properties = properties;
		this.http = HttpClient.newBuilder()
			.cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.connectTimeout(Duration.ofSeconds(15))
			.build();
	}

	/** GETs a path (e.g. {@code /varaus/service/timetable/724}) as the logged-in user and parses the HTML. */
	public synchronized Document get(String path) {
		if (!loggedIn) {
			login();
		}
		HttpResponse<String> response = send(request(path).GET().build());
		if (isLoginPage(response)) {
			log.info("HOAS session expired, logging in again");
			loggedIn = false;
			login();
			response = send(request(path).GET().build());
			if (isLoginPage(response)) {
				throw new HoasException("Still redirected to the login page after logging in: " + path);
			}
		}
		return Jsoup.parse(response.body(), response.uri().toString());
	}

	private void login() {
		if (isBlank(properties.username()) || isBlank(properties.password())) {
			throw new HoasException("HOAS credentials missing: set HOAS_USERNAME and HOAS_PASSWORD (env or .env file)");
		}
		HttpResponse<String> loginPage = send(request(LOGIN_PATH).GET().build());
		Element form = Jsoup.parse(loginPage.body()).getElementById("tankForm");
		if (form == null) {
			throw new HoasException("Login form not found on " + LOGIN_PATH);
		}

		Map<String, String> fields = new LinkedHashMap<>();
		form.select("input[type=hidden]").forEach(input -> fields.put(input.attr("name"), input.val()));
		fields.put("login", properties.username());
		fields.put("password", properties.password());
		fields.put("submit", "Kirjaudu");

		HttpResponse<String> result = send(request(LOGIN_PATH)
			.header("Content-Type", "application/x-www-form-urlencoded")
			.POST(HttpRequest.BodyPublishers.ofString(formEncode(fields)))
			.build());
		if (isLoginPage(result)) {
			throw new HoasException("HOAS login failed: check HOAS_USERNAME / HOAS_PASSWORD");
		}
		loggedIn = true;
		log.info("Logged in to HOAS as {}", properties.username());
	}

	private boolean isLoginPage(HttpResponse<String> response) {
		return response.uri().getPath().startsWith(LOGIN_PATH) || response.body().contains("id=\"tankForm\"");
	}

	private HttpRequest.Builder request(String path) {
		return HttpRequest.newBuilder(URI.create(properties.baseUrl() + path))
			.timeout(Duration.ofSeconds(30))
			.header("User-Agent", USER_AGENT);
	}

	private HttpResponse<String> send(HttpRequest request) {
		try {
			HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			if (response.statusCode() >= 400) {
				throw new HoasException("HTTP " + response.statusCode() + " from " + request.uri());
			}
			return response;
		}
		catch (IOException ex) {
			throw new HoasException("Request to " + request.uri() + " failed: " + ex.getMessage(), ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new HoasException("Interrupted while calling " + request.uri(), ex);
		}
	}

	private static String formEncode(Map<String, String> fields) {
		return fields.entrySet()
			.stream()
			.map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
					+ URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
			.collect(Collectors.joining("&"));
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

}
