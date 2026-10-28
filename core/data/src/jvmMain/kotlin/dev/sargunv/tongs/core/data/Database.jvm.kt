package dev.sargunv.tongs.core.data

import androidx.room3.Room
import androidx.room3.RoomDatabase

internal actual fun databaseBuilder(path: String): RoomDatabase.Builder<TongsDatabase> =
  Room.databaseBuilder<TongsDatabase>(path, factory = TongsDatabaseConstructor::initialize)
