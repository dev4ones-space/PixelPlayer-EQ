package com.theveloper.pixelplay.utils

import com.theveloper.pixelplay.PixelPlayApplication
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.model.Song
import java.util.concurrent.TimeUnit

fun formatDuration(milliseconds: Long): String {
    if (milliseconds <= 0L) return "00:00"

    val totalSeconds = milliseconds / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    return if (hours > 0) {
        String.format("%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}

fun formatTotalDuration(songs: List<Song>): String {
    val totalMillis = songs.sumOf { it.duration }
    val hours = TimeUnit.MILLISECONDS.toHours(totalMillis)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(totalMillis) % 60
    val context = PixelPlayApplication.instance.applicationContext
    return if (hours > 0) {
        context.getString(R.string.duration_hours_minutes_long, hours, minutes)
    } else {
        context.getString(R.string.duration_minutes_long, minutes)
    }
}

fun formatListeningDurationLong(milliseconds: Long): String {
    val totalMinutes = TimeUnit.MILLISECONDS.toMinutes(milliseconds)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    val seconds = TimeUnit.MILLISECONDS.toSeconds(milliseconds) % 60
    val context = PixelPlayApplication.instance.applicationContext
    return when {
        hours > 0 && minutes > 0 -> context.getString(R.string.duration_hours_minutes_medium, hours, minutes)
        hours > 0 -> context.getString(R.string.duration_hours_medium, hours)
        minutes > 0 -> context.getString(R.string.duration_minutes_medium, minutes)
        else -> context.getString(R.string.duration_seconds_medium, seconds)
    }
}

fun formatListeningDurationCompact(milliseconds: Long): String {
    val totalMinutes = TimeUnit.MILLISECONDS.toMinutes(milliseconds)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    val seconds = TimeUnit.MILLISECONDS.toSeconds(milliseconds) % 60
    val context = PixelPlayApplication.instance.applicationContext
    return when {
        hours > 0 && minutes > 0 -> context.getString(R.string.duration_hours_minutes_short, hours, minutes)
        hours > 0 -> context.getString(R.string.duration_hours_short, hours)
        minutes > 0 -> context.getString(R.string.duration_minutes_short, minutes)
        else -> context.getString(R.string.duration_seconds_short, seconds)
    }
}

fun formatSongCount(count: Int): String {
    val context = PixelPlayApplication.instance.applicationContext
    return if (count <= 1) {
        context.getString(R.string.song_count_singular, count)
    } else {
        context.getString(R.string.song_count_plural, count)
    }
}

fun formatTimeAgo(timestamp: Long): String {
    val context = PixelPlayApplication.instance.applicationContext
    if (timestamp <= 0) return context.getString(R.string.time_ago_never)
    val diff = System.currentTimeMillis() - timestamp
    val seconds = diff / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    val days = hours / 24

    return when {
        days > 0 -> if (days == 1L) {
            context.getString(R.string.time_ago_day_singular)
        } else {
            context.getString(R.string.time_ago_days_plural, days)
        }
        hours > 0 -> if (hours == 1L) {
            context.getString(R.string.time_ago_hour_singular)
        } else {
            context.getString(R.string.time_ago_hours_plural, hours)
        }
        minutes > 0 -> if (minutes == 1L) {
            context.getString(R.string.time_ago_minute_singular)
        } else {
            context.getString(R.string.time_ago_minutes_plural, minutes)
        }
        else -> context.getString(R.string.time_ago_just_now)
    }
}
