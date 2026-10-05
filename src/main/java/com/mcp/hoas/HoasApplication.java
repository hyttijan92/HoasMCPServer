package com.mcp.hoas;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class HoasApplication {

	public static void main(String[] args) {
		SpringApplication.run(HoasApplication.class, args);
	}

}
