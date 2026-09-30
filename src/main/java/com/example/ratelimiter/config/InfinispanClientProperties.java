package com.example.ratelimiter.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.infinispan.client.hotrod.configuration.ClientIntelligence;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * How to reach the Infinispan / Data Grid server that holds the buckets.
 *
 * @param servers      {@code host:port[;host:port...]}; on OpenShift the Data Grid Service, e.g.
 *                     {@code datagrid.my-namespace.svc:11222}
 * @param username     Hot Rod credentials; leave both unset for a server without authentication
 * @param password     see {@code username}
 * @param saslMechanism SASL mechanism when authenticating; the client's default (SCRAM-SHA-512)
 *                     matches what the Data Grid operator configures
 * @param intelligence {@code HASH_DISTRIBUTION_AWARE} sends each request straight to the key's owner
 *                     and needs the server pods' IPs to be reachable; {@code BASIC} talks only to the
 *                     addresses in {@code servers}, for when they are not (e.g. through a Route)
 * @param connectTimeout how long to wait for a connection; bounds how long a check can hang when
 *                     the server is gone
 * @param socketTimeout how long to wait for a reply
 */
@Validated
@ConfigurationProperties(prefix = "ratelimiter.infinispan")
public record InfinispanClientProperties(
        @DefaultValue("localhost:11222") @NotBlank String servers,
        String username,
        String password,
        String saslMechanism,
        @DefaultValue("HASH_DISTRIBUTION_AWARE") @NotNull ClientIntelligence intelligence,
        @DefaultValue("2s") @NotNull Duration connectTimeout,
        @DefaultValue("2s") @NotNull Duration socketTimeout,
        @DefaultValue @NotNull Tls tls) {

    /**
     * @param enabled        encrypt the Hot Rod connection
     * @param trustStore     CA bundle to trust; {@code null} means the JVM's default trust store. On
     *                       OpenShift, where Data Grid's certificate is signed by the service CA, that is
     *                       the pod's own {@code /var/run/secrets/kubernetes.io/serviceaccount/service-ca.crt}
     * @param trustStoreType {@code pem} for a CA bundle like the above, otherwise {@code pkcs12}/{@code jks}
     * @param sniHostName    the name the server certificate is checked against; defaults to the host of
     *                       the first entry in {@code servers}
     */
    public record Tls(
            @DefaultValue("false") boolean enabled,
            String trustStore,
            @DefaultValue("pem") String trustStoreType,
            String sniHostName) {
    }

    boolean authenticated() {
        return username != null && !username.isBlank();
    }

    /** The host part of the first server, which is also the name its certificate is issued for. */
    String firstServerHost() {
        String first = servers.split(";")[0].trim();
        int colon = first.lastIndexOf(':');
        return colon > 0 ? first.substring(0, colon) : first;
    }
}
