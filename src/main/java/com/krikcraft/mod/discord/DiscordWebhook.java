package com.krikcraft.mod.discord;

import com.krikcraft.mod.KrikCraftMod;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DiscordWebhook {

    private static final String WEBHOOK_URL =
            "https://discord.com/api/webhooks/1509248630932897884/" +
            "Cw-wONbGg8ltZi-5Prdg--awMJOMBj9xKcztrXPOLjLzpWptUFmQaROnBXntXOvoud5T";

    // Один поток — сообщения уходят по очереди без задержек
    private static final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "KrikCraft-Discord");
        t.setDaemon(true);
        return t;
    });

    // ANSI-цвет малиновый (для спавнеров ниже Y=21)
    // Discord рендерит ANSI в ```ansi блоках
    private static final String ANSI_CRIMSON = "\u001b[1;31m"; // жирный красный/малиновый
    private static final String ANSI_RESET   = "\u001b[0m";

    /**
     * Обычное сообщение (обычный цвет)
     */
    public static void send(String anarchy, String what, int x, int y, int z) {
        String text = String.format("[%s] %s | X: %d, Y: %d, Z: %d", anarchy, what, x, y, z);
        post(text);
    }

    /**
     * Сообщение малинового цвета через ANSI (для спавнеров ниже Y=21)
     */
    public static void sendCrimson(String anarchy, String what, int x, int y, int z) {
        String plain = String.format("[%s] %s | X: %d, Y: %d, Z: %d", anarchy, what, x, y, z);
        // Оборачиваем в ```ansi блок — Discord рендерит цвет
        String text = "```ansi\n" + ANSI_CRIMSON + plain + ANSI_RESET + "\n```";
        post(text);
    }

    private static void post(String message) {
        executor.execute(() -> {
            try {
                RequestConfig config = RequestConfig.custom()
                        .setConnectTimeout(4000)
                        .setSocketTimeout(4000)
                        .build();

                try (CloseableHttpClient client = HttpClients.custom()
                        .setDefaultRequestConfig(config).build()) {

                    HttpPost req = new HttpPost(WEBHOOK_URL);
                    req.setHeader("Content-Type", "application/json");

                    String escaped = message
                            .replace("\\", "\\\\")
                            .replace("\"", "\\\"");
                    // Не экранируем \n — они уже в строке как символы
                    String json = String.format("{\"content\":\"%s\"}", escaped);
                    req.setEntity(new StringEntity(json, StandardCharsets.UTF_8));
                    client.execute(req).close();
                }
            } catch (Exception e) {
                KrikCraftMod.LOGGER.error("Discord: " + e.getMessage());
            }
        });
    }
}
