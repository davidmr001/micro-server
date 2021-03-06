package com.aol.micro.server.servers.grizzly;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import javax.servlet.ServletContextListener;
import javax.servlet.ServletRequestListener;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.experimental.Wither;

import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.grizzly.http.server.NetworkListener;
import org.glassfish.grizzly.http.server.accesslog.AccessLogBuilder;
import org.glassfish.grizzly.servlet.WebappContext;
import org.pcollections.PStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aol.cyclops.util.ExceptionSoftener;
import com.aol.micro.server.ErrorCode;
import com.aol.micro.server.config.SSLProperties;
import com.aol.micro.server.module.WebServerProvider;
import com.aol.micro.server.servers.AccessLogLocationBean;
import com.aol.micro.server.servers.FilterConfigurer;
import com.aol.micro.server.servers.JaxRsServletConfigurer;
import com.aol.micro.server.servers.ServerApplication;
import com.aol.micro.server.servers.ServletConfigurer;
import com.aol.micro.server.servers.ServletContextListenerConfigurer;
import com.aol.micro.server.servers.model.AllData;
import com.aol.micro.server.servers.model.FilterData;
import com.aol.micro.server.servers.model.ServerData;
import com.aol.micro.server.servers.model.ServletData;

@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class GrizzlyApplication implements ServerApplication {

	private final Logger logger = LoggerFactory.getLogger(getClass());

	@Getter
	private final ServerData serverData;

	private final PStack<FilterData> filterData;
	private final PStack<ServletData> servletData;
	private final PStack<ServletContextListener> servletContextListenerData;
	private final PStack<ServletRequestListener> servletRequestListenerData;
	@Wither
	private final SSLProperties SSLProperties;

	public GrizzlyApplication(AllData serverData) {
		this.serverData = serverData.getServerData();
		this.filterData = serverData.getFilterDataList();
		this.servletData = serverData.getServletDataList();
		this.servletContextListenerData = serverData.getServletContextListeners();
		this.servletRequestListenerData = serverData.getServletRequestListeners();
		this.SSLProperties = null;
	}

	public void run(CompletableFuture start,  JaxRsServletConfigurer jaxRsConfigurer, CompletableFuture end) {

		WebappContext webappContext = new WebappContext("WebappContext", "");

		new ServletContextListenerConfigurer(serverData, servletContextListenerData, servletRequestListenerData);

		
		jaxRsConfigurer.addServlet(this.serverData,webappContext);

		new ServletConfigurer(serverData, servletData).addServlets(webappContext);

		new FilterConfigurer(serverData, this.filterData).addFilters(webappContext);

		addListeners(webappContext);

		HttpServer httpServer = HttpServer.createSimpleServer(null, "0.0.0.0", serverData.getPort());
		serverData.getModule().getServerConfigManager().accept(new WebServerProvider(httpServer));
		addAccessLog(httpServer);
		if (SSLProperties != null)
			this.createSSLListener(serverData.getPort());

		startServer(webappContext, httpServer, start, end);
	}

	private void startServer(WebappContext webappContext, HttpServer httpServer, CompletableFuture start, CompletableFuture end) {
		webappContext.deploy(httpServer);
		try {
			logger.info("Starting application {} on port {}", serverData.getModule().getContext(), serverData.getPort());
			logger.info("Browse to http://localhost:{}/{}/application.wadl", serverData.getPort(), serverData.getModule().getContext());
			logger.info("Configured resource classes :-");
			serverData.extractResources()
					.forEach(t -> logger.info(t.v1() + " : " + "http://localhost:" + serverData.getPort() + "/" + serverData.getModule().getContext() + t.v2()));
			;
			httpServer.start();
			start.complete(true);
			end.get();

		} catch (IOException e) {
			throw ExceptionSoftener.throwSoftenedException(e);
		} catch (ExecutionException e) {
			throw ExceptionSoftener.throwSoftenedException(e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw ExceptionSoftener.throwSoftenedException(e);
		} finally {
			httpServer.stop();
		}
	}

	private void addAccessLog(HttpServer httpServer) {
		try {
			String accessLogLocation = serverData.getRootContext().getBean(AccessLogLocationBean.class).getAccessLogLocation();

			accessLogLocation = accessLogLocation + "/" + replaceSlash(serverData.getModule().getContext()) + "-access.log";
			final AccessLogBuilder builder = new AccessLogBuilder(accessLogLocation);

			builder.rotatedDaily();
			builder.rotationPattern("yyyy-MM-dd");
			builder.instrument(httpServer.getServerConfiguration());
		} catch (Exception e) {
			logger.error(ErrorCode.SERVER_STARTUP_FAILED_TO_CREATE_ACCESS_LOG.toString() + ": " + e.getMessage());
			if (e.getCause() != null)
				logger.error("CAUSED BY: " + ErrorCode.SERVER_STARTUP_FAILED_TO_CREATE_ACCESS_LOG.toString() + ": " + e.getCause().getMessage());

		}

	}
	

	private NetworkListener createSSLListener(int port) {

		SSLConfigurationBuilder sslBuilder = new SSLConfigurationBuilder();
		NetworkListener listener = new NetworkListener("grizzly", "0.0.0.0", Integer.valueOf(port));
		listener.getFileCache().setEnabled(false);

		listener.setSecure(true);
		listener.setSSLEngineConfig(sslBuilder.build(SSLProperties));

		return listener;
	}

	private void addListeners(WebappContext webappContext) {
		new ServletContextListenerConfigurer(serverData, servletContextListenerData, servletRequestListenerData).addListeners(webappContext);
	}

	private String replaceSlash(String context) {
		if (context != null && context.contains("/")) {
			return context.substring(0, context.indexOf("/"));
		}
		return context;
	}

}
