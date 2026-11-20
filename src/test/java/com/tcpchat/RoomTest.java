package com.tcpchat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RoomTest {

    public static void main(String[] args) {
        System.out.println("Running Room & RoomManager verification tests...");
        testNormalizeAndValidation();
        testRoomOpenAndLifetime();
        testRoomHistoryIsolation();
        testLastPersonLeavesClosesRoom();
        testMultiRoomManager();
        System.out.println("All verification tests passed successfully!");
    }

    private static void testNormalizeAndValidation() {
        check("general".equals(Room.normalize(null)), "null should normalize to general");
        check("general".equals(Room.normalize("")), "empty should normalize to general");
        check("general".equals(Room.normalize("   ")), "spaces should normalize to general");
        check("general".equals(Room.normalize("#")), "# should normalize to general");
        check("engineering".equals(Room.normalize("#engineering")), "#engineering should normalize to engineering");
        check("engineering".equals(Room.normalize("engineering")), "engineering should normalize to engineering");
        check("team-alpha_1".equals(Room.normalize("#team-alpha_1")), "valid slug should normalize");
        check(Room.normalize("invalid with spaces") == null, "spaces in name should be invalid");
        check(Room.normalize("name$#special!") == null, "special chars should be invalid");
        check(Room.normalize("a".repeat(35)) == null, "names > 30 chars should be invalid");
        System.out.println("✓ Room.normalize and validation checks passed");
    }

    private static void testRoomOpenAndLifetime() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            Room room = new Room("test", scheduler, null);
            check(!room.isOpen(), "Room should initially be closed");

            check(room.open("alice", "1h"), "Room open with 1h should succeed");
            check(room.isOpen(), "Room should be open");
            check("1h".equals(room.roomFields().get("lifetime")), "Lifetime should be 1h");
            check("alice".equals(room.roomFields().get("creator")), "Creator should be alice");
            check(room.roomFields().get("expiresAt") != null, "ExpiresAt should be set");

            // Subsequent open should preserve initial lifetime
            check(room.open("bob", "24h"), "Second open should return true without changing lifetime");
            check("1h".equals(room.roomFields().get("lifetime")), "Lifetime must remain 1h");
            System.out.println("✓ Room open and lifetime checks passed");
        } finally {
            scheduler.shutdownNow();
        }
    }

    private static void testRoomHistoryIsolation() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            Room room = new Room("standup", scheduler, null);
            Map<String, Object> msg = Map.of("id", "msg-1", "from", "alice", "text", "Morning team!");

            check(!room.remember(msg), "Unopened room cannot remember messages");
            room.open("alice", "last");
            check(room.remember(msg), "Open room remembers messages");

            List<Map<String, Object>> history = room.history().forUser("alice");
            check(history.size() == 1, "Alice should see 1 message");
            check("Morning team!".equals(history.get(0).get("text")), "Message text matches");
            System.out.println("✓ Room history checks passed");
        } finally {
            scheduler.shutdownNow();
        }
    }

    private static void testLastPersonLeavesClosesRoom() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            AtomicBoolean closed = new AtomicBoolean(false);
            Room room = new Room("ephemeral", scheduler, r -> closed.set(true));
            room.open("alice", "last");

            List<Map<String, Object>> received = new ArrayList<>();
            ChatConnection conn = received::add;

            ClientRegistry.Claim claim = room.registry().claim("alice", "token-12345", conn);
            check(claim == ClientRegistry.Claim.NEW, "Alice claim should be NEW");
            check(room.registry().usernames().size() == 1, "User list should have 1 user");

            // Alice departs
            room.departIfStill("alice", conn);
            check(!room.isOpen(), "Room with 'last' lifetime must close when empty");
            check(closed.get(), "Closure callback must have fired");
            check(room.registry().usernames().isEmpty(), "User list should be empty");
            System.out.println("✓ Last person leaves closure checks passed");
        } finally {
            scheduler.shutdownNow();
        }
    }

    private static void testMultiRoomManager() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            RoomManager manager = new RoomManager(scheduler);
            Room general = manager.getOrCreate("general");
            Room engineering = manager.getOrCreate("engineering");

            check(general != null && engineering != null, "Rooms should be created");
            check(general != engineering, "Rooms must be distinct instances");

            general.open("alice", "1h");
            engineering.open("bob", "24h");

            ChatConnection conn = payload -> {};
            general.registry().claim("alice", "token-00000001", conn);
            engineering.registry().claim("alice", "token-00000001", conn);
            engineering.registry().claim("bob", "token-00000002", conn);

            check(general.registry().usernames().size() == 1, "General should have 1 user");
            check(engineering.registry().usernames().size() == 2, "Engineering should have 2 users");

            List<Map<String, Object>> active = manager.listActiveRooms();
            check(active.size() == 2, "There should be 2 active rooms");
            check("engineering".equals(active.get(0).get("room")), "Engineering should be listed");
            check("general".equals(active.get(1).get("room")), "General should be listed");

            // Closing engineering shouldn't touch general
            engineering.endRoom(false);
            check(manager.get("engineering") == null, "Engineering should be unmapped after end");
            check(manager.get("general") != null, "General should still be active");
            check(manager.listActiveRooms().size() == 1, "Only 1 active room remains");
            System.out.println("✓ Multi-room manager isolation checks passed");
        } finally {
            scheduler.shutdownNow();
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError("Test failed: " + message);
        }
    }
}
