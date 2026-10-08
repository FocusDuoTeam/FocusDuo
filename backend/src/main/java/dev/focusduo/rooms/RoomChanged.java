package dev.focusduo.rooms;

import dev.focusduo.api.Api;

/** Raised inside the transaction; transports must consume only AFTER_COMMIT. */
public record RoomChanged(Api.RoomSnapshot snapshot) {}
