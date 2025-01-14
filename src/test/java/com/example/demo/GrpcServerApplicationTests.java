package com.example.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.UseMainMethod;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.springframework.grpc.client.ChannelBuilderOptions;
import org.springframework.grpc.client.GrpcChannelFactory;
import org.springframework.grpc.client.security.BasicAuthenticationInterceptor;
import org.springframework.grpc.test.LocalGrpcPort;
import org.springframework.test.annotation.DirtiesContext;

import com.example.demo.proto.HelloReply;
import com.example.demo.proto.HelloRequest;
import com.example.demo.proto.SimpleGrpc;

import io.grpc.Status.Code;
import io.grpc.StatusRuntimeException;

@SpringBootTest(useMainMethod = UseMainMethod.ALWAYS, properties = { "spring.grpc.server.port=0" })
public class GrpcServerApplicationTests {

	public static void main(String[] args) {
		new SpringApplicationBuilder(DemoApplication.class, ExtraConfiguration.class)
			.run("--spring.grpc.server.reactive.enabled=false");
	}

	@Autowired
	@Qualifier("stub")
	private SimpleGrpc.SimpleBlockingStub stub;

	@Autowired
	@Qualifier("basic")
	private SimpleGrpc.SimpleBlockingStub basic;


	@Test
	@DirtiesContext
	void contextLoads() {
	}

	@Test
	@DirtiesContext
	void unauthauthorized() {
		StatusRuntimeException exception = assertThrows(StatusRuntimeException.class,
				() -> basic.streamHello(HelloRequest.newBuilder().setName("Alien").build()).next());
		assertEquals(Code.PERMISSION_DENIED, exception.getStatus().getCode());
	}

	@Test
	@DirtiesContext
	void unauthenticated() {
		StatusRuntimeException exception = assertThrows(StatusRuntimeException.class,
				() -> stub.streamHello(HelloRequest.newBuilder().setName("Alien").build()).next());
		assertEquals(Code.UNAUTHENTICATED, exception.getStatus().getCode());
	}

	@Test
	@DirtiesContext
	void basic() {
		HelloReply response = basic.sayHello(HelloRequest.newBuilder().setName("Alien").build());
		assertEquals("Hello ==> Alien", response.getMessage());
	}

	@TestConfiguration
	static class ExtraConfiguration {

		@Bean
		@Lazy
		SimpleGrpc.SimpleBlockingStub basic(GrpcChannelFactory channels, @LocalGrpcPort int port) {
			return SimpleGrpc.newBlockingStub(channels.createChannel("0.0.0.0:" + port, ChannelBuilderOptions.defaults()
					.withInterceptors(List.of(new BasicAuthenticationInterceptor("user", "user")))));
		}

		@Bean
		@Lazy
		SimpleGrpc.SimpleBlockingStub stub(GrpcChannelFactory channels, @LocalGrpcPort int port) {
			return SimpleGrpc.newBlockingStub(channels.createChannel("0.0.0.0:" + port));
		}

	}

}
