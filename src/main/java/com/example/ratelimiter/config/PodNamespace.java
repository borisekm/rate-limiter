package com.example.ratelimiter.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Tells KUBE_PING which namespace to look for peers in.
 *
 * <p>KUBE_PING reads the namespace from the {@code KUBERNETES_NAMESPACE} system property or
 * environment variable and otherwise falls back to {@code "default"}, where it would find no peers
 * and every pod would form a cluster of one. The usual fix is a downward-API environment variable
 * in the pod spec - but the Deployment comes from a central chart we do not own, so this reads the
 * namespace out of the ServiceAccount token directory instead, which is mounted into every pod
 * regardless of how the pod spec was written.
 *
 * <p>Precedence is left intact: an explicitly set property or environment variable wins, and off
 * Kubernetes the file is simply absent and nothing is set.
 */
final class PodNamespace {

    private static final Logger log = LoggerFactory.getLogger(PodNamespace.class);

    /** KUBE_PING's own property name, which it resolves as a system property or an env var. */
    static final String NAMESPACE_PROPERTY = "KUBERNETES_NAMESPACE";

    private static final Path SERVICE_ACCOUNT_NAMESPACE =
            Path.of("/var/run/secrets/kubernetes.io/serviceaccount/namespace");

    private PodNamespace() {
    }

    /** Sets {@value #NAMESPACE_PROPERTY} from the mounted ServiceAccount, if it is not set already. */
    static void publishAsSystemProperty() {
        if (System.getProperty(NAMESPACE_PROPERTY) != null || System.getenv(NAMESPACE_PROPERTY) != null) {
            return;
        }
        if (!Files.isReadable(SERVICE_ACCOUNT_NAMESPACE)) {
            return;
        }
        try {
            String namespace = Files.readString(SERVICE_ACCOUNT_NAMESPACE).trim();
            if (!namespace.isEmpty()) {
                System.setProperty(NAMESPACE_PROPERTY, namespace);
                log.info("Discovering cluster members in namespace {}", namespace);
            }
        } catch (Exception e) {
            // Not fatal here: JGroups will say so much more loudly when discovery finds nobody.
            log.warn("Could not read {}; KUBE_PING will fall back to its default namespace",
                    SERVICE_ACCOUNT_NAMESPACE, e);
        }
    }
}
