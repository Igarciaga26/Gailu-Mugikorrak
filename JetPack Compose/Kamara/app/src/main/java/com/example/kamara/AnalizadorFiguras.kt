package com.example.kamara

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.compose.ui.geometry.Offset
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.QRCodeDetector
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// --- Ajustes de estabilidad (puedes tocarlos) ---
private const val FRAMES_PARA_CONFIRMAR = 4   // fotogramas seguidos antes de mostrar una figura
private const val FRAMES_PARA_OLVIDAR = 8     // fotogramas sin verla antes de quitarla
private const val SUAVIZADO = 0.3f            // 0.1 = muy suave y lento, 1.0 = sin suavizado
private const val AREA_MINIMA = 0.02          // figura mínima: 2 % de la imagen
private const val AREA_MAXIMA = 0.90          // ignora contornos que ocupan casi todo el cuadro
private const val MAX_FIGURAS = 5
private const val PUNTOS_CIRCULO = 24
private const val TIEMPO_QR_MS = 800L         // el QR sigue visible este tiempo tras dejar de leerse

data class Figura(val nombre: String, val puntos: List<Offset>)

data class CodigoQr(val texto: String, val puntos: List<Offset>)

// Figuras detectadas + QR + tamaño de la imagen analizada (para escalarlas a la pantalla)
data class Resultado(
    val figuras: List<Figura>,
    val ancho: Int,
    val alto: Int,
    val qr: CodigoQr? = null
)

// Detección de un solo fotograma
private class Candidata(val nombre: String, val puntos: List<Offset>, val area: Double) {
    val centro = Offset(
        puntos.map { it.x }.average().toFloat(),
        puntos.map { it.y }.average().toFloat()
    )
}

// Figura que seguimos a lo largo de varios fotogramas
private class Seguida(candidata: Candidata) {
    var puntos = candidata.puntos
    var centro = candidata.centro
    var vistos = 1
    var perdidos = 0
    var confirmada = false
    private val votos = mutableMapOf(candidata.nombre to 1)

    // El nombre es el más votado en los últimos fotogramas, así no parpadea
    val nombre: String get() = votos.maxByOrNull { it.value }!!.key

    fun actualizar(c: Candidata) {
        puntos = suavizar(puntos, c.puntos)
        centro = Offset(
            centro.x * (1 - SUAVIZADO) + c.centro.x * SUAVIZADO,
            centro.y * (1 - SUAVIZADO) + c.centro.y * SUAVIZADO
        )
        vistos++
        perdidos = 0
        if (vistos >= FRAMES_PARA_CONFIRMAR) confirmada = true

        votos[c.nombre] = (votos[c.nombre] ?: 0) + 1
        if (votos.values.max() > 30) {
            votos.keys.toList().forEach { votos[it] = votos[it]!! / 2 }
        }
    }
}

// Mezcla los vértices viejos con los nuevos para que no tiemblen
private fun suavizar(viejos: List<Offset>, nuevos: List<Offset>): List<Offset> {
    if (viejos.size != nuevos.size) return nuevos
    val n = viejos.size

    // OpenCV puede empezar a contar por otro vértice: buscamos el desfase que mejor encaja
    var mejorDesfase = 0
    var mejorDistancia = Float.MAX_VALUE
    for (d in 0 until n) {
        var suma = 0f
        for (i in 0 until n) suma += (viejos[i] - nuevos[(i + d) % n]).getDistance()
        if (suma < mejorDistancia) {
            mejorDistancia = suma
            mejorDesfase = d
        }
    }

    return List(n) { i ->
        val a = viejos[i]
        val b = nuevos[(i + mejorDesfase) % n]
        Offset(
            a.x * (1 - SUAVIZADO) + b.x * SUAVIZADO,
            a.y * (1 - SUAVIZADO) + b.y * SUAVIZADO
        )
    }
}

