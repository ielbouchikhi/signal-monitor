package com.signalmonitor.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [MetricSample::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun metricSampleDao(): MetricSampleDao
}
