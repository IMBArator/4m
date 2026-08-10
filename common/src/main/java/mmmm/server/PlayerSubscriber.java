package mmmm.server;

import mmmm.core.transport.MediaTransport;

import java.util.UUID;

/**
 * One player, as {@code :core} is allowed to see them.
 *
 * <p>{@code :core} must not know what a {@code ServerPlayer} is (ADR-0006), so it holds this
 * instead. A UUID rather than the player object on purpose: sessions keep subscribers across ticks,
 * and holding the object would pin a disconnected player's entity — along with its level and
 * inventory — for as long as the radio kept playing. The transport resolves the UUID at send time,
 * which also means a stale subscriber is a no-op rather than a write to a dead connection.
 */
public record PlayerSubscriber(UUID uuid) implements MediaTransport.SubscriberId {
}
