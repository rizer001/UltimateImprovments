package com.ultimateimprovments.command.vote;

import com.ultimateimprovments.core.Main;
import com.ultimateimprovments.config.MessagesManager;
import com.ultimateimprovments.database.DatabaseManager;
import com.ultimateimprovments.util.Broadcast;
import com.ultimateimprovments.util.MessageUtil;
import com.ultimateimprovments.util.ConsoleLogger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Voting system.
 * <p>
 * Commands:
 * <ul>
 *   <li>{@code /ui vote create <name> <title> <desc> -answer_1:t,d -answer_2:t,d ... -time:<N><s|m|h|d>}</li>
 *   <li>{@code /ui vote <name> [<index>|<title>]} — vote or view results</li>
 *   <li>{@code /ui vote delete <name>} — delete with confirmation</li>
 *   <li>{@code /ui vote change <name> <params>} — change (recreate with the same params)</li>
 * </ul>
 */
public class VoteManager {

    // =========================
    // IN-MEMORY CACHE
    // =========================
    private static final Map<String, Vote> votes = new ConcurrentHashMap<>();
    /** Players who confirmed vote deletion (vote_name -> sender_uuid -> true) */
    private static final Map<String, Map<UUID, Boolean>> pendingDeletes = new ConcurrentHashMap<>();
    /** Active vote close timers */
    private static final Map<String, BukkitTask> closeTimers = new ConcurrentHashMap<>();

    // =========================
    // INIT — load from DB on startup
    // =========================
    public static void init() {
        votes.clear();
        pendingDeletes.clear();
        closeTimers.clear();
        loadFromDatabase();
        ConsoleLogger.info("[VOTE] Loaded " + votes.size() + " votes from database.");
    }

