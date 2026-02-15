package example;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.UpdatesListener;
import com.pengrad.telegrambot.model.Update;
import com.pengrad.telegrambot.request.SendMessage;
import com.pengrad.telegrambot.response.SendResponse;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.*;
import java.util.*;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DiceBotMain {

    private static String botToken = null;
    private static final String BOT_TOKEN_FILE = "bot_token.txt";

    private static TelegramBot bot;
    private static volatile boolean running = false;

    private static final Set<Long> allowedChats = new HashSet<>();
    private static final Map<Long, Map<Long, UserStats>> chatStats = new HashMap<>();
    private static final Map<Long, String> userToName = new HashMap<>();

    private static final Random RANDOM = new Random();
    private static final Pattern DICE_PATTERN = Pattern.compile("(?i)^(\\d+)?d(\\d+)([+-]\\d+)?$");

    private static final String CONFIG_FILE = "allowed_chats.txt";
    private static final String STATS_FILE = "stats.ser";

    private static class UserStats implements Serializable {
        long sum = 0;
        int count = 0;
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(DiceBotMain::createAndShowGUI);
    }

    private static void createAndShowGUI() {
        loadBotToken();
        loadAllowedChats();
        loadStats();

        JFrame frame = new JFrame("D&D Dice Bot Control");
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.setSize(550, 550);
        frame.setLayout(new BorderLayout());

        JLabel statusLabel = new JLabel("Bot status: Stopped", SwingConstants.CENTER);
        frame.add(statusLabel, BorderLayout.NORTH);

        JPanel tokenPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        tokenPanel.add(new JLabel("Bot token:"));

        JTextField tokenField = new JTextField(30);
        if (botToken != null) {
            tokenField.setText(botToken);
        }

        JButton saveTokenButton = new JButton("Save token");
        saveTokenButton.addActionListener(e -> {
            String token = tokenField.getText().trim();
            if (token.isEmpty()) {
                JOptionPane.showMessageDialog(frame,
                        "Token cannot be empty.",
                        "Token",
                        JOptionPane.WARNING_MESSAGE);
                return;
            }
            botToken = token;
            saveBotToken();
            JOptionPane.showMessageDialog(frame,
                    "Token saved.\nDo not commit this file to Git.",
                    "Token",
                    JOptionPane.INFORMATION_MESSAGE);
        });

        tokenPanel.add(tokenField);
        tokenPanel.add(saveTokenButton);

        DefaultListModel<String> listModel = new DefaultListModel<>();
        for (Long id : allowedChats) {
            listModel.addElement(id.toString());
        }
        JList<String> chatList = new JList<>(listModel);
        chatList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane listScroll = new JScrollPane(chatList);

        JPanel centerPanel = new JPanel(new BorderLayout());
        centerPanel.add(tokenPanel, BorderLayout.NORTH);
        centerPanel.add(listScroll, BorderLayout.CENTER);
        frame.add(centerPanel, BorderLayout.CENTER);

        JPanel chatControlPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));

        JTextField chatField = new JTextField(15);
        JButton addButton = new JButton("Add chat ID");
        addButton.addActionListener(e -> {
            String input = chatField.getText().trim();
            if (input.isEmpty()) return;
            try {
                long id = Long.parseLong(input);
                if (allowedChats.add(id)) {
                    listModel.addElement(String.valueOf(id));
                    saveAllowedChats();
                    chatField.setText("");
                    JOptionPane.showMessageDialog(frame,
                            "chat ID added: " + id + "\nBot will immediately work in this chat if it is running.");
                } else {
                    JOptionPane.showMessageDialog(frame, "This chat ID is already in the list.");
                }
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(frame,
                        "Invalid chat ID format (must be a number, for example -1001234567890).");
            }
        });

        JButton removeButton = new JButton("Remove selected");
        removeButton.addActionListener(e -> {
            String selected = chatList.getSelectedValue();
            if (selected == null) {
                JOptionPane.showMessageDialog(frame, "Select a chat ID to remove.");
                return;
            }
            int confirm = JOptionPane.showConfirmDialog(frame,
                    "Remove chat ID " + selected + "?\nThe bot will stop processing messages in this chat.",
                    "Confirm removal",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            if (confirm == JOptionPane.YES_OPTION) {
                long idToRemove = Long.parseLong(selected);
                allowedChats.remove(idToRemove);
                listModel.removeElement(selected);
                saveAllowedChats();
                JOptionPane.showMessageDialog(frame, "chat ID removed: " + selected);
            }
        });

        chatControlPanel.add(new JLabel("chat ID:"));
        chatControlPanel.add(chatField);
        chatControlPanel.add(addButton);
        chatControlPanel.add(removeButton);

        JPanel botControlPanel = new JPanel();
        JButton startButton = new JButton("Start bot");
        JButton stopButton = new JButton("Stop bot");
        stopButton.setEnabled(false);

        startButton.addActionListener(e -> {
            if (running) return;

            String token = tokenField.getText().trim();
            if (token.isEmpty()) {
                JOptionPane.showMessageDialog(frame,
                        "Specify bot token and click \"Save token\" first.",
                        "Token required",
                        JOptionPane.WARNING_MESSAGE);
                return;
            }
            botToken = token;
            saveBotToken();

            try {
                startBot();
                running = true;
                statusLabel.setText("Bot status: Running");
                startButton.setEnabled(false);
                stopButton.setEnabled(true);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(frame,
                        "Failed to start bot: " + ex.getMessage(),
                        "Error",
                        JOptionPane.ERROR_MESSAGE);
                ex.printStackTrace();
            }
        });

        stopButton.addActionListener(e -> {
            if (!running) return;
            stopBot();
            running = false;
            statusLabel.setText("Bot status: Stopped");
            startButton.setEnabled(true);
            stopButton.setEnabled(false);
        });

        botControlPanel.add(startButton);
        botControlPanel.add(stopButton);

        JButton clearStatsButton = new JButton("Clear stats");
        clearStatsButton.addActionListener(e -> {
            int confirm = JOptionPane.showConfirmDialog(frame,
                    "Clear all luck statistics for all chats?\nThis action cannot be undone.",
                    "Clear stats",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            if (confirm == JOptionPane.YES_OPTION) {
                chatStats.clear();
                userToName.clear();
                saveStats();
                JOptionPane.showMessageDialog(frame, "Statistics cleared.");
            }
        });

        JPanel southPanel = new JPanel();
        southPanel.setLayout(new BoxLayout(southPanel, BoxLayout.Y_AXIS));
        southPanel.add(chatControlPanel);
        southPanel.add(botControlPanel);
        southPanel.add(clearStatsButton);

        frame.add(southPanel, BorderLayout.SOUTH);

        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                saveBotToken();
                saveAllowedChats();
                saveStats();
                if (running) {
                    stopBot();
                }
                frame.dispose();
                System.exit(0);
            }
        });

        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private static void startBot() {
        if (botToken == null || botToken.isEmpty()) {
            throw new IllegalStateException("Bot token not set");
        }

        bot = new TelegramBot(botToken);
        bot.setUpdatesListener(updates -> {
            for (Update update : updates) {
                try {
                    processUpdate(update);
                } catch (Exception e) {
                    System.err.println("Error handling update: " + e.getMessage());
                    e.printStackTrace();
                }
            }
            return UpdatesListener.CONFIRMED_UPDATES_ALL;
        });
        System.out.println("Bot started and listening for updates...");
    }

    private static void stopBot() {
        if (bot != null) {
            bot.setUpdatesListener(null);
            bot.shutdown();
            bot = null;
            System.out.println("Bot stopped.");
        }
        saveStats();
    }

    private static void processUpdate(Update update) {
        if (update.message() == null || update.message().text() == null) {
            return;
        }

        var message = update.message();
        long chatId = message.chat().id();
        long userId = message.from().id();
        String text = message.text().trim();

        if (!allowedChats.contains(chatId)) {
            return;
        }

        var from = message.from();
        if (from != null) {
            String username = from.username();
            String name = (username != null) ? "@" + username :
                    from.firstName() + (from.lastName() != null ? " " + from.lastName() : "");
            userToName.put(userId, name);
        }

        if (text.startsWith("/roll")) {
            handleRollCommand(chatId, userId, text.substring("/roll".length()).trim());
        } else if (text.equals("/start") || text.equals("/help")) {
            sendHelp(chatId);
        } else if (text.equals("/stats")) {
            handleStatsCommand(chatId);
        }
    }

    private static void handleRollCommand(long chatId, long userId, String args) {
        if (args.isEmpty()) {
            sendHelp(chatId);
            return;
        }

        Matcher matcher = DICE_PATTERN.matcher(args);
        if (!matcher.matches()) {
            sendMessage(chatId, "Invalid format. Examples:\n/roll d20\n/roll 2d6+5\n/roll 4d8-2");
            return;
        }

        String countStr = matcher.group(1);
        int count = (countStr != null && !countStr.isEmpty()) ? Integer.parseInt(countStr) : 1;
        int sides = Integer.parseInt(matcher.group(2));
        String modStr = matcher.group(3);
        int modifier = (modStr != null) ? Integer.parseInt(modStr) : 0;

        if (count < 1 || count > 100) {
            sendMessage(chatId, "Dice count must be between 1 and 100.");
            return;
        }
        if (!isValidDnDSides(sides)) {
            sendMessage(chatId, "Supported dice: d4, d6, d8, d10, d12, d20.");
            return;
        }

        StringBuilder result = new StringBuilder("Roll ").append(args).append(": ");
        StringBuilder rollsPart = new StringBuilder();
        int total = 0;
        boolean isSingleNoMod = (count == 1 && modifier == 0);

        for (int i = 0; i < count; i++) {
            int roll = RANDOM.nextInt(sides) + 1;
            total += roll;
            updateStats(chatId, userId, roll);

            rollsPart.append(roll);
            if (i < count - 1) rollsPart.append(" + ");
        }

        result.append(rollsPart);

        if (modifier != 0) {
            total += modifier;
            result.append(" ").append(modifier >= 0 ? "+" : "").append(modifier);
            result.append(" = **").append(total).append("**");
        } else if (!isSingleNoMod) {
            result.append(" = **").append(total).append("**");
        }

        sendMessage(chatId, result.toString());
    }

    private static void updateStats(long chatId, long userId, int roll) {
        Map<Long, UserStats> userMap = chatStats.computeIfAbsent(chatId, k -> new HashMap<>());
        UserStats stats = userMap.computeIfAbsent(userId, k -> new UserStats());
        stats.sum += roll;
        stats.count++;
        saveStats();
    }

    private static void handleStatsCommand(long chatId) {
        StringBuilder sb = new StringBuilder("📊 Luck stats for this chat:\n\n");

        Map<Long, UserStats> userMap = chatStats.getOrDefault(chatId, new HashMap<>());
        if (userMap.isEmpty()) {
            sb.append("No rolls yet.");
        } else {
            List<Map.Entry<Long, UserStats>> sorted = new ArrayList<>(userMap.entrySet());
            sorted.sort((a, b) -> {
                double avgA = a.getValue().count > 0 ? (double) a.getValue().sum / a.getValue().count : 0;
                double avgB = b.getValue().count > 0 ? (double) b.getValue().sum / b.getValue().count : 0;
                return Double.compare(avgB, avgA);
            });

            for (Map.Entry<Long, UserStats> entry : sorted) {
                long uid = entry.getKey();
                UserStats s = entry.getValue();
                String name = userToName.getOrDefault(uid, "User " + uid);
                double avg = s.count > 0 ? (double) s.sum / s.count : 0.0;
                sb.append(name)
                        .append(": avg **").append(String.format(Locale.US, "%.2f", avg))
                        .append("** (").append(s.count).append(" rolls)\n");
            }
        }

        sendMessage(chatId, sb.toString());
    }

    private static boolean isValidDnDSides(int sides) {
        return sides == 4 || sides == 6 || sides == 8 || sides == 10 || sides == 12 || sides == 20;
    }

    private static void sendHelp(long chatId) {
        String help = """
                🎲 D&D Dice Bot
                
                Commands:
                /roll d20 — roll d20
                /roll 2d6+3 — 2d6 +3
                /roll 4d8-2 — 4d8 -2
                /roll d6 — single d6
                
                /stats — luck statistics for all players in this chat
                
                Supported dice: d4, d6, d8, d10, d12, d20
                """;
        sendMessage(chatId, help);
    }

    private static void sendMessage(long chatId, String text) {
        if (bot == null) return;
        SendMessage request = new SendMessage(chatId, text)
                .parseMode(com.pengrad.telegrambot.model.request.ParseMode.Markdown);
        SendResponse response = bot.execute(request);
        if (!response.isOk()) {
            System.err.println("Send error: " + response.description());
        }
    }

    private static void loadBotToken() {
        File file = new File(BOT_TOKEN_FILE);
        if (!file.exists()) return;
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line = br.readLine();
            if (line != null && !line.trim().isEmpty()) {
                botToken = line.trim();
            }
        } catch (IOException e) {
            System.err.println("Error reading bot token: " + e.getMessage());
        }
    }

    private static void saveBotToken() {
        if (botToken == null) return;
        try (PrintWriter pw = new PrintWriter(new FileWriter(BOT_TOKEN_FILE))) {
            pw.println(botToken);
        } catch (IOException e) {
            System.err.println("Error saving bot token: " + e.getMessage());
        }
    }

    private static void loadAllowedChats() {
        File file = new File(CONFIG_FILE);
        if (!file.exists()) return;
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) {
                    try {
                        allowedChats.add(Long.parseLong(line));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Error reading allowed_chats: " + e.getMessage());
        }
    }

    private static void saveAllowedChats() {
        try (PrintWriter pw = new PrintWriter(new FileWriter(CONFIG_FILE))) {
            for (Long id : allowedChats) {
                pw.println(id);
            }
        } catch (IOException e) {
            System.err.println("Error saving allowed_chats: " + e.getMessage());
        }
    }

    private static void loadStats() {
        File file = new File(STATS_FILE);
        if (!file.exists()) return;
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(file))) {
            @SuppressWarnings("unchecked")
            Map<Long, Map<Long, UserStats>> loadedStats =
                    (Map<Long, Map<Long, UserStats>>) ois.readObject();
            @SuppressWarnings("unchecked")
            Map<Long, String> loadedNames =
                    (Map<Long, String>) ois.readObject();

            chatStats.clear();
            chatStats.putAll(loadedStats);
            userToName.clear();
            userToName.putAll(loadedNames);
        } catch (Exception e) {
            System.err.println("Error loading stats: " + e.getMessage());
            chatStats.clear();
            userToName.clear();
        }
    }

    private static void saveStats() {
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(STATS_FILE))) {
            oos.writeObject(chatStats);
            oos.writeObject(userToName);
        } catch (IOException e) {
            System.err.println("Error saving stats: " + e.getMessage());
        }
    }
}
