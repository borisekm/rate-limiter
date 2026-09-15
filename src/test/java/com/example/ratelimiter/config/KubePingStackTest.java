package com.example.ratelimiter.config;

import org.jgroups.JChannel;
import org.jgroups.protocols.TCP;
import org.jgroups.protocols.kubernetes.KUBE_PING;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The OpenShift stack is the one piece of configuration nothing else exercises: it is selected only
 * when the app runs on Kubernetes, and a mistake in it - a misspelled protocol, a missing
 * jgroups-kubernetes jar, a bind port left at 0 - surfaces as pods that start happily and each form
 * a cluster of one. Building the channel parses the file and initialises every protocol, without
 * binding a socket or calling the API server.
 */
class KubePingStackTest {

    /** Must match ratelimiter.jgroups-config in the Kubernetes document of application.yml. */
    private static final String KUBERNETES_STACK = "jgroups-kubeping.xml";

    @Test
    void kubernetesStackDiscoversWithKubePingOverTcp() throws Exception {
        try (JChannel channel = new JChannel(KUBERNETES_STACK)) {
            KUBE_PING discovery = channel.getProtocolStack().findProtocol(KUBE_PING.class);
            assertThat(discovery)
                    .as("KUBE_PING must be the discovery protocol: a ClusterIP Service's DNS "
                        + "answers with the virtual IP, so DNS_PING would find nobody")
                    .isNotNull();
            assertThat(discovery.getValue("labels"))
                    .as("the label selector has to match this deployment's pods")
                    .isEqualTo("app.kubernetes.io/name=rate-limiter");

            TCP transport = channel.getProtocolStack().findProtocol(TCP.class);
            assertThat(transport)
                    .as("TCP transport: multicast is unavailable on OpenShift")
                    .isNotNull();
            assertThat(transport.getBindPort())
                    .as("KUBE_PING pings peers on the bind port, so it cannot be left to chance")
                    .isEqualTo(7800);
        }
    }
}
