package com.example.demo;

import java.net.URI;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.reactivestreams.Publisher;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.grpc.server.GlobalServerInterceptor;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.http.server.reactive.AbstractServerHttpRequest;
import org.springframework.http.server.reactive.AbstractServerHttpResponse;
import org.springframework.http.server.reactive.SslInfo;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.WebFilterChainProxy;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.server.adapter.DefaultServerWebExchange;
import org.springframework.web.server.i18n.AcceptHeaderLocaleContextResolver;
import org.springframework.web.server.session.DefaultWebSessionManager;

import com.google.common.util.concurrent.ThreadFactoryBuilder;

import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.Metadata.Key;
import io.grpc.ServerCall;
import io.grpc.ServerCall.Listener;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@SpringBootApplication
public class DemoApplication {

	public static void main(String[] args) {
		System.setProperty("spring.grpc.server.reactive.enabled", "false");
		SpringApplication.run(DemoApplication.class, args);
	}

	@Bean
	public SecurityWebFilterChain security(ServerHttpSecurity http) {
		return http.authorizeExchange(exchanges -> exchanges
				.anyExchange().authenticated())
				.httpBasic(Customizer.withDefaults())
				.formLogin(Customizer.withDefaults())
				.csrf(csrf -> csrf.disable())
				.build();
	}

	@Bean
	@GlobalServerInterceptor
	@Order(Ordered.HIGHEST_PRECEDENCE)
	public CustomInterceptor customizer(WebFilterChainProxy filterChain) {
		return new CustomInterceptor(filterChain);
	}

	static ThreadFactory getThreadFactory(String nameFormat, boolean daemon) {
		return new ThreadFactoryBuilder()
				.setDaemon(daemon)
				.setNameFormat(nameFormat)
				.build();
	}
}

class CustomInterceptor implements ServerInterceptor {

	private static Log log = LogFactory.getLog(CustomInterceptor.class);

	private WebFilterChainProxy filterChain;

	public CustomInterceptor(WebFilterChainProxy filterChain) {
		this.filterChain = filterChain;
	}

	@Override
	public <ReqT, RespT> Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call, Metadata headers,
			ServerCallHandler<ReqT, RespT> next) {
		AtomicReference<SecurityContext> securityContext = new AtomicReference<>();
		String path = "/" + call.getMethodDescriptor().getFullMethodName();
		filterChain.filter(new DefaultServerWebExchange(new FakeServerHttpRequest(URI.create(path), headers(headers)),
				new FakeServerHttpResponse(),
				new DefaultWebSessionManager(),
				ServerCodecConfigurer.create(), new AcceptHeaderLocaleContextResolver()), new WebFilterChain() {

					@Override
					public Mono<Void> filter(ServerWebExchange exchange) {
						return ReactiveSecurityContextHolder.getContext().flatMap(context -> {
							securityContext.set(context);
							return Mono.empty();
						});
					}

				}).block();
		SecurityContext context = securityContext.get();
		log.info("Context: " + context);
		if (context == null) {
			call.close(Status.PERMISSION_DENIED, new Metadata());
		} else {
			SecurityContextHolder.setContext(context);
		}
		return new CustomListener<ReqT>(next.startCall(call, headers));
	}

	private MultiValueMap<String, String> headers(Metadata headers) {
		HttpHeaders result = new HttpHeaders();
		for (String key : headers.keys()) {
			for (String value : headers.getAll(Key.of(key, Metadata.ASCII_STRING_MARSHALLER))) {
				result.add(key, value);
			}
		}
		return result;
	}
}

class CustomListener<ReqT> extends ForwardingServerCallListener.SimpleForwardingServerCallListener<ReqT> {

	private static Log log = LogFactory.getLog(CustomListener.class);

	public CustomListener(ServerCall.Listener<ReqT> delegate) {
		super(delegate);
	}

	@Override
	public void onComplete() {
		super.onComplete();
		log.info("Completed " + SecurityContextHolder.getContext());
		SecurityContextHolder.clearContext();
	}

	@Override
	public void onCancel() {
		super.onCancel();
		log.info("Canceled " + SecurityContextHolder.getContext());
		SecurityContextHolder.clearContext();
	}

}

class FakeServerHttpResponse extends AbstractServerHttpResponse {

	public FakeServerHttpResponse() {
		super(new DefaultDataBufferFactory(), new HttpHeaders());
	}

	@Override
	public <T> T getNativeResponse() {
		return null;
	}

	@Override
	protected Mono<Void> writeWithInternal(Publisher<? extends DataBuffer> body) {
		return Mono.empty();
	}

	@Override
	protected Mono<Void> writeAndFlushWithInternal(Publisher<? extends Publisher<? extends DataBuffer>> body) {
		return Mono.empty();
	}

	@Override
	protected void applyStatusCode() {
	}

	@Override
	protected void applyHeaders() {
	}

	@Override
	protected void applyCookies() {
	}
}

class FakeServerHttpRequest extends AbstractServerHttpRequest {

	FakeServerHttpRequest(URI uri, MultiValueMap<String, String> headers) {
		super(HttpMethod.POST, uri, null, headers);
	}

	@Override
	public Flux<DataBuffer> getBody() {
		return Flux.empty();
	}

	@Override
	protected MultiValueMap<String, HttpCookie> initCookies() {
		return new LinkedMultiValueMap<>();
	}

	@Override
	protected SslInfo initSslInfo() {
		return null;
	}

	@Override
	public <T> T getNativeRequest() {
		return null;
	}
}