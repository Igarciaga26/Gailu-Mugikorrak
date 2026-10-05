package com.example.basiclayout01

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(entities = [ItemEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun itemDao(): ItemDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context, scope: CoroutineScope): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "soothe_database"
                )
                    .addCallback(DatabaseCallback(scope))
                    .build()
                INSTANCE = instance
                instance
            }
        }

        private class DatabaseCallback(
            private val scope: CoroutineScope
        ) : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                INSTANCE?.let { database ->
                    scope.launch(Dispatchers.IO) {
                        populateDatabase(database.itemDao())
                    }
                }
            }

            suspend fun populateDatabase(dao: ItemDao) {
                val bodyItems = listOf(
                    ItemEntity(titleResId = R.string.ab1_inversions, drawableResId = R.drawable.ab1_inversions, category = "body"),
                    ItemEntity(titleResId = R.string.ab2_quick_yoga, drawableResId = R.drawable.ab2_quick_yoga, category = "body"),
                    ItemEntity(titleResId = R.string.ab3_stretching, drawableResId = R.drawable.ab3_stretching, category = "body"),
                    ItemEntity(titleResId = R.string.ab4_tabata, drawableResId = R.drawable.ab4_tabata, category = "body"),
                    ItemEntity(titleResId = R.string.ab5_hiit, drawableResId = R.drawable.ab5_hiit, category = "body"),
                    ItemEntity(titleResId = R.string.ab6_pre_natal_yoga, drawableResId = R.drawable.ab6_pre_natal_yoga, category = "body")
                )

                val favoriteItems = listOf(
                    ItemEntity(titleResId = R.string.fc1_short_mantras, drawableResId = R.drawable.fc1_short_mantras, category = "favorite"),
                    ItemEntity(titleResId = R.string.fc2_nature_meditations, drawableResId = R.drawable.fc2_nature_meditations, category = "favorite"),
                    ItemEntity(titleResId = R.string.fc3_stress_and_anxiety, drawableResId = R.drawable.fc3_stress_and_anxiety, category = "favorite"),
                    ItemEntity(titleResId = R.string.fc4_self_massage, drawableResId = R.drawable.fc4_self_massage, category = "favorite"),
                    ItemEntity(titleResId = R.string.fc5_overwhelmed, drawableResId = R.drawable.fc5_overwhelmed, category = "favorite"),
                    ItemEntity(titleResId = R.string.fc6_nightly_wind_down, drawableResId = R.drawable.fc6_nightly_wind_down, category = "favorite")
                )

                dao.insertAll(bodyItems)
                dao.insertAll(favoriteItems)
            }
        }
    }
}
