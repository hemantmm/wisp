package com.tcpchat;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class LinkPreviews {
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\"]+");
    private static final int MAX_BODY = 65_536;
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    Map<String, Object> forText(String text) {
        Matcher matcher = URL.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        String raw = trimTrailing(matcher.group());
        try {
            return fetch(URI.create(raw), 0);
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> fetch(URI uri, int redirects) throws Exception {
        if (!isPublicHttp(uri)) {
            return null;
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(2))
                .header("User-Agent", "WispLinkPreview")
                .header("Accept", "text/html,application/xhtml+xml")
                .GET()
                .build();
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        int status = response.statusCode();
        if (status >= 300 && status < 400 && redirects < 2) {
            String location = response.headers().firstValue("location").orElse("");
            if (response.body() != null) {
                response.body().close();
            }
            if (location.isBlank()) {
                return null;
            }
            return fetch(uri.resolve(location), redirects + 1);
        }
        if (status < 200 || status >= 300) {
            if (response.body() != null) {
                response.body().close();
            }
            return null;
        }
        String body;
        try (InputStream stream = response.body()) {
            body = stream == null ? "" : new String(stream.readNBytes(MAX_BODY), StandardCharsets.UTF_8);
        }
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("url", uri.toString());
        preview.put("site", uri.getHost());
        String title = meta(body, "og:title");
        if (title.isBlank()) {
            title = tag(body, "title");
        }
        if (!title.isBlank()) {
            preview.put("title", cut(unescape(title), 120));
        }
        String description = meta(body, "og:description");
        if (!description.isBlank()) {
            preview.put("description", cut(unescape(description), 200));
        }
        String image = meta(body, "og:image");
        if (!image.isBlank()) {
            URI imageUri = uri.resolve(unescape(image));
            if (isPublicHttp(imageUri) && "https".equalsIgnoreCase(imageUri.getScheme())) {
                preview.put("image", imageUri.toString());
            }
        }
        return preview;
    }

    private static boolean isPublicHttp(URI uri) throws Exception {
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            return false;
        }
        if (uri.getUserInfo() != null || uri.getHost() == null) {
            return false;
        }
        int port = uri.getPort();
        if (port != -1 && port != 80 && port != 443) {
            return false;
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local")) {
            return false;
        }
        for (InetAddress address : InetAddress.getAllByName(host)) {
            if (address.isAnyLocalAddress()
                    || address.isLoopbackAddress()
                    || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress()
                    || address.isMulticastAddress()) {
                return false;
            }
        }
        return true;
    }

    private static String meta(String html, String property) {
        Pattern pattern = Pattern.compile(
                "<meta[^>]*(?:property|name)=[\"']" + Pattern.quote(property)
                        + "[\"'][^>]*content=[\"']([^\"']*)[\"']"
                        + "|<meta[^>]*content=[\"']([^\"']*)[\"'][^>]*(?:property|name)=[\"']"
                        + Pattern.quote(property) + "[\"']",
                Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(html);
        if (!matcher.find()) {
            return "";
        }
        return matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
    }

    private static String tag(String html, String name) {
        Matcher matcher = Pattern.compile("<" + name + "[^>]*>([^<]*)</" + name + ">", Pattern.CASE_INSENSITIVE)
                .matcher(html);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String unescape(String value) {
        return value.replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .trim();
    }

    private static String cut(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String trimTrailing(String url) {
        return url.replaceAll("[.,!?;:)]+$", "");
    }
}
