package org.me.advancedsky.messaging;

import org.me.advancedsky.exceptions.*;

import java.util.Map;
import java.util.function.Function;

import static java.util.Map.entry;

/** The current protocol's explicit error types, with one factory per type. */
final class RemoteErrors {
    private static final Map<String, Function<String, RuntimeException>> TYPES = Map.ofEntries(
            entry(IllegalStateException.class.getName(), IllegalStateException::new),
            entry(IllegalArgumentException.class.getName(), IllegalArgumentException::new),
            entry(NullPointerException.class.getName(), NullPointerException::new),
            entry(RuntimeException.class.getName(), RuntimeException::new),
            entry(BiomeNotUnlockedException.class.getName(), BiomeNotUnlockedException::new),
            entry(CannotBanIslandPlayerException.class.getName(), ignored -> new CannotBanIslandPlayerException()),
            entry(CannotCoopIslandPlayerException.class.getName(), ignored -> new CannotCoopIslandPlayerException()),
            entry(CannotExpelIslandPlayerException.class.getName(), ignored -> new CannotExpelIslandPlayerException()),
            entry(CannotRemoveOwnerException.class.getName(), ignored -> new CannotRemoveOwnerException()),
            entry(CoopLimitReachedException.class.getName(), CoopLimitReachedException::new),
            entry(HomeDoesNotExistException.class.getName(), ignored -> new HomeDoesNotExistException()),
            entry(HomeLimitReachedException.class.getName(), HomeLimitReachedException::new),
            entry(HomeNameNotLegalException.class.getName(), ignored -> new HomeNameNotLegalException()),
            entry(InsufficientFundsException.class.getName(), ignored -> new InsufficientFundsException()),
            entry(InvalidBiomeException.class.getName(), ignored -> new InvalidBiomeException()),
            entry(InvitedAlreadyException.class.getName(), ignored -> new InvitedAlreadyException()),
            entry(IslandAlreadyExistException.class.getName(), ignored -> new IslandAlreadyExistException()),
            entry(IslandDoesNotExistException.class.getName(), ignored -> new IslandDoesNotExistException()),
            entry(IslandGridFullException.class.getName(), ignored -> new IslandGridFullException()),
            entry(IslandLockedException.class.getName(), ignored -> new IslandLockedException()),
            entry(IslandNotHostedHereException.class.getName(), IslandNotHostedHereException::new),
            entry(IslandPlayerAlreadyExistsException.class.getName(), ignored -> new IslandPlayerAlreadyExistsException()),
            entry(IslandPlayerDoesNotExistException.class.getName(), ignored -> new IslandPlayerDoesNotExistException()),
            entry(LocationNotInIslandException.class.getName(), ignored -> new LocationNotInIslandException()),
            entry(NotIslandOwnerException.class.getName(), ignored -> new NotIslandOwnerException()),
            entry(PlayerAlreadyBannedException.class.getName(), ignored -> new PlayerAlreadyBannedException()),
            entry(PlayerAlreadyCoopedException.class.getName(), ignored -> new PlayerAlreadyCoopedException()),
            entry(PlayerAlreadyOwnerException.class.getName(), ignored -> new PlayerAlreadyOwnerException()),
            entry(PlayerBannedException.class.getName(), ignored -> new PlayerBannedException()),
            entry(PlayerNotBannedException.class.getName(), ignored -> new PlayerNotBannedException()),
            entry(PlayerNotCoopedException.class.getName(), ignored -> new PlayerNotCoopedException()),
            entry(PlayerNotInIslandException.class.getName(), ignored -> new PlayerNotInIslandException()),
            entry(PlayerNotOnlineException.class.getName(), ignored -> new PlayerNotOnlineException()),
            entry(PlayerNotOnOwnIslandException.class.getName(), ignored -> new PlayerNotOnOwnIslandException()),
            entry(TeamLimitReachedException.class.getName(), TeamLimitReachedException::new),
            entry(TeleportCancelledException.class.getName(), ignored -> new TeleportCancelledException()),
            entry(UpgradeDoesNotExistException.class.getName(), ignored -> new UpgradeDoesNotExistException()),
            entry(UpgradeIslandLevelTooLowException.class.getName(), ignored -> new UpgradeIslandLevelTooLowException()),
            entry(UpgradeLevelChangedException.class.getName(), ignored -> new UpgradeLevelChangedException()),
            entry(UpgradeLevelDoesNotExistException.class.getName(), ignored -> new UpgradeLevelDoesNotExistException()),
            entry(UpgradeMaxedException.class.getName(), ignored -> new UpgradeMaxedException()),
            entry(WarpDoesNotExistException.class.getName(), ignored -> new WarpDoesNotExistException()),
            entry(WarpLimitReachedException.class.getName(), WarpLimitReachedException::new),
            entry(WarpNameNotLegalException.class.getName(), ignored -> new WarpNameNotLegalException()),
            entry(WorldNotFoundException.class.getName(), ignored -> new WorldNotFoundException())
    );

    private RemoteErrors() {}

    static RuntimeException decode(String type, String message) {
        if (type == null || !TYPES.containsKey(type)) {
            throw new IllegalArgumentException("Unsupported remote error type: " + type + "; message: " + message);
        }
        return TYPES.get(type).apply(message);
    }
}