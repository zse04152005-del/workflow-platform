package com.workflowplatform.controlplane.security;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** SSRF policy validating every URI, DNS answer, redirect and response budget. */
public final class OutboundRequestPolicy {

    private static final int MAXIMUM_REDIRECTS = 5;
    private static final Set<Integer> ALLOWED_PORTS = Set.of(-1, 443, 8443);
    private final Set<String> allowedHosts;
    private final long maximumResponseBytes;

    public OutboundRequestPolicy(Set<String> hosts, long responseBytes) {
        allowedHosts =
                hosts.stream()
                        .map(host -> host.toLowerCase(Locale.ROOT))
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
        maximumResponseBytes = responseBytes;
        if (allowedHosts.isEmpty() || maximumResponseBytes < 1) {
            throw new IllegalArgumentException("outbound policy is incomplete");
        }
    }

    public void validate(URI uri, List<InetAddress> addresses) {
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!"https".equals(uri.getScheme())
                || uri.getUserInfo() != null
                || !ALLOWED_PORTS.contains(uri.getPort())
                || !allowedHosts.contains(host)
                || addresses.isEmpty()) {
            throw new SecurityException("outbound endpoint denied");
        }
        if (addresses.stream().anyMatch(OutboundRequestPolicy::forbiddenAddress)) {
            throw new SecurityException("outbound DNS resolved to a forbidden address");
        }
    }

    public void validateResponseSize(long bytes) {
        if (bytes < 0 || bytes > maximumResponseBytes) {
            throw new SecurityException("outbound response size denied");
        }
    }

    public void validateRedirects(List<ResolvedHop> hops) {
        if (hops.size() > MAXIMUM_REDIRECTS) {
            throw new SecurityException("too many outbound redirects");
        }
        for (ResolvedHop hop : hops) {
            validate(hop.uri(), hop.addresses());
        }
    }

    private static boolean forbiddenAddress(InetAddress address) {
        return address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress();
    }

    public static InetAddress address(String value) {
        try {
            return InetAddress.getByName(value);
        } catch (UnknownHostException exception) {
            throw new IllegalArgumentException("invalid IP address", exception);
        }
    }

    public record ResolvedHop(URI uri, List<InetAddress> addresses) {
        public ResolvedHop {
            addresses = List.copyOf(addresses);
        }
    }
}
