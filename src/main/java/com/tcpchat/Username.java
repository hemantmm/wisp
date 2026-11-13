package com.tcpchat;

import java.util.regex.Pattern;

final class Username {
    private static final Pattern PATTERN = Pattern.compile("^[A-Za-z0-9_]{1,20}$");

    private Username() {}

    static boolean isValid(String name) {
        return name != null && PATTERN.matcher(name).matches();
    }
}
