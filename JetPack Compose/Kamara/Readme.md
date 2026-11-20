# Kamara – Kamera ariketa (Kotlin + Jetpack Compose)

Android aplikazio honek kameraren aurrebista erakusten du, argazkiak ateratzen ditu, denbora errealean figura geometrikoak eta QR kodeak antzematen ditu, eta egindako argazkien eta irakurritako QR kodeen historiala **Room** datu-base batean gordetzen du.

## Pantaila-argazkia

![Kamara aplikazioaren pantaila-argazkia](imagenes/pantaila-argazkia.png)

## Funtzionalitateak

- Kameraren baimena eskatzen du aplikazioa abiaraztean.
- Kameraren aurrebista pantaila osoan (CameraX).
- **Argazki botoia** (zirkulu zuria): argazkia galeriako `Pictures/Kamara` karpetan gordetzen du.
- **Kamera aldatzeko botoia**: aurreko eta atzeko kameraren artean aldatzen du.
- **Flash botoia**: lanterna piztu eta itzaltzen du. Kamerak flasharik ez badu, mezu bat erakusten da. Kamera aldatzean flasha itzali egiten da.
- **Figura geometrikoen detekzioa**: hirukia, karratua, laukizuzena, pentagonoa, hexagonoa eta zirkulua antzematen ditu, eta izena erakusten du kontornu berdearekin.
- **QR kodeen irakurketa**: QR kodea kontornu urdin argiarekin markatzen da eta testua goialdean erakusten da. Testua esteka bat bada (`http://` edo `https://`), ukitzean nabigatzailean irekitzen da.
- **Historiala (Room)**: argazki bakoitza eta irakurritako QR kode berri bakoitza datu-basean gordetzen da. Goiko eskuineko botoiak historiala erakusten du, eta "Borrar todo" botoiak dena ezabatzen du.

## Teknologiak

- Kotlin eta Jetpack Compose
- CameraX (`camera-camera2`, `camera-lifecycle`, `camera-view`)
- OpenCV 4.9.0 (irudien analisia eta QR detektorea)
- Room 2.8.4 (datu-basea) eta KSP (Room-en konpilatzailea)
- Material Icons Extended (botoien ikonoak)

## Proiektuaren egitura

| Fitxategia | Zeregina |
|---|---|
| `MainActivity.kt` | Interfazea (Compose), baimenak, kameraren kontrolatzailea, botoiak, historialaren leihoa eta antzemandako figuren marrazketa. |
| `AnalizadorFiguras.kt` | Kameraren irudiak aztertzen ditu OpenCV-rekin: figurak eta QR kodeak. |
| `AppDatabase.kt` | Room datu-basea: `Registro` taula (Entity), `RegistroDao` eta `AppDatabase` klasea. |

## Nola funtzionatzen du

### 1. Baimenak eta kamera
`PantallaCamara()` funtzioak kameraren baimena dagoen egiaztatzen du, eta ez badago, eskatu egiten du abiaraztean. Baimena emanda, `VistaCamara()` funtzioak `LifecycleCameraController` bat sortzen du. Kontrolatzaile honek kamera irekitzea eta ixtea kudeatzen du pantailaren bizi-zikloaren arabera. Aurrebista `PreviewView` batean erakusten da, `AndroidView` bidez Compose barruan txertatuta.

### 2. Irudien analisia
Kamerak hartzen duen fotograma bakoitza `AnalizadorFiguras` klaseak aztertzen du, hari (thread) bereizi batean, interfazea blokeatu ez dadin:

1. Irudiaren Y planoa hartzen da (zuri-beltzeko bertsioa) eta zuzen jartzeko biratzen da.
2. QR kodea irakurtzen da `QRCodeDetector` erabiliz (bi fotogramatik behin, arinagoa izan dadin).
3. Irudia lausotu egiten da (`GaussianBlur`), ertzak antzematen dira (`Canny`) eta ertzak lodiagoak egiten dira (`dilate`).
4. Kanpoko kontornuak bilatzen dira (`findContours`).
5. Kontornu bakoitza sinplifikatzen da (`approxPolyDP`) eta erpin kopuruaren arabera figura identifikatzen da:
   - 3 erpin → hirukia
   - 4 erpin → karratua edo laukizuzena (aldeen proportzioaren arabera)
   - 5 erpin → pentagonoa
   - 6 erpin → hexagonoa
   - erpin asko eta oso biribila → zirkulua

### 3. Egonkortasuna
Detekzioa fotograma batetik bestera aldatzen zen eta dir-dir egiten zuen, beraz egonkortasun sistema bat gehitu da:

- Figura bat erakusteko, **4 fotogramatan jarraian** antzeman behar da.
- Behin baieztatuta, **8 fotograma arte** irauten du ikusi gabe.
- Erpinak **leundu** egiten dira aurreko fotogramekin nahastuz, ez dezaten dardar egin.
- Izena **bozketa** bidez aukeratzen da (gehien errepikatutakoa), ez dadin karratuaren eta laukizuzenaren artean aldatu.
- Eremu txikiegiak (irudiaren %2 baino gutxiago) eta handiegiak (%90 baino gehiago) baztertzen dira.

### 4. Marrazketa
`CapaFiguras()` funtzioak `Canvas` batean marrazten ditu kontornuak eta izenak aurrebistaren gainean. Koordenatuak pantailaren tamainara egokitzen dira, eta aurreko kamerarekin ispilu efektua kontuan hartzen da.

### 5. Room datu-basea
Datu-basea `kamara.db` fitxategian gordetzen da, eta hiru zatik osatzen dute:

- **`Registro` (Entity)**: `registros` taula definitzen du. Zutabeak: `id` (automatikoa), `tipo` (`FOTO` edo `QR`), `contenido` (argazkiaren bidea edo QR kodearen testua) eta `fecha` (milisegundotan).
- **`RegistroDao`**: eragiketak definitzen ditu. `insertar` eta `borrarTodos` `suspend` funtzioak dira, beraz Room-ek hari nagusitik kanpo exekutatzen ditu. `obtenerTodos` funtzioak `Flow` bat itzultzen du, taula aldatzen den bakoitzean automatikoki abisatzen duena.
- **`AppDatabase`**: Entity eta DAO-a lotzen ditu, eta datu-base bakarra (singleton) sortzen du aplikazio osorako.

`MainActivity.kt` fitxategian, `collectAsState` erabiltzen da `Flow` hori Compose-ren egoera bihurtzeko, eta horrela historiala automatikoki eguneratzen da. Argazkiak `sacarFoto` funtzioaren callback-etik gordetzen dira, eta QR kodeak `LaunchedEffect` batekin. Erregistro bikoiztuak saihesteko, QR kode bera ez da berriro gordetzen 10 segundo igaro arte (edo beste QR kode bat irakurri arte).

## Probatzea

- **Gailu errealean** funtzionatzen du hobekien (flasha eta atzeko kamera barne).
- **Emulatzailean**, atzeko kamera `VirtualScene` modura konfiguratu behar da. QR kodeak probatzeko, `Webcam0` erabili daiteke PCko webkamerarekin. Emulatzaileak ez du flash fisikorik.
- **Datu-basea ikusteko**: aplikazioa martxan dagoela, Android Studio-n *View → Tool Windows → App Inspection → Database Inspector* ireki eta `kamara.db` aukeratu.

## Kaptura
![Kamara aplikazioaren pantaila-argazkia](imagenes/Pantaila-argazkia%202026-11-20%20110736.png)