package com.example.kamara

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import org.opencv.android.OpenCVLoader
import java.util.concurrent.Executors
import kotlin.math.max

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Cargamos OpenCV antes de usarlo
        if (!OpenCVLoader.initLocal()) {
            Toast.makeText(this, "No se pudo cargar OpenCV", Toast.LENGTH_LONG).show()
        }

        setContent {
            PantallaCamara()
        }
    }
}

@Composable
fun PantallaCamara() {
    val context = LocalContext.current

    var permisoConcedido by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val lanzador = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { concedido ->
        permisoConcedido = concedido
    }

    LaunchedEffect(Unit) {
        if (!permisoConcedido) lanzador.launch(Manifest.permission.CAMERA)
    }

    if (permisoConcedido) {
        VistaCamara()
    } else {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Se necesita permiso de cámara")
        }
    }
}

@Composable
fun VistaCamara() {
    val context = LocalContext.current
    val lifecycleOwner = context as LifecycleOwner

    var camaraFrontal by remember { mutableStateOf(false) }
    var flashEncendido by remember { mutableStateOf(false) }
    var resultado by remember { mutableStateOf(Resultado(emptyList(), 1, 1)) }

    // Hilo aparte para analizar imágenes sin bloquear la pantalla
    val ejecutor = remember { Executors.newSingleThreadExecutor() }

    val controlador = remember {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(CameraController.IMAGE_CAPTURE or CameraController.IMAGE_ANALYSIS)
            setImageAnalysisAnalyzer(ejecutor, AnalizadorFiguras { resultado = it })
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PreviewView(ctx).apply {
                    this.controller = controlador
                    controlador.bindToLifecycle(lifecycleOwner)
                }
            }
        )

        // Contornos, nombres de las figuras y marco del QR encima de la cámara
        CapaFiguras(resultado, camaraFrontal)

        // Texto del QR leído (si es un enlace, al tocarlo se abre)
        resultado.qr?.let { qr ->
            Text(
                text = qr.texto,
                color = Color.White,
                maxLines = 3,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(16.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .clickable { abrirSiEsEnlace(context, qr.texto) }
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            )
        }

        // Botón de flash (linterna): a la izquierda del disparo
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 32.dp, bottom = 62.dp)
                .size(48.dp)
                .clip(CircleShape)
                .background(
                    if (flashEncendido) Color.Yellow.copy(alpha = 0.6f)
                    else Color.Black.copy(alpha = 0.4f)
                )
                .clickable {
                    if (controlador.cameraInfo?.hasFlashUnit() == true) {
                        flashEncendido = !flashEncendido
                        controlador.enableTorch(flashEncendido)
                    } else {
                        Toast.makeText(context, "Esta cámara no tiene flash", Toast.LENGTH_SHORT).show()
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (flashEncendido) Icons.Default.FlashOn else Icons.Default.FlashOff,
                contentDescription = "Flash",
                tint = Color.White
            )
        }

        // Botón de disparo: círculo blanco con un anillo
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp)
                .size(76.dp)
                .border(4.dp, Color.White, CircleShape)
                .padding(8.dp)
                .clip(CircleShape)
                .background(Color.White)
                .clickable { sacarFoto(context, controlador) }
        )

        // Botón de invertir cámara
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 32.dp, bottom = 62.dp)
                .size(48.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.4f))
                .clickable {
                    camaraFrontal = !camaraFrontal
                    // Al cambiar de cámara el flash se apaga
                    flashEncendido = false
                    controlador.cameraSelector =
                        if (camaraFrontal) CameraSelector.DEFAULT_FRONT_CAMERA
                        else CameraSelector.DEFAULT_BACK_CAMERA
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Cameraswitch,
                contentDescription = "Invertir cámara",
                tint = Color.White
            )
        }
    }
}

@Composable
fun CapaFiguras(resultado: Resultado, camaraFrontal: Boolean) {
    val pintura = remember {
        android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 52f
            isAntiAlias = true
            setShadowLayer(8f, 0f, 0f, android.graphics.Color.BLACK)
        }
    }

    Canvas(Modifier.fillMaxSize()) {
        // La vista previa llena la pantalla recortando los bordes (FILL_CENTER),
        // así que aplicamos la misma escala y desplazamiento a las figuras.
        val escala = max(size.width / resultado.ancho, size.height / resultado.alto)
        val desplazamientoX = (size.width - resultado.ancho * escala) / 2f
        val desplazamientoY = (size.height - resultado.alto * escala) / 2f

        fun aPantalla(p: Offset): Offset {
            // La cámara frontal se ve en espejo en la vista previa
            val x = if (camaraFrontal) resultado.ancho - p.x else p.x
            return Offset(x * escala + desplazamientoX, p.y * escala + desplazamientoY)
        }

        for (figura in resultado.figuras) {
            val puntos = figura.puntos.map { aPantalla(it) }

            val camino = Path().apply {
                moveTo(puntos[0].x, puntos[0].y)
                for (i in 1 until puntos.size) lineTo(puntos[i].x, puntos[i].y)
                close()
            }
            drawPath(camino, Color.Green, style = Stroke(width = 6f))

            val etiquetaX = puntos.minOf { it.x }
            val etiquetaY = puntos.minOf { it.y } - 16f
            drawContext.canvas.nativeCanvas.drawText(figura.nombre, etiquetaX, etiquetaY, pintura)
        }

        // Marco del código QR en cian
        resultado.qr?.let { qr ->
            val puntos = qr.puntos.map { aPantalla(it) }

            val camino = Path().apply {
                moveTo(puntos[0].x, puntos[0].y)
                for (i in 1 until puntos.size) lineTo(puntos[i].x, puntos[i].y)
                close()
            }
            drawPath(camino, Color.Cyan, style = Stroke(width = 8f))

            val etiquetaX = puntos.minOf { it.x }
            val etiquetaY = puntos.minOf { it.y } - 16f
            drawContext.canvas.nativeCanvas.drawText("QR", etiquetaX, etiquetaY, pintura)
        }
    }
}

fun abrirSiEsEnlace(context: Context, texto: String) {
    if (texto.startsWith("http://") || texto.startsWith("https://")) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(texto)))
        } catch (e: Exception) {
            Toast.makeText(context, "No se pudo abrir el enlace", Toast.LENGTH_SHORT).show()
        }
    }
}

fun sacarFoto(context: Context, controlador: LifecycleCameraController) {
    val valores = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "foto_${System.currentTimeMillis()}")
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Kamara")
    }

    val opciones = ImageCapture.OutputFileOptions.Builder(
        context.contentResolver,
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        valores
    ).build()

    controlador.takePicture(
        opciones,
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(resultado: ImageCapture.OutputFileResults) {
                Toast.makeText(context, "Foto guardada", Toast.LENGTH_SHORT).show()
            }

            override fun onError(error: ImageCaptureException) {
                Toast.makeText(context, "Error: ${error.message}", Toast.LENGTH_LONG).show()
            }
        }
    )
}