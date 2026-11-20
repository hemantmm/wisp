package com.tcpchat;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;

public final class RoomManager {
    private final ConcurrentHashMap<String, Room> rooms = new ConcurrentHashMap<>();
    private final ScheduledExecutorService departures;

    public RoomManager(ScheduledExecutorService departures) {
        this.departures = departures;
    }

    public Room getOrCreate(String rawName) {
        String name = Room.normalize(rawName);
        if (name == null) {
            return null;
        }
        return rooms.computeIfAbsent(name, key -> new Room(key, departures, this::onRoomClosed));
    }

    public Room get(String rawName) {
        String name = Room.normalize(rawName);
        if (name == null) {
            return null;
        }
        return rooms.get(name);
    }

    private void onRoomClosed(Room room) {
        rooms.remove(room.name(), room);
    }

    public List<Map<String, Object>> listActiveRooms() {
        return rooms.values().stream()
                .filter(Room::isOpen)
                .map(Room::summary)
                .sorted(Comparator.comparing(m -> String.valueOf(m.get("room"))))
                .toList();
    }
}
