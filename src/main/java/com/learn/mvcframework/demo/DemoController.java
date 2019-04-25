package com.learn.mvcframework.demo;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.learn.mvcframework.annotation.MockAutowired;
import com.learn.mvcframework.annotation.MockController;
import com.learn.mvcframework.annotation.MockRequestMapping;
import com.learn.mvcframework.annotation.MockRequestParam;

@MockController
public class DemoController {

	@MockAutowired
	private DemoService service;

	@MockRequestMapping("/demo")
	public String demoHello(HttpServletRequest req, HttpServletResponse resp, @MockRequestParam("name") String name) {
		service.doService(name);
		System.out.println("req=" + req + " resp=" + resp);
		return "Hello ," + name;
	}
}
