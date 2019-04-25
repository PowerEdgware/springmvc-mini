package com.learn.mvcframework.demo;

import java.time.LocalDateTime;

import com.learn.mvcframework.annotation.MockService;

@MockService
public class DemoService {

	public void doService(String name) {
		System.out.println("service method execute at:" + LocalDateTime.now());
	}
}
