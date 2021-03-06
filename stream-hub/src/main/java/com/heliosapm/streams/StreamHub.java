/**
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
 */
package com.heliosapm.streams;

import java.net.URL;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.actuate.endpoint.BeansEndpoint;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.EnableMBeanExport;
import org.springframework.context.annotation.ImportResource;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.ContextRefreshedEvent;

import com.heliosapm.utils.collections.Props;
import com.heliosapm.utils.url.URLHelper;

/**
 * <p>Title: StreamHub</p>
 * <p>Description: Boostrap class for StreamHub instances</p> 
 * @author Whitehead (nwhitehead AT heliosdev DOT org)
 * <p><code>com.heliosapm.streams.StreamHub</code></p>
 */

@SpringBootApplication
@ImportResource("classpath:streamhub.xml")
//@EnableAutoConfiguration 
@EnableMBeanExport
public class StreamHub implements Watcher {
	/** The current booted app context */
	private static ConfigurableApplicationContext appCtx = null;
	/** The current booted spring app */
	private static SpringApplication springApp = null;
	/** The spring boot launch thread */
	private static Thread springBootLaunchThread = null;
	/** Instance logger */
	protected final Logger log = LogManager.getLogger(StreamHub.class);
	
	@Autowired
	protected BeansEndpoint beansEndpoint;
	
	
	/** The original system props so we can reset */
	private static final Map<String, String> VIRGIN_SYS_PROPS = Collections.unmodifiableMap(new HashMap<String, String>(Props.asMap(System.getProperties())));
	
	/** The UTF character set */
	public static final Charset UTF8 = Charset.forName("UTF8");
	
	/*
	 * Admin props:  spring.boot.admin.url=http://localhost:8080
	 * App Version:  info.version=@project.version@
	 * spring.boot.admin.api-path:"api/applications"
	 */
	
	/**
	 * Creates a new StreamHub
	 * @param configXML The configuration XML
	 */
	public StreamHub(final URL configXML) {
//		appCtx = new GenericXmlApplicationContext();
//		appCtx.load(new UrlResource(configXML));
	}
	
	/**
	 * Parameterles ctor for spring boot
	 * Creates a new StreamHub
	 */
	public StreamHub() {
		/* No Op */
	}
	
	
	public StreamHub(final String[] args, final Properties p) {
		try {			
			System.setProperty("jmx.jmxmp.discovery.advertised", "jvm,kafka-consumer,kafka-producer,bytebufs");
			System.setProperty("spring.boot.admin.client.enabled", "true");
			System.setProperty("info.version", "1.0.1");
			System.setProperty("spring.boot.admin.client.name", "StreamHubNode");
//			System.setProperty("spring.config.name", "StreamHubNodeB");
			final String configLocation = p.getProperty("spring.config.location");
			System.out.println("Spring Config Location [" + configLocation + "]");
			System.setProperty("spring.config.location", configLocation);
			
			System.out.println("Booting StreamHub from spring.boot.admin.url [" + p.getProperty("spring.boot.admin.url") + "]");
//			System.getProperties().putAll(p);
			springApp = new SpringApplication(StreamHub.class);		
			springApp.addListeners(new ApplicationListener<ContextRefreshedEvent>(){
				@Override
				public void onApplicationEvent(final ContextRefreshedEvent event) {
					try {
						log.info("\n\t==================================================\n\tStreamHubNode Started [{}]\n\t==================================================\n", event.getApplicationContext().getId());
						
					} catch (Exception ex) {
						System.err.println("AppContext Startup Failure. Shutting down. Stack trace follows.");
						ex.printStackTrace(System.err);
						System.exit(-1);
					}						
				}
			});
			springApp.addListeners(new ApplicationListener<ContextClosedEvent>(){
				@Override
				public void onApplicationEvent(final ContextClosedEvent event) {
					log.info("\n\t==================================================\n\tStreamHubNode Stopped\n\t==================================================\n");					
				}
			});
//			springApp.setResourceLoader(resourceLoader);
//			springApp.setDefaultProperties(p);
			
//			springApp.setDefaultProperties(p);
//			StandardEnvironment environment = new StandardEnvironment();
			
			springBootLaunchThread = new Thread("SpringBootLaunchThread") {
				@Override
				public void run() {
					appCtx = springApp.run(args);
				}
			};
			springBootLaunchThread.setContextClassLoader(this.getClass().getClassLoader());
			springBootLaunchThread.setDaemon(true);
			springBootLaunchThread.start();
			final Thread stopThread = Thread.currentThread();
			Runtime.getRuntime().addShutdownHook(new Thread("StreamHubShutdownHook"){
				public void run() {
					stopThread.interrupt();
				}
			});
			try {
				Thread.currentThread().join();
			} catch (InterruptedException iex) {
				log("StopThread Interrupted. Shutting Down....");
			}
			
			
		} catch (Exception ex) {
			ex.printStackTrace(System.err);
			throw new RuntimeException("Failed to start StreamHub Instance", ex);
			
		}
		
	}
	
	SpringApplication getSpringApp() {
		return springApp;
	}
	
	ApplicationContext getAppContext() {
		return appCtx;
	}
	
	/**
	 * Formatted out printer
	 * @param fmt The format specifier
	 * @param args The arguments
	 */
	public static void log(final Object fmt, final Object...args) {
		System.out.println(String.format(fmt.toString(), args));
	}
	
	/**
	 * Formatted err printer
	 * @param fmt The format specifier
	 * @param args The arguments
	 */
	public static void loge(final Object fmt, final Object...args) {
		System.err.println(String.format(fmt.toString(), args));
	}
	
	
	/**
	 * Starts the stream hub
	 */
	public void start() {
		appCtx.refresh();
	}

	
//	JMXHelper.getAgentProperties().setProperty("sun.java.command", "StreamHubOK");
//	JMXHelper.getAgentProperties().setProperty("sun.rt.javaCommand" , "StreamHubOK");
//	System.setProperty("sun.java.command", "StreamHubOK");
//	System.setProperty("sun.rt.javaCommand" , "StreamHubOK");						
	
	
	private static void loadProps(final String[] args) {
		if(args!=null) {
			for(String s: args) {
				try {
					final Properties p = URLHelper.readProperties(URLHelper.toURL(s));
					if(!p.isEmpty()) {
						for(String key: p.stringPropertyNames()) {
							System.setProperty(key, p.getProperty(key, "").trim());
						}
					}
				} catch (Exception x) {/* No Op */}
			}
		}
	}
	
	/**
	 * Finds a command line arg value
	 * @param prefix The prefix
	 * @param defaultValue The default value if not found
	 * @param args The command line args to search
	 * @return the value
	 */
	private static int findArg(final String prefix, final int defaultValue, final String[] args) {
		final String s = findArg(prefix, (String)null, args);
		if(s==null) return defaultValue;
		try {
			return Integer.parseInt(s);
		} catch (Exception ex) {
			return defaultValue;
		}
	}
	
	
	/**
	 * Finds a command line arg value
	 * @param prefix The prefix
	 * @param defaultValue The default value if not found
	 * @param args The command line args to search
	 * @return the value
	 */
	private static String findArg(final String prefix, final String defaultValue, final String[] args) {
		for(String s: args) {
			if(s.startsWith(prefix)) {
				s = s.replace(prefix, "").trim();
				return s;
			}
		}
		return defaultValue;
	}
	
	
	/**
	 * Err prints the usage
	 */
	public static void usage() {
		System.err.println("Usage:\n\t//TODO: implements this");
	}

	@Override
	public void process(WatchedEvent arg0) {
		// TODO Auto-generated method stub
		
	}

}
