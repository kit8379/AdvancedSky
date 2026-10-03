package org.me.advancedsky.command;

import com.destroystokyo.paper.event.server.AsyncTabCompleteEvent;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.me.advancedsky.AdvancedSky;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class AsyncTabCompleteListener implements Listener {

    private static final long TAB_TIMEOUT_MILLIS = 200L;
    private static final long CANDIDATES_TTL_NANOS = TimeUnit.SECONDS.toNanos(5);

    private final AdvancedSky plugin;
    private final Map<String, AsyncCommandTabRouter> routers = new HashMap<>();
    private final Map<String, Candidates> candidates = new ConcurrentHashMap<>();

    public AsyncTabCompleteListener(AdvancedSky plugin) {
        this.plugin = plugin;
    }

    public void registerRoot(String rootLabel, AsyncCommandTabRouter router) {
        if (rootLabel == null || rootLabel.isEmpty() || router == null) {
            return;
        }
        routers.put(rootLabel.toLowerCase(Locale.ROOT), router);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onAsyncTabComplete(AsyncTabCompleteEvent event) {
        if (!event.isCommand()) {
            return;
        }

        String buffer = event.getBuffer();
        if (buffer.isEmpty() || buffer.charAt(0) != '/') {
            return;
        }

        ParsedCommand parsed = parseCommandBuffer(buffer);
        if (parsed == null) {
            return;
        }

        AsyncCommandTabRouter router = routers.get(parsed.label.toLowerCase(Locale.ROOT));
        if (router == null) {
            return;
        }

        CommandSender sender = event.getSender();

        if (parsed.args.length == 0) {
            event.setCompletions(Collections.emptyList());
            event.setHandled(true);
            return;
        }

        String typed = parsed.args[parsed.args.length - 1].toLowerCase(Locale.ROOT);
        String[] untyped = parsed.args.clone();
        untyped[untyped.length - 1] = "";
        // Arguments never hold a space, so spaces keep the parts of the key apart.
        String key = senderKey(sender) + " " + parsed.label.toLowerCase(Locale.ROOT) + " " + String.join(" ", untyped);

        try {
            List<String> all;
            try {
                // A load slower than the wait keeps going and serves the next keystroke.
                all = candidates(key, () -> router.completeAsync(sender, parsed.label, untyped).exceptionally(ex -> {
                    plugin.severe("Async tab complete exception for buffer '" + buffer + "'", ex);
                    return Collections.emptyList();
                })).get(TAB_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException slow) {
                all = Collections.emptyList();
            }

            List<String> suggestions = (all == null ? Collections.<String>emptyList() : all).stream().filter(s -> s != null && !s.isEmpty() && s.toLowerCase(Locale.ROOT).startsWith(typed)).distinct().collect(Collectors.toList());

            event.setCompletions(suggestions);
            event.setHandled(true);
        } catch (Exception ex) {
            plugin.severe("Failed to get async tab complete suggestions for buffer '" + buffer + "'", ex);
            event.setCompletions(Collections.emptyList());
            event.setHandled(true);
        }
    }

    private CompletableFuture<List<String>> candidates(String key, Supplier<CompletableFuture<List<String>>> loader) {
        long now = System.nanoTime();
        candidates.values().removeIf(cached -> now - cached.loadedAt() >= CANDIDATES_TTL_NANOS);
        return candidates.computeIfAbsent(key, ignored -> new Candidates(loader.get(), now)).list();
    }

    private static String senderKey(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId().toString() : sender.getName();
    }

    private ParsedCommand parseCommandBuffer(String buffer) {
        String raw = buffer.startsWith("/") ? buffer.substring(1) : buffer;
        if (raw.isEmpty()) {
            return null;
        }

        String[] split = raw.split(" ", -1);
        if (split.length == 0) {
            return null;
        }

        String label = split[0];
        if (label.isEmpty()) {
            return null;
        }

        List<String> argsList = new ArrayList<>(Arrays.asList(split).subList(1, split.length));

        return new ParsedCommand(label, argsList.toArray(new String[0]));
    }

    private record ParsedCommand(String label, String[] args) {
    }

    private record Candidates(CompletableFuture<List<String>> list, long loadedAt) {
    }
}