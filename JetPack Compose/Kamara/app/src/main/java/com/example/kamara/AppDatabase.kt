package com.example.kamara

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

// Una fila de la tabla: una foto o un QR
@Entity(tableName = "registros")
data class Registro(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tipo: String,        // "FOTO" o "QR"
    val contenido: String,   // ruta de la foto o texto del QR
    val fecha: Long          // milisegundos desde 1970
)

// Operaciones sobre la tabla
@Dao
interface RegistroDao {
    @Insert
    suspend fun insertar(registro: Registro)

    @Query("SELECT * FROM registros ORDER BY fecha DESC")
    fun obtenerTodos(): Flow<List<Registro>>

    @Query("DELETE FROM registros")
    suspend fun borrarTodos()
}

@Database(entities = [Registro::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun registroDao(): RegistroDao

    companion object {
        @Volatile
        private var instancia: AppDatabase? = null

        // Una sola base de datos para toda la app
        fun obtener(context: Context): AppDatabase =
            instancia ?: synchronized(this) {
                instancia ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "kamara.db"
                ).build().also { instancia = it }
            }
    }
}