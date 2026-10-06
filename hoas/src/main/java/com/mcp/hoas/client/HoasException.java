package com.mcp.hoas.client;

public class HoasException extends RuntimeException {

	public HoasException(String message) {
		super(message);
	}

	public HoasException(String message, Throwable cause) {
		super(message, cause);
	}

}
