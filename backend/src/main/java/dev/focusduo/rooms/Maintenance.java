package dev.focusduo.rooms;

import dev.focusduo.ws.RoomSockets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class Maintenance {
    private static final Logger log = LoggerFactory.getLogger(Maintenance.class);
    private final RoomService rooms;
    private final RoomSockets sockets;
    public Maintenance(RoomService rooms, RoomSockets sockets) { this.rooms = rooms; this.sockets = sockets; }

    @Scheduled(fixedDelayString = "${focusduo.maintenance-delay-ms:1000}",
               initialDelayString = "${focusduo.maintenance-initial-delay-ms:1000}")
    public void refresh() {
        try { rooms.refreshDueRooms(); }
        catch (RuntimeException ex) { log.error("Round maintenance failed ({})", ex.getClass().getSimpleName()); }
        try { sockets.checkSessions(); }
        catch (RuntimeException ex) { log.error("Socket session maintenance failed ({})", ex.getClass().getSimpleName()); }
    }

    @Scheduled(fixedDelay = 30000, initialDelay = 30000)
    public void ping() { sockets.ping(); }
}
