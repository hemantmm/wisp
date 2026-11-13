package com.tcpchat;

import java.util.Locale;
import java.util.regex.Pattern;

final class Username {
    private static final Pattern PATTERN = Pattern.compile("^[A-Za-z0-9_]{1,20}$");
    private static final Pattern TOKEN = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");

    private Username() {}

    static boolean isValid(String name) {
        return name != null && PATTERN.matcher(name).matches();
    }

    static boolean isValidToken(String token) {
        return token != null && TOKEN.matcher(token).matches();
    }

    static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    static boolean same(String left, String right) {
        return left != null && right != null && key(left).equals(key(right));
    }
}
