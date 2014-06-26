package com.kixeye.chassis.transport.http;

import java.net.InetSocketAddress;

import org.apache.commons.lang.StringUtils;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlets.GzipFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.annotation.Order;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.web.context.ConfigurableWebApplicationContext;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.jetty9.InstrumentedQueuedThreadPool;
import com.kixeye.chassis.transport.SpringMvcConfiguration;
import com.kixeye.chassis.transport.http.HttpTransportConfiguration.HttpEnabledCondition;
import com.kixeye.chassis.transport.shared.JettyConnectorRegistry;
import com.kixeye.chassis.transport.swagger.SwaggerRegistry;

/**
 * Configures the Http transport.
 * 
 * @author ebahtijaragic
 */
@Configuration
@Conditional(HttpEnabledCondition.class)
@ComponentScan(basePackageClasses=HttpTransportConfiguration.class)
public class HttpTransportConfiguration {

    @Autowired(required = false)
    private MetricRegistry metricRegistry;

    @Value("${http.metrics.threadpool.enabled}")
    private boolean monitorThreadpool;

    @Value("${http.metrics.handler.enabled}")
    private boolean monitorHandler;

    @Bean(initMethod="start", destroyMethod="stop")
	@Order(0)
	public Server httpServer(
			@Value("${http.enabled:false}") boolean httpEnabled,
            @Value("${http.hostname:}") String httpHostname,
            @Value("${http.port:-1}") int httpPort,
            
			@Value("${https.enabled:false}") boolean httpsEnabled,
            @Value("${https.hostname:}") String httpsHostname,
            @Value("${https.port:-1}") int httpsPort,
            @Value("${https.selfSigned:false}") boolean selfSigned,
            @Value("${https.mutualSsl:false}") boolean mutualSsl,

			@Value("${https.keyStorePath:}") String keyStorePath,
			@Value("${https.keyStoreData:}") String keyStoreData,
			@Value("${https.keyStorePassword:}") String keyStorePassword,
			@Value("${https.keyManagerPassword:}") String keyManagerPassword,

			@Value("${https.trustStorePath:}") String trustStorePath,
			@Value("${https.trustStoreData:}") String trustStoreData,
			@Value("${https.trustStorePassword:}") String trustStorePassword,
			
			@Value("${https.excludedCipherSuites:}") String[] excludedCipherSuites,
			
			ConfigurableWebApplicationContext webApplicationContext) throws Exception {
    	
        // set up servlets
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.NO_SESSIONS | ServletContextHandler.NO_SECURITY);
		context.setErrorHandler(null);
		context.setWelcomeFiles(new String[] { "/" });
		
		// create a new child application context
		AnnotationConfigWebApplicationContext childApplicationContext = new AnnotationConfigWebApplicationContext();
		childApplicationContext.setDisplayName("httpTransport-webMvcContext");
		childApplicationContext.setParent(webApplicationContext);
		childApplicationContext.setServletContext(context.getServletContext());
		childApplicationContext.setEnvironment(webApplicationContext.getEnvironment());
		childApplicationContext.register(SpringMvcConfiguration.class);
		childApplicationContext.register(PropertySourcesPlaceholderConfigurer.class);
		childApplicationContext.refresh();
		
		// register swagger
		childApplicationContext.getBean(SwaggerRegistry.class).registerSwagger(context);
		
		// configure the spring mvc dispatcher
		DispatcherServlet dispatcher = new DispatcherServlet(childApplicationContext);

		// enable gzip
		context.addFilter(GzipFilter.class, "/*", null);
		
        // map application servlets
		context.addServlet(new ServletHolder(dispatcher), "/");

        // create the server
    	Server server;
    	if (metricRegistry == null || !monitorThreadpool){
        	server = new Server();

            server.setHandler(context);
        } else {
        	server = new Server(new InstrumentedQueuedThreadPool(metricRegistry));
        	
        	InstrumentedHandler instrumented = new InstrumentedHandler(metricRegistry);
            instrumented.setHandler(context);

            server.setHandler(instrumented);
        }
        
    	// set up connectors
    	if (httpEnabled) {
        	InetSocketAddress address = StringUtils.isBlank(httpHostname) ? new InetSocketAddress(httpPort) : new InetSocketAddress(httpHostname, httpPort);
        	
        	JettyConnectorRegistry.registerHttpConnector(server, address);
    	}
    	
    	if (httpsEnabled) {
        	InetSocketAddress address = StringUtils.isBlank(httpsHostname) ? new InetSocketAddress(httpsPort) : new InetSocketAddress(httpsHostname, httpsPort);
        	
        	JettyConnectorRegistry.registerHttpsConnector(server, address, selfSigned, mutualSsl, keyStorePath, keyStoreData, keyStorePassword, keyManagerPassword, 
        			trustStorePath, trustStoreData, trustStorePassword, excludedCipherSuites);
    	}
        
		return server;
	}
    
    /**
	 * A condition to check whether HTTP is enabled.
	 * 
	 * @author ebahtijaragic
	 */
	public static class HttpEnabledCondition implements Condition {
		public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
			return StringUtils.equalsIgnoreCase(context.getEnvironment().resolvePlaceholders("${http.enabled}"), "true") || StringUtils.equalsIgnoreCase(context.getEnvironment().resolvePlaceholders("${https.enabled}"), "true");
		}
	}
}