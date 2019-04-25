package com.learn.mvcframework.v1.servlet;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.net.URL;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.learn.mvcframework.annotation.MockAutowired;
import com.learn.mvcframework.annotation.MockController;
import com.learn.mvcframework.annotation.MockRequestMapping;
import com.learn.mvcframework.annotation.MockRequestParam;
import com.learn.mvcframework.annotation.MockService;

public class MockDispatcherServlet extends HttpServlet {
	static class HanderMethod {
		String url;
		Method method;
		Object instance;
	}

	private static final long serialVersionUID = 1L;

	static String configrationLocation = "configrationLocation";

	Map<String, HanderMethod> urlMethod = new HashMap<>();
	Map<String, Object> beansMap = new HashMap<>();
	Set<String> classesSet = new HashSet<>();

	@Override
	public void init(ServletConfig config) throws ServletException {
		System.out.println("Mock MVC starts init at:" + LocalDateTime.now());
		// load configration
		Properties props = loadConfig2Props(config);
		// doCheck
		checkLoadResult(props);
		// getAll clazz
		String scanPath = props.getProperty("scanPackage");
		doGetClasses(scanPath);

		// doResolverAndInstance
		doResolverAndInstance();

		// doAutowired
		doAutowired();

		System.out.println("Mock MVC ends init at:" + LocalDateTime.now());
	}

	private void doAutowired() {
		beansMap.forEach((className, instance) -> {
			Class<?> clazz = instance.getClass();
			// just autowire controller
			if (clazz.isAnnotationPresent(MockController.class)) {
				Field[] fields = clazz.getDeclaredFields();
				for (Field field : fields) {
					if (!field.isAnnotationPresent(MockAutowired.class)) {
						continue;
					}
					// get instance
					String beanName = field.getAnnotation(MockAutowired.class).value();
					if (emtpy(beanName)) {
						beanName = field.getType().getName();
					}
					// inject
					field.setAccessible(true);
					try {
						field.set(instance, beansMap.get(beanName));
					} catch (IllegalArgumentException e) {
						e.printStackTrace();
					} catch (IllegalAccessException e) {
						e.printStackTrace();
					}
				}
			}
		});
	}

	private void doResolverAndInstance() {
		classesSet.forEach(className -> {
			try {
				Class<?> clazz = Class.forName(className);
				if (clazz.isAnnotationPresent(MockController.class)) {// do
																		// resolver
																		// controller
					try {
						// 1. doInstance
						Object instance = clazz.newInstance();
						beansMap.put(className, instance);
						// 2. resolver mapping
						String baseUrl = "";
						// 2.1 mapping on class
						if (clazz.isAnnotationPresent(MockRequestMapping.class)) {
							baseUrl += clazz.getAnnotation(MockRequestMapping.class).value();
						}
						// 2.2 mapping on method
						Method[] methods = clazz.getDeclaredMethods();
						for (Method method : methods) {
							if (!method.isAnnotationPresent(MockRequestMapping.class)) {
								continue;
							}
							MockRequestMapping requestMapping = method.getAnnotation(MockRequestMapping.class);
							String visitUrl = baseUrl + requestMapping.value();
							HanderMethod handerMethod = new HanderMethod();
							handerMethod.url = visitUrl;
							handerMethod.instance = instance;
							handerMethod.method = method;
							urlMethod.put(visitUrl, handerMethod);
							System.out.println("Mapped url='" + visitUrl + "' on method=" + method);
						}
						//

					} catch (InstantiationException e) {
						e.printStackTrace();
					} catch (IllegalAccessException e) {
						e.printStackTrace();
					}
				} else if (clazz.isAnnotationPresent(MockService.class)) {// do
																			// resolver
																			// service
					try {
						Object instance = clazz.newInstance();
						MockService mockService = clazz.getAnnotation(MockService.class);
						String beanName = mockService.value();
						if (emtpy(beanName)) {
							beanName = className;
						}
						if (beansMap.containsKey(beanName)) {
							throw new RuntimeException("bean name duplicate for instance=" + instance);
						}
						beansMap.put(beanName, instance);
					} catch (InstantiationException e) {
						e.printStackTrace();
					} catch (IllegalAccessException e) {
						e.printStackTrace();
					}
				}
			} catch (ClassNotFoundException e) {
				e.printStackTrace();
			}
		});
	}