    // =========================
    // LOAD FROM DB
    // =========================
    private static void loadFromDatabase() {
        try (Connection con = DatabaseManager.getConnection()) {
            // Load votes
            try (PreparedStatement st = con.prepareStatement(
                    "SELECT name, title, question, creator_uuid, created_at, expires_at, ended FROM votes")) {
                ResultSet rs = st.executeQuery();
                while (rs.next()) {
                    Vote vote = new Vote();
                    vote.name = rs.getString("name");
                    vote.title = rs.getString("title");
                    vote.question = rs.getString("question");
                    vote.creator = UUID.fromString(rs.getString("creator_uuid"));
                    vote.createdAt = rs.getLong("created_at");
                    vote.expiresAt = rs.getLong("expires_at");
                    vote.ended = rs.getInt("ended") == 1;
                    vote.answers = new ArrayList<>();
                    vote.votes = new ConcurrentHashMap<>();
                    votes.put(vote.name.toLowerCase(), vote);
                }
            }

            // Load answers
            try (PreparedStatement st = con.prepareStatement(
                    "SELECT vote_name, answer_index, title, description FROM vote_answers ORDER BY vote_name, answer_index")) {
                ResultSet rs = st.executeQuery();
                while (rs.next()) {
                    String voteName = rs.getString("vote_name");
                    Vote vote = votes.get(voteName.toLowerCase());
                    if (vote == null) continue;
                    int idx = rs.getInt("answer_index");
                    Answer a = new Answer();
                    a.title = rs.getString("title");
                    a.description = rs.getString("description");
                    while (vote.answers.size() <= idx) vote.answers.add(null);
                    vote.answers.set(idx, a);
                }
            }

            // Load player votes
            try (PreparedStatement st = con.prepareStatement(
                    "SELECT vote_name, player_uuid, answer_index FROM vote_records")) {
                ResultSet rs = st.executeQuery();
                while (rs.next()) {
                    String voteName = rs.getString("vote_name");
                    Vote vote = votes.get(voteName.toLowerCase());
                    if (vote == null) continue;
                    UUID playerUuid = UUID.fromString(rs.getString("player_uuid"));
                    int answerIndex = rs.getInt("answer_index");
                    vote.votes.put(playerUuid, answerIndex);
                }
            }

            // Restore timers for non-expired votes
            long now = System.currentTimeMillis();
            for (Vote vote : votes.values()) {
                if (vote.expiresAt > now && !vote.ended) {
                    scheduleClose(vote.name, vote.expiresAt - now);
                } else if (vote.expiresAt <= now && !vote.ended) {
                    closeVote(vote.name);
                }
            }
        } catch (Exception e) {
            ConsoleLogger.error("[VOTE] Failed to load votes: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // =========================
    // PARSE VOTE CREATE ARGS
    // =========================
    public static boolean parseCreate(Player creator, String[] args, int startIndex) {
        if (args.length < startIndex + 3) {
            creator.sendMessage(MessageUtil.parse(MessagesManager.getString("vote.errors.usage_create",
                    "<red>❌ Usage:</red> <white>/ui vote create [name] [title] [description] -answer_[N]:[title,desc] ... -time:[N][s|m|h|d]</white>")));
            return true;
        }

        String name = args[startIndex];
        String title = args[startIndex + 1];
        String question = args[startIndex + 2];

        // name validation
        if (!name.matches("[a-zA-Z0-9_-]{1,32}")) {
            creator.sendMessage(MessageUtil.parse(MessagesManager.getString("vote.errors.invalid_name",
                    "<red>❌ Vote name must contain only letters, numbers, hyphens and underscores (1-32 characters).</red>")));
            return true;
        }

        if (votes.containsKey(name.toLowerCase())) {
            creator.sendMessage(MessageUtil.parse(MessagesManager.getString("vote.errors.already_exists",
                    "<red>❌ A vote with that name already exists!</red>")));
            return true;
        }

        List<Answer> answers = new ArrayList<>();
        long duration = 3600000; // 1 hour default

        for (int i = startIndex + 3; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("-answer_")) {
                // Format: -answer_N:title,description
                String withoutPrefix = arg.substring("-answer_".length());
                int colonIdx = withoutPrefix.indexOf(':');
                if (colonIdx < 0) {
                    creator.sendMessage(MessageUtil.parse(MessagesManager.getString("vote.errors.invalid_answer",
                            "<red>❌ Invalid answer format: </red><yellow>%arg%</yellow><red>. Expected: -answer_N:title,description</red>")
                            .replace("%arg%", arg)));
                    return true;
                }
                // number N
                String numStr = withoutPrefix.substring(0, colonIdx);
                int answerNum;
                try {
                    answerNum = Integer.parseInt(numStr);
                } catch (NumberFormatException e) {
                    creator.sendMessage(MessageUtil.parse(MessagesManager.getString("vote.errors.invalid_answer_number",
                            "<red>❌ Invalid answer number: </red><yellow>%num%</yellow>")
                            .replace("%num%", numStr)));
                    return true;
                }
                String rest = withoutPrefix.substring(colonIdx + 1);
                int commaIdx = rest.indexOf(',');
                String answerTitle;
                String answerDesc;
                if (commaIdx >= 0) {
                    answerTitle = rest.substring(0, commaIdx).trim();
                    answerDesc = rest.substring(commaIdx + 1).trim();
                } else {
                    answerTitle = rest.trim();
                    answerDesc = "";
                }
                if (answerTitle.isEmpty()) {
                    creator.sendMessage(MessageUtil.parse(MessagesManager.getString("vote.errors.empty_answer",
                            "<red>❌ Answer title cannot be empty!</red>")));
                    return true;
                }
                Answer a = new Answer();
                a.title = answerTitle;
                a.description = answerDesc;
                // Insert at correct position
                while (answers.size() <= answerNum) answers.add(null);
                answers.set(answerNum, a);
            } else if (arg.startsWith("-time:")) {
                String timeStr = arg.substring("-time:".length()).trim();
                duration = parseDuration(timeStr);
                if (duration < 0) {
                    creator.sendMessage(MessageUtil.parse(MessagesManager.getString("vote.errors.invalid_time",
                            "<red>❌ Invalid time format: </red><yellow>%time%</yellow><red>. Use e.g.: 30s, 5m, 2h, 1d</red>")
                            .replace("%time%", timeStr)));
                    return true;
                }
            }
        }

        // Remove nulls (gaps in answers)
        answers.removeAll(Collections.singleton(null));
        if (answers.size() < 2) {
            creator.sendMessage(MessageUtil.parse(MessagesManager.getString("vote.errors.min_answers",
                    "<red>❌ You need at least 2 answer options!</red>")));
            return true;
        }

        createVote(creator, name, title, question, answers, duration);
        return true;
    }

    // =========================
    // CREATE VOTE
    // =========================
    public static void createVote(Player creator, String name, String title, String question,
                                   List<Answer> answers, long durationMillis) {
        Vote vote = new Vote();
        vote.name = name;
        vote.title = title;
        vote.question = question;
        vote.answers = new ArrayList<>(answers);
        vote.creator = creator.getUniqueId();
        vote.createdAt = System.currentTimeMillis();
        vote.expiresAt = vote.createdAt + durationMillis;
        vote.votes = new ConcurrentHashMap<>();

        votes.put(name.toLowerCase(), vote);
        saveToDatabase(vote);
        scheduleClose(name.toLowerCase(), durationMillis);
        broadcastVote(vote);

        creator.sendMessage(MessageUtil.parse(MessagesManager.getString("vote.create.success",
                "<green>✔</green> <white>Vote </white><yellow>%name%</yellow><white> created! Duration: </white><yellow>%duration%</yellow>")
                .replace("%name%", name)
                .replace("%duration%", formatDuration(durationMillis))));
    }

    // =========================
    // VOTE
    // =========================
    public static boolean vote(Player player, String voteName, String answerStr) {
        Vote vote = votes.get(voteName.toLowerCase());
        if (vote == null) {
            player.sendMessage(MessageUtil.parse(MessagesManager.getString("vote.errors.not_found",
                    "<red>❌ Vote </red><yellow>%name%</yellow><red> not found!</red>")
                    .replace("%name%", voteName)));
            return true;
        }
        if (vote.ended) {
            player.sendMessage(MessageUtil.parse(MessagesManager.getString("vote.errors.already_ended",
                    "<red>❌ Vote </red><yellow>%name%</yellow><red> has already ended!</red>")
                    .replace("%name%", vote.name)));
            return true;
        }
        if (System.currentTimeMillis() >= vote.expiresAt) {
            closeVote(vote.name);
            player.sendMessage(MessageUtil.parse(MessagesManager.getString("vote.errors.expired",
                    "<red>❌ Vote </red><yellow>%name%</yellow><red> has expired!</red>")
                    .replace("%name%", vote.name)));
            return true;
        }

        int answerIndex = -1;

        // Try parsing as number
        try {
            int idx = Integer.parseInt(answerStr);
            if (idx >= 0 && idx < vote.answers.size()) {
                answerIndex = idx;
            }
        } catch (NumberFormatException ignored) {}

        // Try matching by title (case-insensitive)
        if (answerIndex < 0) {
            for (int i = 0; i < vote.answers.size(); i++) {
                if (vote.answers.get(i).title.equalsIgnoreCase(answerStr)) {
                    answerIndex = i;
                    break;
                }
            }
        }

        if (answerIndex < 0) {
            StringBuilder options = new StringBuilder();
            for (int i = 0; i < vote.answers.size(); i++) {
                if (i > 0) options.append("<gray>, </gray>");
                options.append("<yellow>").append(i).append(":</yellow> <white>").append(vote.answers.get(i).title).append("</white>");
            }
            player.sendMessage(MessageUtil.parse(MessagesManager.getString("vote.errors.answer_not_found",
                    "<red>❌ Invalid answer option! Available options:</red>")
                    + "\n" + options));
            return true;
        }

        UUID uuid = player.getUniqueId();
        vote.votes.put(uuid, answerIndex);
        saveVoteRecord(vote.name, uuid, answerIndex);

        Answer chosen = vote.answers.get(answerIndex);
        player.sendMessage(MessageUtil.parse(MessagesManager.getString("vote.vote.success",
                "<green>✔</green> <white>Your vote has been recorded: </white><yellow>%answer%</yellow>")
                .replace("%answer%", chosen.title)));

        // Current vote stats
        int total = vote.votes.size();
        player.sendMessage(MessageUtil.parse(MessagesManager.getString("vote.vote.total_info",
                "<gray>Votes cast: </gray><yellow>%total%</yellow><gray> out of </gray><yellow>%display_total%</yellow><gray> eligible players (online).</gray>")
                .replace("%total%", String.valueOf(total))
                .replace("%display_total%", String.valueOf(vote.getDisplayTotal()))));

        return true;
    }

    // =========================
    // VIEW VOTE RESULTS
    // =========================
    public static boolean view(Player player, String voteName) {
        Vote vote = votes.get(voteName.toLowerCase());
        if (vote == null) {
            player.sendMessage(MessageUtil.parse(MessagesManager.getString("vote.errors.not_found",
                    "<red>❌ Vote </red><yellow>%name%</yellow><red> not found!</red>")
                    .replace("%name%", voteName)));
            return true;
        }

        player.sendMessage(MessageUtil.parse(""));
        player.sendMessage(MessageUtil.parse(MessagesManager.getString("vote.results.header", "<gold>═══════════════════════════════════</gold>")));
        player.sendMessage(MessageUtil.parse(MessagesManager.getString("vote.results.title", "<gold>  <yellow>✦ %title%</yellow></gold>").replace("%title%", vote.title)));
        player.sendMessage(MessageUtil.parse(MessagesManager.getString("vote.results.header", "<gold>═══════════════════════════════════</gold>")));
        player.sendMessage(MessageUtil.parse(MessagesManager.getString("vote.results.question", "<gray>┃ <white>%question%</white></gray>").replace("%question%", vote.question)));
        player.sendMessage(MessageUtil.parse(""));

        // Vote counting
        Map<Integer, Integer> counts = new HashMap<>();
        for (int idx : vote.votes.values()) {
            counts.merge(idx, 1, Integer::sum);
        }
        int totalVotes = vote.votes.size();

        for (int i = 0; i < vote.answers.size(); i++) {
            Answer a = vote.answers.get(i);
            int count = counts.getOrDefault(i, 0);
            int pct = totalVotes > 0 ? (count * 100 / totalVotes) : 0;
            String bar = createProgressBar(pct, 20);

            player.sendMessage(MessageUtil.parse(MessagesManager.getString("vote.results.answer_entry", "<gray>┃ <yellow>%num%.</yellow> <white>%title%</white></gray>")
                    .replace("%num%", String.valueOf(i))
                    .replace("%title%", a.title)));
            if (!a.description.isEmpty()) {
                player.sendMessage(MessageUtil.parse(MessagesManager.getString("vote.results.answer_desc", "<gray>┃   <gray>%desc%</gray></gray>")
                        .replace("%desc%", a.description)));
            }
            player.sendMessage(MessageUtil.parse(MessagesManager.getString("vote.results.progress_bar", "<gray>┃   %bar% <yellow>%count%</yellow> <gray>(%pct%%)</gray></gray>")
                    .replace("%bar%", bar)
                    .replace("%count%", String.valueOf(count))
                    .replace("%pct%", String.valueOf(pct))));
            player.sendMessage(MessageUtil.parse(""));
        }

        String status;
        if (vote.ended) {
            status = MessagesManager.getString("vote.results.status_ended", "<red>Ended</red>");
        } else if (System.currentTimeMillis() >= vote.expiresAt) {
            status = MessagesManager.getString("vote.results.status_expiring", "<red>Expiring...</red>");
        } else {
            long remaining = vote.expiresAt - System.currentTimeMillis();
            status = MessagesManager.getString("vote.results.status_active", "<green>Time left: <yellow>%remaining%</yellow></green>")
                    .replace("%remaining%", formatDuration(remaining));
        }
        player.sendMessage(MessageUtil.parse(MessagesManager.getString("vote.results.total_votes", "<gray>┃ <gray>Total votes:</gray> <yellow>%total%</yellow></gray>")
                .replace("%total%", String.valueOf(totalVotes))));
        player.sendMessage(MessageUtil.parse("<gray>┃ Status:</gray> " + status));
        player.sendMessage(MessageUtil.parse(MessagesManager.getString("vote.results.header", "<gold>═══════════════════════════════════</gold>")));
        player.sendMessage(MessageUtil.parse(""));

        return true;
    }

    // =========================
    // LIST ALL VOTES
    // =========================
    public static boolean list(Player player) {
        if (votes.isEmpty()) {
            player.sendMessage(MessageUtil.parse(MessagesManager.getString("vote.list.empty",
                    "<yellow>✦</yellow> <white>No active votes.</white>")));
            return true;
        }

        player.sendMessage(MessageUtil.parse(""));
        player.sendMessage(MessageUtil.parse(MessagesManager.getString("vote.list.header", "<gold>═══════════════════════════════════</gold>")));
        player.sendMessage(MessageUtil.parse(MessagesManager.getString("vote.list.title", "<gold>  ✦ <white>Votes</white> <gray>(%count%)</gray></gold>")
                .replace("%count%", String.valueOf(votes.size()))));
        player.sendMessage(MessageUtil.parse(MessagesManager.getString("vote.list.header", "<gold>═══════════════════════════════════</gold>")));

        for (Vote v : votes.values()) {
            String status;
            if (v.ended) {
                status = MessagesManager.getString("vote.list.status_ended", "<red>❌ Ended</red>");
            } else if (System.currentTimeMillis() >= v.expiresAt) {
                status = MessagesManager.getString("vote.list.status_expiring", "<red>Expiring...</red>");
            } else {
                long remaining = v.expiresAt - System.currentTimeMillis();
                status = "<green>" + formatDuration(remaining) + "</green>";
            }
            player.sendMessage(MessageUtil.parse(MessagesManager.getString("vote.list.entry", "<gray>┃ <yellow>%name%</yellow> <gray>—</gray> <white>%title%</white></gray>")
                    .replace("%name%", v.name)
                    .replace("%title%", v.title)));
            player.sendMessage(MessageUtil.parse(MessagesManager.getString("vote.list.entry_info", "<gray>┃   <gray>Options:</gray> <yellow>%answers%</yellow> <gray>| Votes:</gray> <yellow>%votes%</yellow> <gray>|</gray> %status%</gray>")
                    .replace("%answers%", String.valueOf(v.answers.size()))
                    .replace("%votes%", String.valueOf(v.votes.size()))
                    .replace("%status%", status)));
            player.sendMessage(MessageUtil.parse(MessagesManager.getString("vote.list.entry_hint", "<gray>┃   <gray><italic>/ui vote %name%</italic> <gray>— vote</gray></gray></gray>")
                    .replace("%name%", v.name)));
            player.sendMessage(MessageUtil.parse(""));
        }
        player.sendMessage(MessageUtil.parse(MessagesManager.getString("vote.list.header", "<gold>═══════════════════════════════════</gold>")));
        player.sendMessage(MessageUtil.parse(""));

        return true;
    }

    // =========================
    // DELETE VOTE (with confirmation)
    // =========================
    public static boolean delete(Player player, String voteName) {
        Vote vote = votes.get(voteName.toLowerCase());
        if (vote == null) {
            player.sendMessage(MessageUtil.parse(MessagesManager.getString("vote.errors.not_found",
                    "<red>❌ Vote </red><yellow>%name%</yellow><red> not found!</red>")
                    .replace("%name%", voteName)));
            return true;
        }
        if (!player.getUniqueId().equals(vote.creator) && !player.hasPermission("ui.command.vote.delete.other")) {
            player.sendMessage(MessageUtil.parse(MessagesManager.getString("vote.delete.not_creator",
                    "<red>❌ Only the vote creator can delete it!</red>")));
            return true;
        }

        String nameLower = vote.name.toLowerCase();
        Map<UUID, Boolean> pending = pendingDeletes.computeIfAbsent(nameLower, k -> new ConcurrentHashMap<>());

        if (pending.getOrDefault(player.getUniqueId(), false)) {
            // Confirmed — delete
            pending.remove(player.getUniqueId());
            doDeleteVote(vote);
            player.sendMessage(MessageUtil.parse(MessagesManager.getString("vote.delete.success",
                    "<green>✔</green> <white>Vote </white><yellow>%name%</yellow><white> deleted.</white>")
                    .replace("%name%", vote.name)));
            return true;
        }

        // Request confirmation
        pending.put(player.getUniqueId(), true);
        player.sendMessage(MessageUtil.parse(""));
        player.sendMessage(MessageUtil.parse("<dark_gray>┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓</dark_gray>"));
        player.sendMessage(MessageUtil.parse("<dark_gray>┃   </dark_gray>" + MessagesManager.getString("vote.delete.confirm_title", "<dark_red>⚠</dark_red> <red>Confirm vote deletion</red>")));
        player.sendMessage(MessageUtil.parse("<dark_gray>┃</dark_gray>"));
        player.sendMessage(MessageUtil.parse("<dark_gray>┃   </dark_gray>" + MessagesManager.getString("vote.delete.confirm_name", "<gray>Name: </gray><gold>%name%</gold>")
                .replace("%name%", vote.name)));
        player.sendMessage(MessageUtil.parse("<dark_gray>┃   </dark_gray>" + MessagesManager.getString("vote.delete.confirm_topic", "<gray>Topic: </gray><white>%title%</white>")
                .replace("%title%", vote.title)));
        player.sendMessage(MessageUtil.parse("<dark_gray>┃</dark_gray>"));
        player.sendMessage(MessageUtil.parse("<dark_gray>┃   </dark_gray>" + MessagesManager.getString("vote.delete.confirm_irreversible", "<gray>This action cannot be undone!</gray>")));

        Component confirmButton = MessageUtil.parse("<dark_gray>┃     <red>[<dark_red>❌ </dark_red></red>"
                + MessagesManager.getString("vote.delete.confirm_button", "Confirm deletion") + "<red>]</red></dark_gray>")
                .clickEvent(ClickEvent.runCommand("/ui vote delete " + vote.name))
                .hoverEvent(HoverEvent.showText(MessageUtil.parse(MessagesManager.getString("vote.delete.confirm_hover", "<red>Click to confirm deletion</red>"))));
        player.sendMessage(confirmButton);

        player.sendMessage(MessageUtil.parse("<dark_gray>┃   </dark_gray>" + MessagesManager.getString("vote.delete.confirm_resend", "<gray>or type </gray><white>/ui vote delete %name%</white><gray> again</gray>")
                .replace("%name%", vote.name)));
        player.sendMessage(MessageUtil.parse("<dark_gray>┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛</dark_gray>"));
        player.sendMessage(MessageUtil.parse(""));

        // Auto-expire after 30 sec
        Bukkit.getScheduler().runTaskLater(Main.getInstance(), () -> {
            if (pending.remove(player.getUniqueId()) != null) {
                player.sendMessage(MessageUtil.parse(MessagesManager.getString("vote.delete.confirm_expired",
                        "<yellow>✦</yellow> <white>Vote deletion request for </white><yellow>%name%</yellow><white> expired.</white>")
                        .replace("%name%", vote.name)));
            }
        }, 600L); // 30 sec

        return true;
    }

    // =========================
    // DO DELETE
    // =========================
    private static void doDeleteVote(Vote vote) {
        String nameLower = vote.name.toLowerCase();
        // Cancel close timer
        BukkitTask timer = closeTimers.remove(nameLower);
        if (timer != null) timer.cancel();

        votes.remove(nameLower);
        pendingDeletes.remove(nameLower);

        // Remove from DB
        try (Connection con = DatabaseManager.getConnection()) {
            try (PreparedStatement st = con.prepareStatement("DELETE FROM vote_records WHERE vote_name = ?")) {
                st.setString(1, vote.name);
                st.executeUpdate();
            }
            try (PreparedStatement st = con.prepareStatement("DELETE FROM vote_answers WHERE vote_name = ?")) {
                st.setString(1, vote.name);
                st.executeUpdate();
            }
            try (PreparedStatement st = con.prepareStatement("DELETE FROM votes WHERE name = ?")) {
                st.setString(1, vote.name);
                st.executeUpdate();
            }
        } catch (Exception e) {
            ConsoleLogger.error("[VOTE] Failed to delete vote from DB: " + e.getMessage());
        }

        Broadcast.send(MessagesManager.getString("vote.delete.broadcast",
                "<dark_red>✦</dark_red> <red>Vote </red><yellow>%name%</yellow><red> has been deleted.</red>")
                .replace("%name%", vote.name));
    }

    // =========================
    // CHANGE VOTE (recreate with new params)
    // =========================
    public static boolean change(Player player, String voteName, String[] args, int startIndex) {
        Vote oldVote = votes.get(voteName.toLowerCase());
        if (oldVote == null) {
            player.sendMessage(MessageUtil.parse(MessagesManager.getString("vote.errors.not_found",
                    "<red>❌ Vote </red><yellow>%name%</yellow><red> not found!</red>")
                    .replace("%name%", voteName)));
            return true;
        }
        if (!player.getUniqueId().equals(oldVote.creator) && !player.hasPermission("ui.command.vote.change.other")) {
            player.sendMessage(MessageUtil.parse(MessagesManager.getString("vote.errors.not_creator",
                    "<red>❌ Only the vote creator can do this!</red>")));
            return true;
        }

        // Parse new params from args
        if (args.length < startIndex + 1) {
            player.sendMessage(MessageUtil.parse(MessagesManager.getString("vote.errors.need_params",
                    "<red>❌ Specify at least one parameter to change!</red>")));
            return true;
        }

        // We'll collect changes and apply them
        String newTitle = null;
        String newQuestion = null;
        List<Answer> newAnswers = null;
        Long newDuration = null;

        for (int i = startIndex; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("-title") || arg.equals("-t")) {
                if (i + 1 < args.length) {
                    newTitle = args[++i];
                }
            } else if (arg.equals("-desc") || arg.equals("-d") || arg.equals("-question")) {
                if (i + 1 < args.length) {
                    newQuestion = args[++i];
                }
            } else if (arg.startsWith("-answer_")) {
                // Same format as create
                String withoutPrefix = arg.substring("-answer_".length());
                int colonIdx = withoutPrefix.indexOf(':');
                if (colonIdx < 0) continue;
                String numStr = withoutPrefix.substring(0, colonIdx);
                int answerNum;
                try {
                    answerNum = Integer.parseInt(numStr);
                } catch (NumberFormatException e) {
                    continue;
                }
                String rest = withoutPrefix.substring(colonIdx + 1);
                int commaIdx = rest.indexOf(',');
                String answerTitle;
                String answerDesc;
                if (commaIdx >= 0) {
                    answerTitle = rest.substring(0, commaIdx).trim();
                    answerDesc = rest.substring(commaIdx + 1).trim();
                } else {
                    answerTitle = rest.trim();
                    answerDesc = "";
                }
                if (answerTitle.isEmpty()) continue;

                if (newAnswers == null) newAnswers = new ArrayList<>(oldVote.answers);
                Answer a = new Answer();
                a.title = answerTitle;
                a.description = answerDesc;
                while (newAnswers.size() <= answerNum) newAnswers.add(null);
                newAnswers.set(answerNum, a);
            } else if (arg.startsWith("-time:")) {
                newDuration = parseDuration(arg.substring("-time:".length()).trim());
            }
        }

        // Apply the changes
        boolean changed = false;

        if (newTitle != null && !newTitle.isEmpty()) {
            oldVote.title = newTitle;
            changed = true;
        }
        if (newQuestion != null && !newQuestion.isEmpty()) {
            oldVote.question = newQuestion;
            changed = true;
        }
        if (newAnswers != null) {
            newAnswers.removeAll(Collections.singleton(null));
            if (newAnswers.size() >= 2) {
                oldVote.answers = new ArrayList<>(newAnswers);
                changed = true;
            } else {
                player.sendMessage(MessageUtil.parse(MessagesManager.getString("vote.errors.change_min_answers",
                        "<red>❌ After the change, there must be at least 2 answer options!</red>")));
            }
        }
        if (newDuration != null && newDuration > 0) {
            oldVote.expiresAt = System.currentTimeMillis() + newDuration;
            // Reschedule timer
            BukkitTask oldTimer = closeTimers.remove(oldVote.name.toLowerCase());
            if (oldTimer != null) oldTimer.cancel();
            scheduleClose(oldVote.name.toLowerCase(), newDuration);
            changed = true;
        }

        if (changed) {
            saveToDatabase(oldVote);
            player.sendMessage(MessageUtil.parse(MessagesManager.getString("vote.change.success",
                    "<green>✔</green> <white>Vote </white><yellow>%name%</yellow><white> updated.</white>")
                    .replace("%name%", oldVote.name)));
            broadcastVote(oldVote);
        } else {
            player.sendMessage(MessageUtil.parse(MessagesManager.getString("vote.errors.no_changes",
                    "<yellow>✦</yellow> <white>Nothing changed.</white>")));
        }

        return true;
    }

    // =========================
    // BROADCAST VOTE
    // =========================
    public static void broadcastVote(Vote vote) {
        String durationStr = formatDuration(vote.expiresAt - System.currentTimeMillis());
        String header = MessagesManager.getString("vote.broadcast.header", "<gold>═══════════════════════════════════════</gold>");

        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendMessage(MessageUtil.parse(""));
            player.sendMessage(MessageUtil.parse(header));
            player.sendMessage(MessageUtil.parse(MessagesManager.getString("vote.broadcast.title", "<gold>  <yellow>✦ %title%</yellow></gold>").replace("%title%", vote.title)));
            player.sendMessage(MessageUtil.parse(header));
            player.sendMessage(MessageUtil.parse(MessagesManager.getString("vote.broadcast.question", "<gray>┃ <white>%question%</white></gray>").replace("%question%", vote.question)));
            player.sendMessage(MessageUtil.parse(""));
            player.sendMessage(MessageUtil.parse(MessagesManager.getString("vote.broadcast.options", "<gray>┃ <gray>Answer options:</gray></gray>")));

            for (int i = 0; i < vote.answers.size(); i++) {
                Answer a = vote.answers.get(i);

                Component hoverText = MessageUtil.parse(MessagesManager.getString("vote.broadcast.button_hover", "<green>Click to vote for <white>%title%</white></green>")
                        .replace("%title%", a.title));
                if (!a.description.isEmpty()) {
                    hoverText = hoverText.append(Component.newline()).append(MessageUtil.parse("<gray>" + a.description + "</gray>"));
                }

                Component answerBtn = MessageUtil.parse("<dark_gray>┃ </dark_gray><yellow>" + i + ".</yellow> <green>[<dark_green>" + a.title + "</dark_green>]</green>")
                        .clickEvent(ClickEvent.runCommand("/ui vote " + vote.name + " " + i))
                        .hoverEvent(HoverEvent.showText(hoverText));
                player.sendMessage(answerBtn);

                if (!a.description.isEmpty()) {
                    player.sendMessage(MessageUtil.parse("<dark_gray>┃   <gray>" + a.description + "</gray></dark_gray>"));
                }
            }

            player.sendMessage(MessageUtil.parse(""));
            player.sendMessage(MessageUtil.parse(MessagesManager.getString("vote.broadcast.hint", "<dark_gray>┃ <gray>To vote, click the button above</gray></dark_gray>")));
            player.sendMessage(MessageUtil.parse(MessagesManager.getString("vote.broadcast.hint2", "<dark_gray>┃ <gray>or type </gray><white>/ui vote %name% \\<number\\></white></dark_gray>").replace("%name%", vote.name)));
            player.sendMessage(MessageUtil.parse("<dark_gray>┃</dark_gray>"));
            player.sendMessage(MessageUtil.parse(MessagesManager.getString("vote.broadcast.time_left", "<dark_gray>┃ <gray>Time left:</gray> <yellow>%duration%</yellow></dark_gray>").replace("%duration%", durationStr)));
            player.sendMessage(MessageUtil.parse(header));
            player.sendMessage(MessageUtil.parse(""));
        }
    }