class AnalizadorFiguras(
    private val alTerminar: (Resultado) -> Unit
) : ImageAnalysis.Analyzer {

    // Se usa siempre desde el mismo hilo (el ejecutor de un solo hilo)
    private val seguidas = mutableListOf<Seguida>()

    private val detectorQr = QRCodeDetector()
    private var contador = 0
    private var ultimoQr: CodigoQr? = null
    private var ultimoQrMs = 0L

    override fun analyze(imagen: ImageProxy) {
        val mats = mutableListOf<Mat>()
        try {
            // 1. Plano Y de la imagen = versión en blanco y negro
            val plano = imagen.planes[0]
            val buffer = plano.buffer
            val datos = ByteArray(buffer.remaining())
            buffer.get(datos)

            val bruto = Mat(imagen.height, plano.rowStride, CvType.CV_8UC1).also { mats.add(it) }
            bruto.put(0, 0, datos)
            val gris = Mat(bruto, Rect(0, 0, imagen.width, imagen.height)).also { mats.add(it) }

            // 2. Giramos la imagen para que quede derecha, como se ve en pantalla
            val recto = Mat().also { mats.add(it) }
            when (imagen.imageInfo.rotationDegrees) {
                90 -> Core.rotate(gris, recto, Core.ROTATE_90_CLOCKWISE)
                180 -> Core.rotate(gris, recto, Core.ROTATE_180)
                270 -> Core.rotate(gris, recto, Core.ROTATE_90_COUNTERCLOCKWISE)
                else -> gris.copyTo(recto)
            }

            // 3. Leemos el QR antes de desenfocar la imagen (cada 2 fotogramas para ir más ligero)
            contador++
            if (contador % 2 == 0) leerQr(recto)
            val qr = ultimoQr?.takeIf { System.currentTimeMillis() - ultimoQrMs < TIEMPO_QR_MS }

            // 4. Suavizado + detección de bordes + engrosar bordes para cerrar huecos
            val bordes = Mat().also { mats.add(it) }
            Imgproc.GaussianBlur(recto, recto, Size(7.0, 7.0), 0.0)
            Imgproc.Canny(recto, bordes, 50.0, 150.0)
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
                .also { mats.add(it) }
            Imgproc.dilate(bordes, bordes, kernel)

            // 5. Buscamos contornos exteriores
            val contornos = mutableListOf<MatOfPoint>()
            val jerarquia = Mat().also { mats.add(it) }
            Imgproc.findContours(
                bordes, contornos, jerarquia,
                Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE
            )

            // 6. Reconocemos cada contorno de este fotograma
            val areaImagen = recto.width().toDouble() * recto.height()
            val candidatas = mutableListOf<Candidata>()
            for (contorno in contornos) {
                val area = Imgproc.contourArea(contorno)
                if (area >= areaImagen * AREA_MINIMA && area <= areaImagen * AREA_MAXIMA) {
                    reconocer(contorno, area)?.let { candidatas.add(it) }
                }
                contorno.release()
            }

            // Nos quedamos con las más grandes
            val mejores = candidatas.sortedByDescending { it.area }.take(MAX_FIGURAS)

            // 7. Relacionamos con lo visto en fotogramas anteriores
            val distanciaMaxima = hypot(recto.width().toFloat(), recto.height().toFloat()) * 0.08f
            val usadas = mutableSetOf<Seguida>()
            val nuevas = mutableListOf<Seguida>()

            for (c in mejores) {
                val cercana = seguidas
                    .filter { it !in usadas }
                    .minByOrNull { (it.centro - c.centro).getDistance() }

                if (cercana != null && (cercana.centro - c.centro).getDistance() < distanciaMaxima) {
                    cercana.actualizar(c)
                    usadas.add(cercana)
                } else {
                    nuevas.add(Seguida(c))
                }
            }

            for (s in seguidas) {
                if (s !in usadas) s.perdidos++
            }
            // Las no confirmadas se descartan al primer fallo; las confirmadas aguantan unos fotogramas
            seguidas.removeAll { it.perdidos > (if (it.confirmada) FRAMES_PARA_OLVIDAR else 0) }
            seguidas.addAll(nuevas)

            // 8. Solo mostramos las figuras confirmadas
            val figuras = seguidas
                .filter { it.confirmada }
                .map { Figura(it.nombre, it.puntos) }

            alTerminar(Resultado(figuras, recto.width(), recto.height(), qr))
        } finally {
            mats.forEach { it.release() }
            imagen.close() // imprescindible: sin esto la cámara deja de enviar imágenes
        }
    }

    private fun leerQr(imagen: Mat) {
        val esquinas = Mat()
        try {
            val texto = detectorQr.detectAndDecode(imagen, esquinas)
            if (texto.isNotEmpty() && esquinas.total() >= 4) {
                val v = FloatArray(8)
                esquinas.get(0, 0, v)
                val nuevos = List(4) { Offset(v[it * 2], v[it * 2 + 1]) }

                // Si es el mismo QR, suavizamos el marco para que no tiemble
                val anterior = ultimoQr
                val puntos = if (anterior != null && anterior.texto == texto) {
                    suavizar(anterior.puntos, nuevos)
                } else {
                    nuevos
                }
                ultimoQr = CodigoQr(texto, puntos)
                ultimoQrMs = System.currentTimeMillis()
            }
        } catch (e: Exception) {
            // Si OpenCV falla en un fotograma, lo ignoramos y seguimos con el siguiente
        } finally {
            esquinas.release()
        }
    }

    private fun reconocer(contorno: MatOfPoint, area: Double): Candidata? {
        val contorno2f = MatOfPoint2f(*contorno.toArray())
        val perimetro = Imgproc.arcLength(contorno2f, true)

        // Simplificamos el contorno a pocos vértices
        val aproximado = MatOfPoint2f()
        Imgproc.approxPolyDP(contorno2f, aproximado, 0.03 * perimetro, true)
        val puntos = aproximado.toArray()
        val aproxPuntos = MatOfPoint(*puntos)

        val vertices = puntos.size
        val convexo = Imgproc.isContourConvex(aproxPuntos)

        var esCirculo = false
        val nombre: String? = when {
            vertices == 3 && convexo -> "Triángulo"
            vertices == 4 && convexo -> {
                // Rectángulo girado que mejor encaja, para medir bien aunque esté inclinado
                val rect = Imgproc.minAreaRect(aproximado)
                val lado1 = rect.size.width
                val lado2 = rect.size.height
                val proporcion = min(lado1, lado2) / max(lado1, lado2)
                if (proporcion > 0.9) "Cuadrado" else "Rectángulo"
            }
            vertices == 5 && convexo -> "Pentágono"
            vertices == 6 && convexo -> "Hexágono"
            // Círculo: muchos vértices y contorno muy redondeado (1.0 = círculo perfecto)
            vertices > 6 && (4 * PI * area / (perimetro * perimetro)) > 0.8 -> {
                esCirculo = true
                "Círculo"
            }
            else -> null
        }

        val resultado: Candidata? = if (nombre == null) {
            null
        } else if (esCirculo) {
            // Dibujamos el círculo con un número fijo de puntos para poder suavizarlo
            val centro = Point()
            val radio = FloatArray(1)
            Imgproc.minEnclosingCircle(contorno2f, centro, radio)
            val puntosCirculo = List(PUNTOS_CIRCULO) { i ->
                val angulo = 2 * PI * i / PUNTOS_CIRCULO
                Offset(
                    (centro.x + radio[0] * cos(angulo)).toFloat(),
                    (centro.y + radio[0] * sin(angulo)).toFloat()
                )
            }
            Candidata(nombre, puntosCirculo, area)
        } else {
            Candidata(nombre, puntos.map { Offset(it.x.toFloat(), it.y.toFloat()) }, area)
        }

        contorno2f.release()
        aproximado.release()
        aproxPuntos.release()

        return resultado
    }
}