	private void doGetClasses(String scanPath) {
		URL url = this.getClass().getClassLoader().getResource("/" + scanPath.replaceAll("\\.", "/"));

		File classDir = new File(url.getFile());
		for (File file : classDir.listFiles()) {
			if (file.isDirectory()) {
				doGetClasses(scanPath + "." + file.getName());
			}
			if (!file.isFile() || !file.getName().endsWith(".class")) {
				continue;
			}

			String className = scanPath + "." + file.getName().replace(".class", "");
			classesSet.add(className);
		}

	}

	private void checkLoadResult(Properties props) {
		if (props == null || props.isEmpty()) {
			throw new RuntimeException("config load err");
		}
	}

	private Properties loadConfig2Props(ServletConfig config) {
		InputStream in = null;
		try {
			String configPath = config.getInitParameter(configrationLocation);
			in = this.getClass().getClassLoader().getResourceAsStream(configPath);
			Properties props = new Properties();
			try {
				props.load(in);
			} catch (IOException e) {
				e.printStackTrace();
			}
			return props;
		} finally {
			closeStreams(in);
		}
	}

	boolean emtpy(String strin) {
		return (strin == null || strin.isEmpty());
	}

	void closeStreams(InputStream... inputStreams) {
		if (inputStreams.length == 0)
			return;
		for (InputStream inputStream : inputStreams) {
			if (inputStream != null) {
				try {
					inputStream.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}
	}

	/**
	 * 覆写doGet
	 */
	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		this.doPost(req, resp);
	}

	/**
	 * 覆写doPost
	 */
	@Override
	protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		try {
			doDispatch(req, resp);
		} catch (Exception e) {
			resp.getWriter().println("500 Server Error " + Arrays.toString(e.getStackTrace()));
			e.printStackTrace();
		}
	}

	private void doDispatch(HttpServletRequest req, HttpServletResponse resp) throws Exception {
		String uri = req.getRequestURI();
		String contextPath = req.getContextPath();
		uri = uri.replace(contextPath, "").replaceAll("/+", "/");
		if (!urlMethod.containsKey(uri)) {
			resp.getWriter().println("404 NOT FOUND url=" + uri);
			return;
		}
		HanderMethod hm = urlMethod.get(uri);
		Object ret = doInvoke(hm, req, resp);
		if (ret != null) {
			resp.getWriter().println("Server Response:" + ret);
			return;
		}
	}

	private Object doInvoke(HanderMethod hm, HttpServletRequest req, HttpServletResponse resp)
			throws IllegalAccessException, IllegalArgumentException, InvocationTargetException {
		Method m = hm.method;
		Parameter[] parameters = m.getParameters();
		// m.getParameterTypes()
		Object[] args = new Object[m.getParameterCount()];
		for (int i = 0; i < parameters.length; i++) {
			Class<?> paramClazz = parameters[i].getType();
			if (paramClazz == HttpServletRequest.class) {
				args[i] = req;
			} else if (paramClazz == HttpServletResponse.class) {
				args[i] = resp;
			} else if (paramClazz == String.class) {
				// 获取参数注解，拿到参数名称
				Parameter parameter = parameters[i];
				MockRequestParam requestParam = parameter.getAnnotation(MockRequestParam.class);
				String paramName = requestParam.value();
				String value = req.getParameter(paramName);
				args[i] = value;
			}
		}
		Object instance = hm.instance;
		return m.invoke(instance, args);
	}

}
