# Cannon AutoFire

Proyecto Android (Kotlin + C++ NDK) para detectar el faro del minijuego mostrado en las capturas y, tras un ciclo rojo-verde, abrir el menú de Telegram y seleccionar `¡Fuego!`.

## Estado del proyecto
Este es un primer proyecto funcional de base, no una APK ya probada en el dispositivo. La detección visual y las coordenadas se deben calibrar con una captura real de pantalla del Motorola y comprobar en el juego antes de usarlo.

## Requisitos
- Android Studio reciente
- JDK 17
- Android SDK 35
- NDK y CMake 3.22.1
- Android 8.0 o superior

## Cómo abrir
1. Descomprime y abre la carpeta en Android Studio.
2. Espera la sincronización de Gradle.
3. Compila `app`.
4. Instala la APK.

## Primer uso
1. Abre la app y autoriza la burbuja flotante.
2. Activa `Cannon AutoFire` en Ajustes > Accesibilidad.
3. Vuelve a la app y autoriza la captura de pantalla.
4. Abre el chat de Telegram con el juego.
5. Expande la burbuja y pulsa ACTIVAR.
6. El detector exige ver primero rojo y luego verde para evitar disparar si se inicia con el faro ya verde.
7. DETENER deshabilita nuevas acciones inmediatamente.

## Selector de faro (nuevo)
1. Expande la burbuja y pulsa SELECTOR (detiene la automatización mientras ajustas).
2. Arrastra el recuadro cian sobre la luz del faro; cambia el tamaño con las asas de las esquinas. El interior queda limpio para que la captura lo vea tal cual.
3. El texto en pantalla muestra en vivo % verde, % rojo y la clasificación. Comprueba que marque ROJO en rojo y VERDE en verde, y que no marque VERDE con fondo de mar o isla.
4. Pulsa GUARDAR. Se conserva entre sesiones; RESTABLECER vuelve al valor por defecto.
5. Elige un recuadro pequeño, solo sobre la luz, para evitar falsos verdes del fondo (isla, mar).

Protección extra: se exigen 2 fotogramas verdes seguidos (`GREEN_FRAMES`), y el limitador de fotogramas baja a 80 ms.

## Calibración necesaria
El área del faro está inicialmente configurada en `AutoFireService.kt` con coordenadas normalizadas aproximadas:
- X: 77.5%-83.5% del ancho
- Y: 63.5%-69.5% del alto

El icono de la mano usa una posición aproximada (94.3%, 84.3%). Si la interfaz cambia, ajustar esos valores. La opción `¡Fuego!` se busca por texto mediante accesibilidad, no por coordenadas.

## Limitaciones conocidas
- Android puede mostrar avisos y restricciones de captura/servicios según fabricante y versión.
- MediaProjection requiere autorización explícita del usuario y puede requerir renovarla al reiniciar el servicio.
- Telegram puede exponer el texto del menú de forma distinta según versión/idioma. Si el nodo `¡Fuego!` no es accesible, la secuencia se detiene sin seleccionar otra opción.
- La burbuja se oculta al retirar el permiso de superposición.
- La detección de color necesita validación real: brillo, animaciones y cambios de escala pueden requerir ajustar el umbral/ROI.
- El servicio no intenta ocultarse ni eludir protecciones de Android.

## Seguridad de ejecución
La automatización no se rearma con el faro verde continuo. Solo permite un disparo después de observar rojo y luego verde. El botón DETENER y la acción de notificación detienen la automatización.


## Optimizaciones aplicadas
1. Limitador de fotogramas a 80 ms.
2. Análisis nativo directo: C++ lee solo el recuadro del faro sobre el buffer de la captura (`NativeDetector.analyze`), sin copiar píxeles a Kotlin.
3. Sin espera fija: tras tocar la mano, «¡Fuego!» se pulsa en cuanto el evento de accesibilidad indica que el menú apareció (tiempo máximo 1,5 s).
4. Confirmación de 2 fotogramas verdes seguidos (`GREEN_FRAMES`).

## Compilar en GitHub (sin instalar nada)
1. Crea un repositorio en github.com (botón **New**, por ejemplo `CannonAutoFire`, privado o público).
2. Sube el contenido de la carpeta `CannonAutoFire` (no el zip) a la raíz del repositorio. Debe verse `app/`, `.github/`, `build.gradle.kts`... en la raíz. Desde la web: **Add file → Upload files**, arrastra todo (incluida la carpeta `.github`; si tu navegador oculta carpetas con punto, usa git desde un PC o Termux).
3. Abre la pestaña **Actions**. El flujo **Build APK** se ejecuta solo al subir; también puedes lanzarlo con **Run workflow**.
4. Al terminar (unos 5-8 min), entra en la ejecución y descarga el artefacto **CannonAutoFire-debug-apk** (zip con `app-debug.apk`).
5. Instálalo en el móvil (permite «instalar apps desconocidas»).
6. Abre la app: autoriza la burbuja, activa el servicio de accesibilidad, autoriza la captura. En Telegram, abre SELECTOR y coloca el recuadro sobre el faro.

Si Actions falla, abre el registro del paso en rojo y pégalo para corregirlo.

## Correcciones de la revisión de arquitectura (v4)
- Android 14+: se registra `MediaProjection.Callback` antes de crear el VirtualDisplay (si no, la app se cierra) y se detiene la automatización si el sistema corta la captura.
- El servicio ya no se reinicia solo sin permiso de captura (`START_NOT_STICKY`).
- El toque a la mano usa el tamaño real de pantalla, igual que la captura y el selector.
- Se solicita el permiso de notificaciones (Android 13+).
- Solo se compilan ABIs arm64-v8a y armeabi-v7a (build más rápido).

## Selectores de mano y plan B (v5)
- **MANO**: elige con una mira dónde está el icono de la mano (valor por defecto 94,3 % / 84,3 %).
- **FUEGO** + **PLAN B**: si «¡Fuego!» no aparece en accesibilidad (menú dibujado sin nodos), tras 1,5 s se toca el punto guardado. Para fijarlo: abre el menú del cañón a mano, pulsa FUEGO en la burbuja, marca la opción «¡Fuego!» y GUARDAR. PLAN B viene desactivado; es menos seguro porque no verifica qué se pulsa, así que comprueba el punto antes de activarlo.
- El estado indica el método usado: «accesibilidad» o «coordenadas».
