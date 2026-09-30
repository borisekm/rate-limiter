package com.example.ratelimiter;

import com.example.ratelimiter.config.HotRodConfig;
import com.example.ratelimiter.config.InfinispanClientProperties;
import org.infinispan.client.hotrod.RemoteCacheManager;
import org.infinispan.client.hotrod.configuration.ClientIntelligence;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.time.Duration;

/**
 * One real Infinispan server in a container, shared by every test in the JVM, so the tests run the
 * actual wire protocol - versions, {@code replaceWithVersion}, lifespans, SCRAM authentication, the
 * app creating its own cache - against the server generation Data Grid 8.x is built on.
 *
 * <p>Override the image with {@code -Dinfinispan.image=...} to try another server version.
 */
public final class HotRodTestServer {

    public static final String USERNAME = "ratelimiter";
    public static final String PASSWORD = "ratelimiter-test";

    private static final String IMAGE = System.getProperty("infinispan.image", "quay.io/infinispan/server:15.2");

    private static GenericContainer<?> container;

    private HotRodTestServer() {
    }

    /** Starts the container on first use; Testcontainers removes it when the JVM exits. */
    public static synchronized String address() {
        if (container == null) {
            GenericContainer<?> server = new GenericContainer<>(DockerImageName.parse(IMAGE))
                    .withEnv("USER", USERNAME)
                    .withEnv("PASS", PASSWORD)
                    .withExposedPorts(11222)
                    .waitingFor(Wait.forLogMessage(".*ISPN080001.*", 1))   // "Infinispan Server ... started"
                    .withStartupTimeout(Duration.ofMinutes(2));
            server.start();
            container = server;
        }
        return container.getHost() + ":" + container.getMappedPort(11222);
    }

    /** A client configured exactly as the app configures its own, via {@link HotRodConfig}. */
    public static RemoteCacheManager newClient() {
        return client(address());
    }

    /**
     * A client for any address - e.g. one where nothing listens. BASIC intelligence: the server
     * advertises its container-internal address, which the host may not be able to reach.
     */
    public static RemoteCacheManager client(String servers) {
        InfinispanClientProperties props = new InfinispanClientProperties(servers, USERNAME, PASSWORD, null,
                ClientIntelligence.BASIC, Duration.ofSeconds(1), Duration.ofSeconds(2),
                new InfinispanClientProperties.Tls(false, null, "pem", null));
        return new RemoteCacheManager(HotRodConfig.clientConfiguration(props).build());
    }

    /** An address nothing listens on. */
    public static String deadAddress() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return "127.0.0.1:" + socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
