package com.quiz;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Quiz Leaderboard System
 *
 * Flow:
 *  1. Poll /quiz/messages 10 times (poll=0..9) with a 5-second delay between each.
 *  2. Deduplicate events using the composite key (roundId + participant).
 *  3. Aggregate scores per participant.
 *  4. Sort leaderboard by totalScore descending, then participant ascending.
 *  5. Submit once to /quiz/submit.
 */
public class QuizLeaderboardSystem {

    // ── Configuration ─────────────────────────────────────────────────────────
    private static final String BASE_URL = "https://devapigw.vidalhealthtpa.com/srm-quiz-task";
    private static final String REG_NO   = "2024CS101";   // PDF sample registration number
    private static final int    TOTAL_POLLS       = 10;
    private static final long   DELAY_BETWEEN_MS  = 5_000L; // 5 seconds

    // ── Shared utilities ──────────────────────────────────────────────────────
    private final HttpClient   httpClient   = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ── Deduplication: key = "roundId::participant" ───────────────────────────
    private final Set<String>          seenKeys = new HashSet<>();
    private final Map<String, Integer> scores   = new LinkedHashMap<>();
    private int uniqueEventCount = 0;

    // ═════════════════════════════════════════════════════════════════════════
    public static void main(String[] args) throws Exception {
        new QuizLeaderboardSystem().run();
    }

    public void run() throws Exception {
        System.out.println("=== Quiz Leaderboard System ===");
        System.out.println("Registration No : " + REG_NO);
        System.out.println("Total Polls     : " + TOTAL_POLLS);
        System.out.println("Delay Between   : " + DELAY_BETWEEN_MS / 1000 + "s");
        System.out.println("================================\n");

        // ── Step 1 & 2: Poll and collect ──────────────────────────────────────
        for (int poll = 0; poll < TOTAL_POLLS; poll++) {
            System.out.printf("[Poll %d/%d] Fetching...%n", poll, TOTAL_POLLS - 1);

            String json = fetchMessages(poll);
            processResponse(json, poll);

            if (poll < TOTAL_POLLS - 1) {
                System.out.printf("[Poll %d/%d] Waiting %ds before next poll...%n",
                        poll, TOTAL_POLLS - 1, DELAY_BETWEEN_MS / 1000);
                Thread.sleep(DELAY_BETWEEN_MS);
            }
        }

        // ── Step 3: Build sorted leaderboard ─────────────────────────────────
        List<Map.Entry<String, Integer>> leaderboard = scores.entrySet()
                .stream()
                .sorted(
                        Map.Entry.<String, Integer>comparingByValue()
                                .reversed()
                                .thenComparing(Map.Entry.comparingByKey())
                )
                .collect(Collectors.toList());

        int totalScore = leaderboard.stream().mapToInt(Map.Entry::getValue).sum();

        System.out.println("\n=== Leaderboard ===");
        int rank = 1;
        for (Map.Entry<String, Integer> entry : leaderboard) {
            System.out.printf("#%d  %-20s  %d%n", rank++, entry.getKey(), entry.getValue());
        }
        System.out.println("-------------------");
        System.out.println("Combined Total Score: " + totalScore);
        System.out.println("Unique Events Count : " + uniqueEventCount);
        System.out.println("===================\n");

        // ── Step 4: Submit once ───────────────────────────────────────────────
        submitLeaderboard(leaderboard);
    }

    // ── Poll the API ──────────────────────────────────────────────────────────
    private String fetchMessages(int poll) throws IOException, InterruptedException {
        String url = String.format("%s/quiz/messages?regNo=%s&poll=%d", BASE_URL, REG_NO, poll);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .header("Accept", "application/json")
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException(
                    "GET /quiz/messages failed [poll=" + poll + "] status=" + response.statusCode()
                            + " body=" + response.body());
        }

