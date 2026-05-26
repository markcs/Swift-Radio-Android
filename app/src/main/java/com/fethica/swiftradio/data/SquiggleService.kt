package com.fethica.swiftradio.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Serializable
data class SquiggleResponse(
    val games: List<SquiggleGame> = emptyList()
)

@Serializable
data class SquiggleGame(
    val complete: Int? = null,
    val hteam: String? = null,
    val ateam: String? = null,
    val hscore: Int? = null,
    val ascore: Int? = null,
    val date: String? = null,
    val localtime: String? = null,
    val updated: String? = null,
    val winner: String? = null
)

class SquiggleService(
    okHttpClient: OkHttpClient,
    scope: CoroutineScope
) {
    private val client = HttpClient(OkHttp) {
        engine {
            preconfigured = okHttpClient
        }
        install(ContentNegotiation) {
            json(Json { 
                ignoreUnknownKeys = true
                coerceInputValues = true
            })
        }
    }

    val liveScore: StateFlow<String?> = flow {
        var allGamesCompletedAt: Long? = null
        val localSdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val dateSdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

        while (true) {
            val now = System.currentTimeMillis()
            val currentYear = Calendar.getInstance().get(Calendar.YEAR)
            val today = dateSdf.format(Date())

            // 1. Fetch games schedule/scores
            val allGames = fetchGames(currentYear)
            if (allGames == null) {
                // Failure! Retry in 30 seconds instead of an hour
                delay(30_000L)
                continue
            }

            val todayGames = allGames.filter { it.date?.startsWith(today) == true }

            if (todayGames.isEmpty()) {
                emit(null)
                allGamesCompletedAt = null
                delay(3600_000L) // No games today, check again in an hour
                continue
            }

            // 2. Analyze game states
            var anyGameInPollingWindow = false
            var allGamesComplete = true
            val gamesToDisplay = mutableListOf<SquiggleGame>()

            for (game in todayGames) {
                val startMillis = try {
                    localSdf.parse(game.localtime ?: "")?.time ?: 0L
                } catch (e: Exception) { 0L }

                val isComplete = (game.complete ?: 0) == 100
                val isWithinWindow = now >= (startMillis - 5 * 60 * 1000L) && 
                                     now <= (startMillis + 3 * 60 * 60 * 1000L)

                if (isWithinWindow && !isComplete) {
                    anyGameInPollingWindow = true
                }
                if (!isComplete) {
                    allGamesComplete = false
                }

                if ((game.complete ?: 0) > 0) {
                    if (isComplete) {
                        val updatedMillis = try {
                            localSdf.parse(game.updated ?: "")?.time ?: 0L
                        } catch (e: Exception) { 0L }

                        if (now - updatedMillis < 60 * 60 * 1000L) {
                            gamesToDisplay.add(game)
                        }
                    } else {
                        gamesToDisplay.add(game)
                    }
                }
            }

            // 3. Handle Completion Timing
            if (allGamesComplete && todayGames.isNotEmpty()) {
                if (allGamesCompletedAt == null) {
                    allGamesCompletedAt = now
                }
            } else {
                allGamesCompletedAt = null
            }

            // 4. Update Display
            val showScores = gamesToDisplay.isNotEmpty() && 
                             (!allGamesComplete || (allGamesCompletedAt != null && now - allGamesCompletedAt < 15 * 60 * 1000L))

            val finalScore = if (showScores) {
                formatScores(gamesToDisplay)
            } else {
                null
            }

            emit(finalScore)

            // 5. Determine Delay
            val nextDelay = if (anyGameInPollingWindow) 20_000L else 60_000L
            delay(nextDelay)
        }
    }.stateIn(
        scope = scope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = null
    )

    suspend fun fetchGames(year: Int): List<SquiggleGame>? {
        return try {
            val response: SquiggleResponse = client.get("https://api.squiggle.com.au/?q=games;year=$year").body()
            response.games
        } catch (e: Exception) {
            android.util.Log.e("SquiggleService", "Failed to fetch games", e)
            null
        }
    }

    fun formatScores(games: List<SquiggleGame>): String? {
        if (games.isEmpty()) return null
        return games.joinToString(" | ") { game ->
            val hTeam = game.hteam ?: "Unknown"
            val aTeam = game.ateam ?: "Unknown"
            val hScore = game.hscore ?: 0
            val aScore = game.ascore ?: 0

            if (game.complete == 100) {
                val winner = game.winner
                if (winner != null) {
                    val (winningScore, losingTeam, losingScore) = if (winner == hTeam) {
                        Triple(hScore, aTeam, aScore)
                    } else {
                        Triple(aScore, hTeam, hScore)
                    }
                    "$winner ($winningScore) beat $losingTeam ($losingScore)"
                } else {
                    "$hTeam ($hScore) v $aTeam ($aScore)"
                }
            } else {
                "$hTeam ($hScore) v $aTeam ($aScore)"
            }
        }
    }

    @Deprecated("Use fetchGames and handle logic in caller", ReplaceWith("fetchGames(year)"))
    suspend fun fetchLiveScore(year: Int): String? {
        val games = fetchGames(year) ?: return null
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val today = sdf.format(Date())
        val activeGames = games.filter { game ->
            val gameDate = game.date ?: ""
            val isToday = gameDate.startsWith(today)
            val isStarted = (game.complete ?: 0) > 0
            isToday && isStarted
        }
        return formatScores(activeGames)
    }
}
