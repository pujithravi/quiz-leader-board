# Quiz Leaderboard System

A Java application that polls the quiz validator API 10 times, deduplicates repeated round events, builds a participant leaderboard, and submits the final result.

Repository: `http://github.com/pujithravi/quiz-leader-board`

## Problem Summary

The validator API returns quiz score events across 10 polls. Some events are repeated in later polls, so the app must:

1. Poll `/quiz/messages` for `poll=0` through `poll=9`
2. Wait 5 seconds between polls
3. Deduplicate repeated events using `(roundId, participant)`
4. Sum scores per participant
5. Sort the leaderboard by `totalScore` descending, then participant name ascending
6. Submit the final leaderboard to `/quiz/submit`

## Project Structure

```text
quiz-leaderboard/
├── pom.xml
├── README.md
├── .gitignore
└── src/
    └── main/
        └── java/
            └── com/
                └── quiz/
                    └── QuizLeaderboardSystem.java
```

## Prerequisites

- Java 17+
- Maven 3.8+

## Configuration

The registration number is currently hardcoded in [QuizLeaderboardSystem.java](/Users/pujithraavi/Downloads/quiz-leaderboard/src/main/java/com/quiz/QuizLeaderboardSystem.java:34):

```java
private static final String REG_NO = "2024CS101";
```

Update it if you need to submit for a different registration number.

## Build And Run

Build the shaded JAR:

```bash
mvn -q -DskipTests package
```

Run the app:

```bash
java -cp target/quiz-leaderboard-1.0.0.jar com.quiz.QuizLeaderboardSystem
```

## API Shape

### GET `/quiz/messages`

Example response:

```json
{
  "regNo": "2024CS101",
  "pollIndex": 0,
  "totalPolls": 97,
  "events": [
    { "roundId": "R1", "participant": "Alice", "score": 120 },
    { "roundId": "R1", "participant": "Bob", "score": 95 }
  ],
  "meta": {
    "hint": "Rounds and scores may repeat across polls. Handle them correctly.",
    "totalPollsRequired": 10
  }
}
```

### POST `/quiz/submit`

Submitted payload:

```json
{
  "regNo": "2024CS101",
  "leaderboard": [
    { "participant": "Bob", "totalScore": 295 },
    { "participant": "Alice", "totalScore": 280 },
    { "participant": "Charlie", "totalScore": 260 }
  ]
}
```

Observed live response format:

```json
{
  "regNo": "2024CS101",
  "totalPollsMade": 4194,
  "submittedTotal": 835,
  "attemptCount": 365
}
```

Note: the current API version does not always return legacy fields like `isCorrect`, `expectedTotal`, or `message`.

## How The App Works

### Polling

The app polls exactly 10 times and waits 5 seconds between each request.

### Deduplication

Each event is uniquely identified by:

```java
roundId + "::" + participant
```

If the same event appears again in later polls, it is ignored.

### Aggregation

Accepted event scores are summed per participant in a `LinkedHashMap<String, Integer>`.

### Sorting

The leaderboard is sorted by:

1. `totalScore` descending
2. `participant` ascending

## Sample Output

```text
=== Leaderboard ===
#1  Bob                   295
#2  Alice                 280
#3  Charlie               260
-------------------
Combined Total Score: 835
Unique Events Count : 9
===================
```

## Dependencies

- `jackson-databind` `2.17.1` for JSON parsing and serialization

## Notes

- The Maven Shade plugin builds a fat JAR with dependencies included.
- The repo ignores build output and macOS metadata files.
