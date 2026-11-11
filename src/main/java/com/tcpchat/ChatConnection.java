package com.tcpchat;

import java.util.Map;

interface ChatConnection {
    void send(Map<String, Object> payload);
}
