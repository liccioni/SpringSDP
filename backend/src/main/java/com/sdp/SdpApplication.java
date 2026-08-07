package com.sdp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SdpApplication {

	public static void main(String[] args) {
		SpringApplication.run(SdpApplication.class, args);
	}
}