    // =========================
    // CLOSE VOTE
    // =========================
    public static void closeVote(String voteName) {
        Vote vote = votes.get(voteName.toLowerCase());
        if (vote == null || vote.ended) return;

        vote.ended = true;
        closeTimers.remove(voteName.toLowerCase());

        // Count the results
        Map<Integer, Integer> counts = new HashMap<>();
        for (int idx : vote.votes.values()) {
            counts.merge(idx, 1, Integer::sum);
        }
        int total = vote.votes.size();

        // Determine the winner
        int maxCount = 0;
        int winnerIdx = -1;
        boolean isTie = false;
        for (int i = 0; i < vote.answers.size(); i++) {
            int c = counts.getOrDefault(i, 0);
            if (c > maxCount) {
                maxCount = c;
                winnerIdx = i;
                isTie = false;
            } else if (c == maxCount && maxCount > 0) {
                isTie = true;
            }
        }

        // Broadcast results
        String header = MessagesManager.getString("vote.close.header", "<gold>═══════════════════════════════════════</gold>");
        String closeTitle = MessagesManager.getString("vote.close.title", "<gold>  ✦ <white>Vote ended</white> <gray>[<yellow>%name%</yellow><gray>]</gray></gray></gold>");
        String noVotes = MessagesManager.getString("vote.close.no_votes", "<gray>┃ <gray>Nobody voted.</gray></gray>");
        String tieStr = MessagesManager.getString("vote.close.tie", "<gray>┃ <yellow>Tie!</yellow> <gray>Several options received the same number of votes.</gray></gray>");
        String tieEntry = MessagesManager.getString("vote.close.tie_entry", "<gray>┃ <yellow>%title%</yellow> <gray>—</gray> <yellow>%count%</yellow> <gray>votes</gray></gray>");
        String winner = MessagesManager.getString("vote.close.winner", "<gray>┃ <green>Winner:</green> <yellow>%title%</yellow> <gray>(</gray><yellow>%count%</yellow><gray>/%total% —</gray> <yellow>%pct%%</yellow><gray>)</gray></gray>");
        String totalStr = MessagesManager.getString("vote.close.total", "<gray>┃ <gray>Total votes cast:</gray> <yellow>%total%</yellow></gray>");

        Broadcast.send("");
        Broadcast.send(header + "\n" + closeTitle.replace("%name%", vote.name) + "\n" + header);

        if (total == 0) {
            Broadcast.send(noVotes);
        } else if (isTie) {
            Broadcast.send(tieStr);
            for (int i = 0; i < vote.answers.size(); i++) {
                int c = counts.getOrDefault(i, 0);
                if (c == maxCount) {
                    Broadcast.send(tieEntry
                            .replace("%title%", vote.answers.get(i).title)
                            .replace("%count%", String.valueOf(c)));
                }
            }
        } else if (winnerIdx >= 0) {
            Answer win = vote.answers.get(winnerIdx);
            int pct = (maxCount * 100 / total);
            Broadcast.send(winner
                    .replace("%title%", win.title)
                    .replace("%count%", String.valueOf(maxCount))
                    .replace("%total%", String.valueOf(total))
                    .replace("%pct%", String.valueOf(pct)));
        }

        Broadcast.send(totalStr.replace("%total%", String.valueOf(total)));
        Broadcast.send(header);
        Broadcast.send("");

        updateVoteEndedInDb(vote.name, true);
    }

