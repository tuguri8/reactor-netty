/*
 * Copyright (c) 2011-2018 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.ipc.netty.channel;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.HttpMethod;
import org.junit.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.ipc.netty.ByteBufFlux;
import reactor.ipc.netty.DisposableServer;
import reactor.ipc.netty.FutureMono;
import reactor.ipc.netty.SocketUtils;
import reactor.ipc.netty.http.client.HttpClient;
import reactor.ipc.netty.http.server.HttpServer;
import reactor.test.StepVerifier;
import reactor.util.Logger;
import reactor.util.Loggers;

import static org.assertj.core.api.Assertions.assertThat;

public class ChannelOperationsHandlerTest {

	@Test
	public void publisherSenderOnCompleteFlushInProgress_1() {
		doTestPublisherSenderOnCompleteFlushInProgress(false);
	}

	@Test
	public void publisherSenderOnCompleteFlushInProgress_2() {
		doTestPublisherSenderOnCompleteFlushInProgress(true);
	}

	private void doTestPublisherSenderOnCompleteFlushInProgress(boolean useScheduler) {
		DisposableServer server =
				HttpServer.create()
				          .port(0)
				          .handle((req, res) ->
				                  req.receive()
				                     .asString()
				                     .doOnNext(System.err::println)
				                     .then(res.status(200).sendHeaders().then()))
				          .wiretap()
				          .bindNow(Duration.ofSeconds(30));

		Flux<String> flux = Flux.range(1, 257).map(count -> count + "");
		if (useScheduler) {
			flux.publishOn(Schedulers.single());
		}
		Mono<Integer> code =
				HttpClient.prepare()
				          .port(server.address().getPort())
				          .wiretap()
				          .post()
				          .uri("/")
				          .send(ByteBufFlux.fromString(flux))
				          .responseSingle((res, buf) -> Mono.just(res.status().code()))
				          .log();

		StepVerifier.create(code)
		            .expectNextMatches(c -> c == 200)
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		server.dispose();
	}

	@Test
	public void keepPrefetchSizeConstantEqualsWriteBufferLowHighWaterMark() {
		doTestPrefetchSize(1024, 1024);
	}

	@Test
	public void keepPrefetchSizeConstantDifferentWriteBufferLowHighWaterMark() {
		doTestPrefetchSize(0, 1024);
	}

	private void doTestPrefetchSize(int writeBufferLowWaterMark, int writeBufferHighWaterMark) {
		ChannelOperationsHandler handler = new ChannelOperationsHandler(null, null);

		EmbeddedChannel channel = new EmbeddedChannel(handler);
		channel.config()
		       .setWriteBufferLowWaterMark(writeBufferLowWaterMark)
		       .setWriteBufferHighWaterMark(writeBufferHighWaterMark);

		assertThat(handler.prefetch == (handler.inner.requested - handler.inner.produced)).isTrue();

		StepVerifier.create(FutureMono.deferFuture(() -> channel.writeAndFlush(Flux.range(0, 70))))
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		assertThat(handler.prefetch == (handler.inner.requested - handler.inner.produced)).isTrue();
	}

	@Test
	public void testChannelInactiveThrowsIOException() throws Exception {
		ExecutorService threadPool = Executors.newCachedThreadPool();

		int abortServerPort = SocketUtils.findAvailableTcpPort();
		ConnectionAbortServer abortServer = new ConnectionAbortServer(abortServerPort);

		threadPool.submit(abortServer);

		if(!abortServer.await(10, TimeUnit.SECONDS)){
			throw new IOException("Fail to start test server");
		}

		ByteBufFlux response =
				HttpClient.prepare()
				          .port(abortServerPort)
				          .wiretap()
				          .request(HttpMethod.GET)
				          .uri("/")
				          .send((req, out) -> out.sendString(Flux.just("a", "b", "c")))
				          .responseContent();

		StepVerifier.create(response.log())
		            .expectErrorMessage("Connection closed prematurely")
		            .verify();

		abortServer.close();
	}

	static final Logger log = Loggers.getLogger(ChannelOperationsHandlerTest.class);

	private static final class ConnectionAbortServer extends CountDownLatch
			implements Runnable {

		private final int                 port;
		private final ServerSocketChannel server;
		private volatile Thread           thread;

		private ConnectionAbortServer(int port) {
			super(1);
			this.port = port;
			try {
				server = ServerSocketChannel.open();
			}
			catch (IOException e) {
				throw new RuntimeException(e);
			}
		}

		@Override
		public void run() {
			try {
				server.configureBlocking(true);
				server.socket()
				      .bind(new InetSocketAddress(port));
				countDown();
				thread = Thread.currentThread();
				SocketChannel ch = server.accept();
				while (true) {
					int bytes = ch.read(ByteBuffer.allocate(256));
					if (bytes > 0) {
						ch.close();
						server.socket().close();
						return;
					}
				}
			}
			catch (IOException e) {
				log.error("", e);
			}
		}

		public void close() throws IOException {
			Thread thread = this.thread;
			if (thread != null) {
				thread.interrupt();
			}
			ServerSocketChannel server = this.server;
			if (server != null) {
				server.close();
			}
		}
	}

	@Test
	public void testIssue196() throws Exception {
		ExecutorService threadPool = Executors.newCachedThreadPool();

		int testServerPort = SocketUtils.findAvailableTcpPort();
		TestServer testServer = new TestServer(testServerPort);

		threadPool.submit(testServer);

		if(!testServer.await(10, TimeUnit.SECONDS)){
			throw new IOException("Fail to start test server");
		}

		HttpClient client =
		        HttpClient.newConnection()
		                  .port(testServerPort)
		                  .wiretap();

		Flux.range(0, 2)
		    .concatMap(i -> client.get()
		                        .uri("/205")
		                        .responseContent()
		                        .aggregate()
		                        .asString()
		                        .log())
		    .blockLast(Duration.ofSeconds(30));

		testServer.close();
	}

	private static final class TestServer extends CountDownLatch implements Runnable {

		private final    int                 port;
		private final    ServerSocketChannel server;
		private volatile Thread              thread;

		private TestServer(int port) {
			super(1);
			this.port = port;
			try {
				server = ServerSocketChannel.open();
			}
			catch (IOException e) {
				throw new RuntimeException(e);
			}
		}

		@Override
		public void run() {
			try {
				server.configureBlocking(true);
				server.socket()
				      .bind(new InetSocketAddress(port));
				countDown();
				thread = Thread.currentThread();
				while (true) {
					SocketChannel ch = server.accept();

					log.debug("Accepting {}", ch);

					byte[] buffer =
							("HTTP/1.1 205 Reset Content\r\n" +
							"Transfer-Encoding: chunked\r\n" +
							"\r\n" +
							"0\r\n" +
							"\r\n").getBytes(Charset.defaultCharset());

					int written = ch.write(ByteBuffer.wrap(buffer));
					if (written < 0) {
						throw new IOException("Cannot write to client");
					}
				}
			}
			catch (IOException e) {
				log.error("TestServer" ,e);
			}
		}

		public void close() throws IOException {
			Thread thread = this.thread;
			if (thread != null) {
				thread.interrupt();
			}
			ServerSocketChannel server = this.server;
			if (server != null) {
				server.close();
			}
		}
	}
}
