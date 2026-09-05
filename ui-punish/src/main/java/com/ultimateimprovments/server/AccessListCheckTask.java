package com.ultimateimprovments.server;

import com.ultimateimprovments.whitelist.BlacklistManager;
import com.ultimateimprovments.core.Main;
import com.ultimateimprovments.whitelist.OpWhitelistManager;
import com.ultimateimprovments.util.MessageUtil;
import com.ultimateimprovments.whitelist.WhitelistManager;
import com.ultimateimprovments.util.ConsoleLogger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * 🔄 AccessListCheckTask — periodic check of all online players
 * against the whitelist, blacklist and opwhitelist.
 * <p>
 * Runs with the interval from config.yml → access_control.check_interval_ticks.
 * On finding a violator — kicks them or removes OP.
 * <p>
 * Duplicates the logic of {@link WhitelistManager#onPlayerLogin},
 * {@link BlacklistManager#onPlayerLogin} and {@link OpWhitelistManager#checkAndDeop}
 * for already connected players (e.g. if the list changed directly via the DB).
 */
public class AccessListCheckTask extends BukkitRunnable {

    private static int taskId = -1;

    /**
     * Starts the periodic task with the interval from the config.
     *
     * @param plugin the plugin instance
     */
    public static void start(Main plugin) {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
        }

        int interval = plugin.getConfig().getInt("access_control.check_interval_ticks", 20);
        if (interval <= 0) {
            ConsoleLogger.info("[AccessCheck] Periodic check disabled (interval <= 0).");
            taskId = -1;
            return;
        }

        taskId = new AccessListCheckTask().runTaskTimer(plugin, interval, interval).getTaskId();
        ConsoleLogger.info("[AccessCheck] Started with interval " + interval + " ticks.");
    }

    /**
     * Stops the task.
     */
    public static void stop() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }
    }

    @Override
    public void run() {
        boolean whitelistEnabled = WhitelistManager.isEnabled();
        boolean blacklistEnabled = BlacklistManager.isEnabled();
        boolean opWhitelistEnabled = OpWhitelistManager.isEnabled();

        if (!whitelistEnabled && !blacklistEnabled && !opWhitelistEnabled) {
            return; // nothing enabled — nothing to check
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            String name = player.getName();

            // =========================
            // BLACKLIST CHECK
            // =========================
            if (blacklistEnabled && BlacklistManager.isBlacklisted(name)) {
                player.kickPlayer(MessageUtil.legacy(
                        "<red>⛔ You are blacklisted from this server!</red>"
                ));
                continue; // player already kicked
            }

            // =========================
            // WHITELIST CHECK
            // =========================
            if (whitelistEnabled && !WhitelistManager.isWhitelisted(name)) {
                player.kickPlayer(MessageUtil.legacy(
                        "<red>⛔ You are not whitelisted on this server!</red>\n" +
                        "<gray>Use the UltimateImprovments whitelist system.</gray>"
                ));
                continue;
            }

            // =========================
            // OP WHITELIST CHECK (via OpWhitelistManager)
            // =========================
            if (opWhitelistEnabled && player.isOp()) {
                if (!OpWhitelistManager.isWhitelisted(name)) {
                    player.setOp(false);
                    player.sendMessage(MessageUtil.parse(
                            "<red>⛔</red> <white>Your operator status has been removed — you are not in the OP whitelist.</white>"
                    ));
                    ConsoleLogger.info("[OpWhitelist] Removed OP from " + name + " (not whitelisted)");
                }
            }
        }
    }
}