    // =========================
    // SCHEDULE CLOSE
    // =========================
    private static void scheduleClose(String voteName, long delayMillis) {
        long delayTicks = Math.max(1, delayMillis / 50);
        String key = voteName.toLowerCase();
        BukkitTask task = new BukkitRunnable() {
            @Override
            public void run() {
                closeVote(key);
            }
        }.runTaskLater(Main.getInstance(), delayTicks);
        closeTimers.put(key, task);
    }

    // =========================
    // DATABASE HELPERS
    // =========================
    private static void saveToDatabase(Vote vote) {
        try (Connection con = DatabaseManager.getConnection()) {
            try (PreparedStatement st = con.prepareStatement(
                    "INSERT OR REPLACE INTO votes (name, title, question, creator_uuid, created_at, expires_at, ended) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                st.setString(1, vote.name);
                st.setString(2, vote.title);
                st.setString(3, vote.question);
                st.setString(4, vote.creator.toString());
                st.setLong(5, vote.createdAt);
                st.setLong(6, vote.expiresAt);
                st.setInt(7, vote.ended ? 1 : 0);
                st.executeUpdate();
            }
            saveAnswersToDatabase(vote);
        } catch (Exception e) {
            ConsoleLogger.error("[VOTE] DB save error: " + e.getMessage());
        }
    }

    private static void saveAnswersToDatabase(Vote vote) {
        try (Connection con = DatabaseManager.getConnection()) {
            // Clear old answers
            try (PreparedStatement st = con.prepareStatement("DELETE FROM vote_answers WHERE vote_name = ?")) {
                st.setString(1, vote.name);
                st.executeUpdate();
            }
            // Insert new
            try (PreparedStatement st = con.prepareStatement(
                    "INSERT INTO vote_answers (vote_name, answer_index, title, description) VALUES (?, ?, ?, ?)")) {
                for (int i = 0; i < vote.answers.size(); i++) {
                    Answer a = vote.answers.get(i);
                    st.setString(1, vote.name);
                    st.setInt(2, i);
                    st.setString(3, a.title);
                    st.setString(4, a.description);
                    st.addBatch();
                }
                st.executeBatch();
            }
        } catch (Exception e) {
            ConsoleLogger.error("[VOTE] DB save answers error: " + e.getMessage());
        }
    }

    private static void saveVoteRecord(String voteName, UUID playerUuid, int answerIndex) {
        try (Connection con = DatabaseManager.getConnection()) {
            // Upsert — replace existing vote
            try (PreparedStatement st = con.prepareStatement(
                    "INSERT OR REPLACE INTO vote_records (vote_name, player_uuid, answer_index) VALUES (?, ?, ?)")) {
                st.setString(1, voteName);
                st.setString(2, playerUuid.toString());
                st.setInt(3, answerIndex);
                st.executeUpdate();
            }
        } catch (Exception e) {
            ConsoleLogger.error("[VOTE] DB save record error: " + e.getMessage());
        }
    }

    private static void updateVoteEndedInDb(String voteName, boolean ended) {
        try (Connection con = DatabaseManager.getConnection();
             PreparedStatement st = con.prepareStatement("UPDATE votes SET ended = ? WHERE name = ?")) {
            st.setInt(1, ended ? 1 : 0);
            st.setString(2, voteName);
            st.executeUpdate();
        } catch (Exception e) {
            ConsoleLogger.error("[VOTE] DB update ended error: " + e.getMessage());
        }
    }

    // =========================
    // UTILITY
    // =========================
    private static long parseDuration(String timeStr) {
        if (timeStr.isEmpty()) return -1;
        char unit = timeStr.charAt(timeStr.length() - 1);
        String numStr = timeStr.substring(0, timeStr.length() - 1);
        long num;
        try {
            num = Long.parseLong(numStr);
        } catch (NumberFormatException e) {
            return -1;
        }
        if (num <= 0) return -1;
        return switch (Character.toLowerCase(unit)) {
            case 's' -> num * 1000;
            case 'm' -> num * 60000;
            case 'h' -> num * 3600000;
            case 'd' -> num * 86400000;
            default -> -1;
        };
    }

    private static String formatDuration(long millis) {
        if (millis < 0) millis = 0;
        long seconds = millis / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;

        if (days > 0) return days + "d " + (hours % 24) + "h " + (minutes % 60) + "m";
        if (hours > 0) return hours + "h " + (minutes % 60) + "m";
        if (minutes > 0) return minutes + "m " + (seconds % 60) + "s";
        return seconds + "s";
    }

    private static String createProgressBar(int pct, int width) {
        int filled = pct * width / 100;
        StringBuilder sb = new StringBuilder("<dark_gray>[");
        for (int i = 0; i < width; i++) {
            if (i < filled) {
                if (pct >= 66) sb.append("<green>█</green>");
                else if (pct >= 33) sb.append("<yellow>█</yellow>");
                else sb.append("<red>█</red>");
            } else {
                sb.append("<gray>▒</gray>");
            }
        }
        sb.append("</dark_gray>]");
        return sb.toString();
    }

    // =========================
    // DATA CLASSES
    // =========================
    public static class Vote {
        public String name;
        public String title;
        public String question;
        public List<Answer> answers;
        public UUID creator;
        public long createdAt;
        public long expiresAt;
        public boolean ended;
        public Map<UUID, Integer> votes; // player -> answer index

        /** How many players are online (can vote) */
        public int getDisplayTotal() {
            return (int) Bukkit.getOnlinePlayers().stream()
                    .filter(p -> !p.hasPermission("ui.command.vote.bypass"))
                    .count();
        }
    }

    public static class Answer {
        public String title;
        public String description;
    }

    // =========================
    // SHUTDOWN — cancel all active timers
    // =========================
    public static void shutdown() {
        for (BukkitTask task : closeTimers.values()) {
            try {
                task.cancel();
            } catch (Exception ignored) {}
        }
        closeTimers.clear();
        ConsoleLogger.info("[VOTE] All timers cancelled.");
    }

    // =========================
    // GET VOTES (for tab completion)
    // =========================
    public static Set<String> getVoteNames() {
        return votes.keySet();
    }

    public static Vote getVote(String name) {
        return votes.get(name.toLowerCase());
    }

    // =========================
    // TAB COMPLETION
    // =========================
    public static List<String> tabComplete(Player player, String[] args) {
        if (args.length == 1) {
            List<String> opts = new ArrayList<>();
            if (player.hasPermission("ui.command.vote.create")) opts.add("create");
            if (player.hasPermission("ui.command.vote.delete")) opts.add("delete");
            if (player.hasPermission("ui.command.vote.change")) opts.add("change");
            if (player.hasPermission("ui.command.vote.stats")) opts.add("stats");
            opts.addAll(votes.keySet());
            return filterByInput(opts, args[0]);
        }

        String sub = args[1].toLowerCase();

        return switch (sub) {
            case "create" -> tabCompleteCreate(args);
            case "delete" -> tabCompleteDelete(args);
            case "change" -> tabCompleteChange(args);
            case "stats" -> tabCompleteStats(args);
            default -> {
                // /ui vote <name> [answerIndex]
                Vote vote = votes.get(sub);
                if (vote != null && args.length == 3) {
                    List<String> opts = new ArrayList<>();
                    for (int i = 0; i < vote.answers.size(); i++) {
                        opts.add(String.valueOf(i));
                        opts.add(vote.answers.get(i).title);
                    }
                    yield filterByInput(opts, args[2]);
                }
                yield List.of();
            }
        };
    }

    private static List<String> tabCompleteCreate(String[] args) {
        // /ui vote create <name> <title> <desc> -answer_1:t,d -time:5m
        if (args.length <= 4) return List.of(); // name, title, desc — free text
        String last = args[args.length - 1];
        List<String> opts = new ArrayList<>();
        if (!last.startsWith("-")) {
            opts.add("-answer_1:");
            opts.add("-answer_2:");
            opts.add("-answer_3:");
            opts.add("-time:");
        } else if (last.startsWith("-answer_")) {
            opts.add(last + "");
        } else if (last.startsWith("-time:")) {
            opts.add("-time:5m");
            opts.add("-time:1h");
            opts.add("-time:1d");
        } else {
            opts.add("-answer_1:");
            opts.add("-answer_2:");
            opts.add("-answer_3:");
            opts.add("-time:");
        }
        return filterByInput(opts, last);
    }

    private static List<String> tabCompleteDelete(String[] args) {
        // /ui vote delete <name>
        if (args.length == 3) return filterByInput(new ArrayList<>(votes.keySet()), args[2]);
        return List.of();
    }

    private static List<String> tabCompleteChange(String[] args) {
        // /ui vote change <name> [-title X] [-desc Y] [-answer_N:t,d] [-time:X]
        if (args.length == 3) return filterByInput(new ArrayList<>(votes.keySet()), args[2]);
        String last = args[args.length - 1];
        List<String> opts = new ArrayList<>();
        if (!last.startsWith("-")) {
            opts.add("-title");
            opts.add("-desc");
            opts.add("-answer_1:");
            opts.add("-answer_2:");
            opts.add("-time:");
        } else if (last.startsWith("-time:")) {
            opts.add("-time:5m");
            opts.add("-time:1h");
            opts.add("-time:1d");
        } else if (last.startsWith("-answer_")) {
            opts.add(last + "");
        } else {
            opts.add("-title");
            opts.add("-desc");
            opts.add("-answer_1:");
            opts.add("-answer_2:");
            opts.add("-time:");
        }
        return filterByInput(opts, last);
    }

    private static List<String> tabCompleteStats(String[] args) {
        // /ui vote stats <name>
        if (args.length == 3) return filterByInput(new ArrayList<>(votes.keySet()), args[2]);
        return List.of();
    }

    private static List<String> filterByInput(List<String> options, String input) {
        String lower = input.toLowerCase();
        return options.stream()
                .filter(s -> s.toLowerCase().startsWith(lower))
                .collect(Collectors.toList());
    }
}
