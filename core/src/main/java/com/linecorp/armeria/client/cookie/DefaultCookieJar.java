/*
 * Copyright 2020 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.client.cookie;

import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;

import com.linecorp.armeria.common.Cookie;
import com.linecorp.armeria.common.CookieBuilder;
import com.linecorp.armeria.common.Cookies;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.internal.common.util.ReentrantShortLock;

import io.netty.util.NetUtil;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;

/**
 * A default in-memory {@link CookieJar} implementation.
 */
final class DefaultCookieJar implements CookieJar {

    private final Object2LongOpenHashMap<Cookie> store;
    /**
     * Used to find cookies for a host that matches a domain. For example, if there is a domain example.com,
     * host example.com or foo.example.com will match all cookies in that entry.
     */
    private final Map<String, Set<Cookie>> filter;
    private final CookiePolicy cookiePolicy;
    private final ReentrantLock lock;

    DefaultCookieJar() {
        this(CookiePolicy.acceptOriginOnly());
    }

    DefaultCookieJar(CookiePolicy cookiePolicy) {
        this.cookiePolicy = cookiePolicy;
        store = new Object2LongOpenHashMap<>();
        filter = new HashMap<>();
        lock = new ReentrantShortLock();
    }

    @Override
    public Cookies get(URI uri) {
        requireNonNull(uri, "uri");
        if (store.isEmpty()) {
            return Cookies.of();
        }
        final String host = uri.getHost();
        final String path = uri.getPath().isEmpty() ? "/" : uri.getPath();
        final boolean secure = isSecure(uri.getScheme());
        final Set<Cookie> cookies = new HashSet<>();
        lock.lock();
        try {
            filterGet(cookies, host, path, secure);
        } finally {
            lock.unlock();
        }
        return Cookies.of(cookies);
    }

