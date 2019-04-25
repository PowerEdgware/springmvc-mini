package com.learn.mvcframework.v3.servlet;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.net.URL;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

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
import com.learn.mvcframework.v3.servlet.MockDispatcherServlet.HanderMethod.MethodParameter;

/**
 * 改进版的，1,支持url正则匹配，重构init 2,做一个url->handler的mapping 3, 支持请求参数动态匹配
 * 
 *
 */
public class MockDispatcherServlet extends HttpServlet {
	static class HanderMethod {
		String url;
		Method method;
		Object instance;
		Pattern pattern;

		MethodParameter[] methodParameters;

		public HanderMethod(String url, Method method, Object instance, Pattern pattern) {
			super();
			this.url = url;
			this.method = method;
			this.instance = instance;
			this.pattern = pattern;
			// 解析方法参数，方便后续请求匹配||获取方法名称和顺序映射
			methodParameters = initMethodParameter();
		}

		private MethodParameter[] initMethodParameter() {
			Parameter[] parameters = this.method.getParameters();
			MethodParameter[] methodParameters = new MethodParameter[parameters.length];
			for (int i = 0; i < parameters.length; i++) {
				// 获取注解
				Parameter parameter = parameters[i];
				MethodParameter methodParameter = new MethodParameter(i);
				methodParameter.parameterType = parameter.getType();
				//TODO 不一定有name,需要asm动态获取并缓存，有机会再研究asm读取字节码
				if (parameter.isAnnotationPresent(MockRequestParam.class)) {
					methodParameter.name = parameter.getAnnotation(MockRequestParam.class).value();
				}
				methodParameters[i] = methodParameter;

			}
			return methodParameters;
		}

		class MethodParameter {
			int index;
			Class<?> parameterType;
			String name;
			Executable executable;

			public MethodParameter(int i) {
				this.index = i;
				this.executable = HanderMethod.this.method;
			}

			public Class<?> getParameterType() {
				return this.parameterType;
			}

			public String getParameterName() {
				return this.name;
			}
		}
	}

	private static final long serialVersionUID = 1L;

	static String configrationLocation = "configrationLocation";

	List<HanderMethod> handerMapping = new ArrayList<>();
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

		// doInstance
		doInstance();

		// doAutowired
		doAutowired();

		// init hander mapping
		initHanderMapping();

		System.out.println("Mock MVC ends init at:" + LocalDateTime.now());
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
		// 获取handler
		HanderMethod hm = getHander(req);
		if (hm == null) {
			// NOT FOUND
			resp.getWriter().println("404 Not Found");
			return;
		}
		Object ret = doInvoke(hm, req, resp);
	
		// handler result
		processRetValue(ret, resp);
	}

	private Object doInvoke(HanderMethod hm, HttpServletRequest req, HttpServletResponse resp)
			throws IllegalAccessException, IllegalArgumentException, InvocationTargetException {
		// get method argument values
		Object[] args = getMethodArgValues(hm, req, resp);
	
		// do actual invoke
		return hm.method.invoke(hm.instance, args);
	}

	private void initHanderMapping() {
		Set<String> beanNameSet = beansMap.keySet();
		for (String beanName : beanNameSet) {
			Object instance = beansMap.get(beanName);
			Class<?> clazz = instance.getClass();
			if (!clazz.isAnnotationPresent(MockController.class)) {
				continue;
			}
			String baseUrl = "/";
			if (clazz.isAnnotationPresent(MockRequestMapping.class)) {
				baseUrl = clazz.getAnnotation(MockRequestMapping.class).value();
			}
			// process method MockAnnotation
			Method methods[] = clazz.getDeclaredMethods();
			for (Method method : methods) {
				if (!method.isAnnotationPresent(MockRequestMapping.class)) {
					continue;
				}
				baseUrl += method.getAnnotation(MockRequestMapping.class).value();
				baseUrl = baseUrl.replaceAll("/+", "/");
				Pattern pattern = Pattern.compile(baseUrl);

				HanderMethod handerMethod = new HanderMethod(baseUrl, method, instance, pattern);
				System.out.println("Mapping url='" + baseUrl + "' on method=" + method);

				handerMapping.add(handerMethod);
			}
		}
	}

	private void doAutowired() {
		beansMap.forEach((beanName, instance) -> {
			Class<?> clazz = instance.getClass();
			// just autowire controller
			if (clazz.isAnnotationPresent(MockController.class)) {
				Field[] fields = clazz.getDeclaredFields();
				for (Field field : fields) {
					if (!field.isAnnotationPresent(MockAutowired.class)) {
						continue;
					}
					// get instance
					String innerbeanName = field.getAnnotation(MockAutowired.class).value();
					if (emtpy(innerbeanName)) {
						innerbeanName = ensureBeanName(field.getType().getSimpleName());
					}
					// inject
					field.setAccessible(true);
					try {
						field.set(instance, beansMap.get(innerbeanName));
					} catch (IllegalArgumentException e) {
						e.printStackTrace();
					} catch (IllegalAccessException e) {
						e.printStackTrace();
					}
				}
			}
		});
	}

	private void doInstance() {
		classesSet.forEach(className -> {
			try {
				Class<?> clazz = Class.forName(className);
				if (clazz.isAnnotationPresent(MockController.class)) {// do
																		// resolver
																		// controller
					try {
						// 1. doInstance
						Object instance = clazz.newInstance();
						String beanName = clazz.getAnnotation(MockController.class).value();
						if (emtpy(beanName)) {
							beanName = ensureBeanName(clazz.getSimpleName());
						}
						beansMap.put(beanName, instance);
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
							beanName = ensureBeanName(clazz.getSimpleName());
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

	private String ensureBeanName(String simpleName) {
		if (!Character.isUpperCase(simpleName.charAt(0))) {
			return simpleName;
		}
		char[] chars = simpleName.toCharArray();
		chars[0] += 32;
		return new String(chars);
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

	private HanderMethod getHander(HttpServletRequest req) {
		String uri = req.getRequestURI();
		String contextPath = req.getContextPath();
		uri = uri.replace(contextPath, "").replaceAll("/+", "/");
		for (HanderMethod hm : handerMapping) {
			if (hm.pattern.matcher(uri).matches()) {
				return hm;
			}
		}
		return null;
	}

	private Object[] getMethodArgValues(HanderMethod hm, HttpServletRequest req, HttpServletResponse resp) {
		MethodParameter[] methodParameters = hm.methodParameters;
		Object[] args = new Object[methodParameters.length];
		for (int i = 0; i < methodParameters.length; i++) {
			MethodParameter methodParameter = methodParameters[i];
			if (HttpServletRequest.class.isAssignableFrom(methodParameter.parameterType)) {
				args[i] = req;
			} else if (HttpServletResponse.class.isAssignableFrom(methodParameter.parameterType)) {
				args[i] = resp;
			} else {
				String paramName = methodParameter.getParameterName();
				String[] values = req.getParameterValues(paramName);
				if (values != null && values.length == 1) {
					args[i] = convertValueIfNecessary(methodParameter.getParameterType(), values[0]);
				}
			}
		}
		return args;
	}

	private Object convertValueIfNecessary(Class<?> parameterType, String value) {
		if (parameterType == String.class) {
			return value;
		} else if (parameterType == int.class || parameterType == Integer.class) {
			return Integer.parseInt(value);
		}
		// and so on
		return null;
	}

	private void processRetValue(Object ret, HttpServletResponse resp) throws IOException {
		if (ret != null) {
			resp.getWriter().println("Server Response:" + ret);
			return;
		}
	}

}