        System.out.printf("  → HTTP 200 received (poll=%d)%n", poll);
        return response.body();
    }

    // ── Parse and deduplicate events ──────────────────────────────────────────
    private void processResponse(String json, int poll) throws IOException {
        JsonNode root   = objectMapper.readTree(json);
        JsonNode events = root.path("events");

        if (!events.isArray()) {
            System.out.printf("  [Poll %d] No events array found — skipping.%n", poll);
            return;
        }

        int newEvents  = 0;
        int dupEvents  = 0;
        for (JsonNode event : events) {
            String roundId = textOrNull(event, "roundId");
            String participant = textOrNull(event, "participant");
            int score = event.path("score").asInt();

            if (roundId == null || participant == null) {
                System.out.printf("  [INVALID EVENT IGNORED] roundId=%s  participant=%s  score=%d%n",
                        roundId, participant, score);
                continue;
            }

            String dedupKey = buildDedupKey(roundId, participant);

            if (seenKeys.contains(dedupKey)) {
                dupEvents++;
                System.out.printf("  [DUPLICATE IGNORED] roundId=%s  participant=%s  score=%d%n",
                        roundId, participant, score);
            } else {
                seenKeys.add(dedupKey);
                scores.merge(participant, score, Integer::sum);
                uniqueEventCount++;
                newEvents++;
                System.out.printf("  [ACCEPTED] roundId=%s  participant=%-15s  score=%d%n",
                        roundId, participant, score);
            }
        }

        System.out.printf("  Poll %d summary → accepted=%d  duplicates=%d%n%n",
                poll, newEvents, dupEvents);
    }

    private String buildDedupKey(String roundId, String participant) {
        return roundId + "::" + participant;
    }

    private String textOrNull(JsonNode node, String fieldName) {
        JsonNode value = node.path(fieldName);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }

        return normalizeText(value.asText());
    }

    private String normalizeText(String text) {
        if (text == null) {
            return null;
        }

        String normalized = text.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    // ── POST leaderboard ──────────────────────────────────────────────────────
    private void submitLeaderboard(List<Map.Entry<String, Integer>> leaderboard)
            throws IOException, InterruptedException {

        // Build request payload
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("regNo", REG_NO);

        ArrayNode leaderboardArray = payload.putArray("leaderboard");
        for (Map.Entry<String, Integer> entry : leaderboard) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("participant", entry.getKey());
            node.put("totalScore", entry.getValue());
            leaderboardArray.add(node);
        }

        String requestBody = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
        System.out.println("=== Submitting Leaderboard ===");
        System.out.println("Payload:\n" + requestBody);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/quiz/submit"))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        System.out.println("\n=== Submission Response ===");
        System.out.println("HTTP Status : " + response.statusCode());

        // Pretty-print the response
        try {
            JsonNode responseJson = objectMapper.readTree(response.body());
            System.out.println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(responseJson));

            boolean hasLegacyValidationFields =
                    responseJson.has("isCorrect")
                            || responseJson.has("expectedTotal")
                            || responseJson.has("message");

            if (hasLegacyValidationFields) {
                boolean isCorrect    = responseJson.path("isCorrect").asBoolean(false);
                boolean isIdempotent = responseJson.path("isIdempotent").asBoolean(false);
                String  message      = responseJson.path("message").asText("(no message)");
                int     submitted    = responseJson.path("submittedTotal").asInt(-1);
                int     expected     = responseJson.path("expectedTotal").asInt(-1);

                System.out.println("\n--- Result Summary ---");
                System.out.println("isCorrect    : " + isCorrect);
                System.out.println("isIdempotent : " + isIdempotent);
                System.out.println("Submitted    : " + submitted);
                System.out.println("Expected     : " + expected);
                System.out.println("Message      : " + message);
                System.out.println("---------------------");

                if (isCorrect) {
                    System.out.println("\n✅ SUCCESS — Leaderboard accepted!");
                } else {
                    System.out.println("\n❌ INCORRECT — Check deduplication logic.");
                }
            } else {
                int submitted = responseJson.path("submittedTotal").asInt(-1);
                int totalPollsMade = responseJson.path("totalPollsMade").asInt(-1);
                int attemptCount = responseJson.path("attemptCount").asInt(-1);

                System.out.println("\n--- Result Summary ---");
                System.out.println("Submitted    : " + submitted);
                System.out.println("Total Polls  : " + totalPollsMade);
                System.out.println("Attempt Count: " + attemptCount);
                System.out.println("---------------------");
                System.out.println(
                        "\n✅ Submission accepted by API, but this API version does not return correctness fields."
                );
            }

        } catch (Exception e) {
            System.out.println("Raw response: " + response.body());
        }
    }
}