    @Override
    public void set(URI uri, Iterable<? extends Cookie> cookies, long createdTimeMillis) {
        requireNonNull(uri, "uri");
        requireNonNull(cookies, "cookies");
        lock.lock();
        try {
            for (Cookie cookie : cookies) {
                final Cookie ensuredCookie = ensureDomainAndPath(cookie, uri);
                // remove similar cookie if present
                store.removeLong(ensuredCookie);
                if ((ensuredCookie.maxAge() == Cookie.UNDEFINED_MAX_AGE || ensuredCookie.maxAge() > 0) &&
                    cookiePolicy.accept(uri, ensuredCookie)) {
                    store.put(ensuredCookie, createdTimeMillis);
                    final Set<Cookie> cookieSet = filter.computeIfAbsent(ensuredCookie.domain(),
                                                                         s -> new HashSet<>());

                    // remove the cookie with the same name, domain, and path if present
                    // https://datatracker.ietf.org/doc/html/rfc6265#page-24
                    final Cookie oldCookie = cookieSet.stream()
                                                      .filter(c -> isSameNameAndPath(c, ensuredCookie))
                                                      .findFirst()
                                                      .orElse(null);
                    if (oldCookie == null) {
                        cookieSet.add(ensuredCookie);
                        continue;
                    }
                    if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) &&
                        oldCookie.isHttpOnly()) {
                        // if the new cookie is received from a non-HTTP and the old cookie is http-only, skip
                        continue;
                    }
                    cookieSet.remove(oldCookie);
                    cookieSet.add(ensuredCookie);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public CookieState state(Cookie cookie, long currentTimeMillis) {
        requireNonNull(cookie, "cookie");
        lock.lock();
        final long createdTimeMillis = store.getOrDefault(cookie, Long.MIN_VALUE);
        lock.unlock();
        if (createdTimeMillis == Long.MIN_VALUE) {
            return CookieState.NON_EXISTENT;
        }
        return isExpired(cookie, createdTimeMillis, currentTimeMillis) ?
               CookieState.EXPIRED : CookieState.EXISTENT;
    }

    /**
     * Ensures this cookie has domain and path attributes, otherwise sets them to default values. If domain
     * is absent, the default is the request host, with {@code host-only} flag set to {@code true}. If path is
     * absent, the default is computed from the request path. See RFC 6265
     * <a href="https://datatracker.ietf.org/doc/html/rfc6265#section-5.3">5.3</a> and
     * <a href="https://datatracker.ietf.org/doc/html/rfc6265#section-5.1.4">5.1.4</a>
     */
    @VisibleForTesting
    Cookie ensureDomainAndPath(Cookie cookie, URI uri) {
        final boolean validDomain = !Strings.isNullOrEmpty(cookie.domain());
        final String cookiePath = cookie.path();
        final boolean validPath = !Strings.isNullOrEmpty(cookiePath) && cookiePath.charAt(0) == '/';
        if (validDomain && validPath) {
            return cookie;
        }
        final CookieBuilder cb = cookie.toBuilder();
        if (!validDomain) {
            cb.domain(uri.getHost()).hostOnly(true);
        }
        if (!validPath) {
            String path = uri.getPath();
            if (path.isEmpty()) {
                path = "/";
            } else {
                final int i = path.lastIndexOf('/');
                if (i > 0) {
                    path = path.substring(0, i);
                }
            }
            cb.path(path);
        }
        return cb.build();
    }

    private void filterGet(Set<Cookie> cookies, String host, String path, boolean secure) {
        final long currentTimeMillis = System.currentTimeMillis();
        for (Map.Entry<String, Set<Cookie>> entry : filter.entrySet()) {
            if (domainMatches(entry.getKey(), host)) {
                final Iterator<Cookie> it = entry.getValue().iterator();
                while (it.hasNext()) {
                    final Cookie cookie = it.next();
                    final long createdTimeMillis = store.getOrDefault(cookie, Long.MIN_VALUE);
                    if (createdTimeMillis == Long.MIN_VALUE) {
                        // the cookie has been removed from the main store so remove it from filter also
                        it.remove();
                        break;
                    }
                    if (isExpired(cookie, createdTimeMillis, currentTimeMillis)) {
                        it.remove();
                        store.removeLong(cookie);
                        break;
                    }
                    if (cookieMatches(cookie, host, path, secure)) {
                        cookies.add(cookie);
                    }
                }
            }
        }
    }

    private static boolean isSecure(String scheme) {
        final SessionProtocol parsedProtocol;
        if (scheme.indexOf('+') >= 0) {
            final Scheme parsedScheme = Scheme.tryParse(scheme);
            parsedProtocol = parsedScheme != null ? parsedScheme.sessionProtocol() : null;
        } else {
            parsedProtocol = SessionProtocol.find(scheme);
        }
        return parsedProtocol != null && parsedProtocol.isTls();
    }

    private static boolean isExpired(Cookie cookie, long createdTimeMillis, long currentTimeMillis) {
        final long timePassed = currentTimeMillis - createdTimeMillis;
        return cookie.maxAge() != Cookie.UNDEFINED_MAX_AGE && timePassed > cookie.maxAge() * 1000;
    }

    private static boolean domainMatches(String domain, String host) {
        if (domain.equalsIgnoreCase(host)) {
            return true;
        }
        return host.endsWith(domain) && host.charAt(host.length() - domain.length() - 1) == '.' &&
               !NetUtil.isValidIpV4Address(host) && !NetUtil.isValidIpV6Address(host);
    }

    private static boolean cookieMatches(Cookie cookie, String host, String path, boolean secure) {
        // if a cookie is host-only, host and domain have to be identical
        final boolean satisfiedHostOnly = !cookie.isHostOnly() || host.equalsIgnoreCase(cookie.domain());
        final boolean satisfiedSecure = secure || !cookie.isSecure();
        final String cookiePath = cookie.path();
        assert cookiePath != null;
        final boolean pathMatched = path.startsWith(cookiePath);
        return satisfiedHostOnly && satisfiedSecure && pathMatched;
    }

    private static boolean isSameNameAndPath(Cookie oldCookie, Cookie newCookie) {
        final String oldCookiePath = oldCookie.path();
        assert oldCookiePath != null;
        return oldCookie.name().equals(newCookie.name()) && oldCookiePath.equals(newCookie.path());
    }
}
