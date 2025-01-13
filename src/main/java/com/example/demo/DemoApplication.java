package com.example.demo;

import java.util.stream.Collectors;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.grpc.server.GlobalServerInterceptor;
import org.springframework.grpc.server.exception.CompositeGrpcExceptionHandler;
import org.springframework.grpc.server.exception.GrpcExceptionHandler;
import org.springframework.security.access.AccessDeniedException;

import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCall.Listener;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import reactor.core.publisher.Mono;

@SpringBootApplication
public class DemoApplication {

	public static void main(String[] args) {
		SpringApplication.run(DemoApplication.class, args);
	}

	@Bean
	@GlobalServerInterceptor
	public CustomInterceptor customizer(ObjectProvider<GrpcExceptionHandler> exceptionHandlers) {
		return new CustomInterceptor(new CompositeGrpcExceptionHandler(
				exceptionHandlers.orderedStream().collect(Collectors.toList()).toArray(new GrpcExceptionHandler[0])));
	}

}

class CustomInterceptor implements ServerInterceptor {

	private GrpcExceptionHandler exceptionHandler;

	public CustomInterceptor(GrpcExceptionHandler exceptionHandler) {
		this.exceptionHandler = exceptionHandler;
	}

	@Override
	public <ReqT, RespT> Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call, Metadata headers,
			ServerCallHandler<ReqT, RespT> next) {
		return new CustomListener<ReqT, RespT>(next.startCall(call, headers), call, headers, this.exceptionHandler);
	}
}

class CustomListener<ReqT, RespT> extends ForwardingServerCallListener<ReqT> {

	private static Log log = LogFactory.getLog(CustomListener.class);

	private Metadata headers;
	private Listener<ReqT> delegate;

	private GrpcExceptionHandler exceptionHandler;

	private ServerCall<ReqT, RespT> call;

	CustomListener(Listener<ReqT> delegate, ServerCall<ReqT, RespT> call, Metadata headers,
			GrpcExceptionHandler exceptionHandler) {
		this.call = call;
		this.headers = headers;
		this.delegate = delegate;
		this.exceptionHandler = exceptionHandler;
	}

	@Override
	protected Listener<ReqT> delegate() {
		return this.delegate;
	}

	@Override
	public void onReady() {
		log.info("Headers: " + this.headers.keys());
		Mono.just(headers).map(metadata -> {
			if (metadata.keys().contains("x-grpc-test")) {
				throw new AccessDeniedException("Access Denied Dummy");
			}
			return metadata;
		})
				.subscribe(result -> {
					log.info("Success: " + result.keys());
				}, result -> {
					log.info("Error: " + result.getMessage(), result);
					this.call.close(this.exceptionHandler.handleException(result), new Metadata());
				}, () -> {
					log.info("Completed");
					super.onReady();
				});
	}
}