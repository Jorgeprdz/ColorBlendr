# Samsung One UI + Shizuku Palette Bridge

## Estado y recuperación después de SIGKILL

Checkout conservado: `/workspace/ColorBlendr`. Remote original:
`https://github.com/Mahmud0808/ColorBlendr.git`. Base:
`5b078e92abfa482674d82a23ce2a302d17cee756`. Rama:
`fix/samsung-oneui-shizuku-palette-bridge`.

Al retomar existían ocho archivos tracked modificados (+119/-33), siete archivos
del bridge, tres suites de tests, el proyecto JVM independiente y el plan previo.
Esto es el estado observado, más avanzado que la estimación anterior de seis
archivos. Se conservaron esos cambios; no se clonó, reseteó ni descartó el checkout.
JDK/SDK y resultados de tests anteriores estaban presentes. No se ejecutó ni
validó la adaptación local de aidl. `/proc` no mostró procesos huérfanos de este
build; `./gradlew --stop` indicó que no había daemons. No se usó QEMU ni se lanzó
ningún build Android local en esta reanudación.

## Problema, evidencia y causa raíz

Objetivo: Galaxy S25, Android 16, One UI 8.5, Shizuku sin root. Evidencia aportada
por el usuario: con seed `#F3EDC8` y EXPRESSIVE, HeliBoard cambió a amarillo cerca
de un segundo y volvió a los colores anteriores. Fue HeliBoard, no Samsung
Keyboard. No se añadió integración particular con ningún teclado.

El usuario confirmó que `android:SemWT_com.android.systemui` sobrescribe recursos
reales: `qs_tile_round_background_on = accent1_300` y
`volume_seekbar_progress_color = accent2_300`. También confirmó que escribir
los 65 colores en Settings.System y hacer state 0→1 regenera SemWT.

Causa arquitectónica demostrable en el código anterior: la ruta sin root sólo
enviaba seed/style mediante `theme_customization_overlay_packages`; no enviaba
la matriz generada ni sus ajustes. Además, alternaba G Monet (enable/disable)
en cada aplicación, por lo que una segunda aplicación podía deshabilitarlo.
El listener de wallpaper podía forzar otra aplicación aun sin nuevo wallpaper,
y actualizar el seed desde un extractor con fallback a colores del framework.

La cadena exacta que produjo el flash en ese S25 sigue siendo provisional:
no hay captura temporal de logcat del incidente ni acceso al dispositivo en esta
sesión. El flash demuestra una transición observada, no identifica por sí solo
quién reaplicó el tema. La solución elimina los mecanismos anteriores en la ruta
Samsung soportada y deja trazas temporales T0–T14 para comprobarlo.

## Arquitectura y palette65

`PreviewController.buildPreviewColors()` lee estilo y ajustes internos y llama
`generateModifiedColors()` para modo claro y oscuro. El bridge usa ese mismo
generador, selecciona la matriz del modo activo y la valida con `SamsungPalette`.
No obtiene los 65 colores de recursos Monet del framework. Las consultas de
recursos SystemUI son diagnósticas posteriores, nunca la entrada del generador.

Orden: accent1, accent2, accent3, neutral1, neutral2. Cada fila contiene exactamente
13 tonos: 0,10,50,100,200,300,400,500,600,700,800,900,1000. Se descarta la sexta
fila `error`. Índice = familia*13 + posición del tono. Ejemplos: A1_300=5,
A2_300=18, N1_500=46. La lista se serializa como `[Int, Int, ...]`, con 65 ARGB
de Kotlin con signo, sin convertirlos a unsigned. Se validan nombres y dimensiones.
`isgray=1` sólo si todos los colores finales tienen R=G=B, incluidos overrides.

Seed manual, wallpaper seed almacenado, estilos integrados, secondary/tertiary y
overrides por tono siguen pasando por la configuración interna existente.
Una matriz Samsung única no representa simultáneamente todos los roles Material
claros/oscuros ni todas las capacidades exclusivas de root.

## Saturación y UI

Accent saturation, Background saturation y Background lightness se habilitan
para root o para Samsung + Shizuku + soporte comprobado. No se habilitan para
Shizuku de otros fabricantes. Se mantiene 0..200 y la presentación 100=1.00x,
150=1.50x, 200=2.00x. Se conserva el algoritmo original de ColorBlendr: el valor
mostrado no implica multiplicar literalmente cada componente RGB.

Los tres getters alimentan `generateModifiedColors()` tanto para preview como
para aplicación. El algoritmo original limita saturación en estilos monocromáticos
y neutrales Rainbow; los overrides explícitos por tono prevalecen sobre tuning.
Los tests de integración comparan 100 y 150 para los tres controles, ambos modos
y staging→commit. Su ejecución Android/Robolectric queda pendiente de CI.

## Secuencia Shizuku y recuperación

1. Comprobar fabricante Samsung (case-insensitive), método Shizuku, disponibilidad,
   permiso y `wallpapertheme_state` reconocido como 0/1. Fallo de detección no
   habilita los sliders.
2. Conexión existente a Shizuku; comandos verificados por exit code, stdout y
   stderr mediante AIDL append-only. No se busca `su` en el servicio Shizuku.
3. Guardar snapshot durable por usuario Android, fuera de preferencias staged.
4. Escribir `wallpapertheme_color`, `wallpapertheme_color_isgray`, state=0,
   pausa suspendida de 100 ms, state=1 con reintento acotado.
5. Observar registro asíncrono SemWT con hasta ocho observaciones; exigir Android
   y SystemUI habilitados y verificar settings. Habilitar overlays presentes que
   estén disabled. Nunca deshabilitar G Monet, SystemUI ni ningún otro SemWT.
6. Guardar backup como confirmado y refrescar la UI. Un fallo del bridge no
   continúa por la segunda ruta de JSON seguro.

`wallpapertheme_color_for_g` nunca se escribe. Tampoco se escribe JSON seguro
en la ruta Samsung soportada. No se toca `ThemeOverlayPackage` para otros equipos.
G Monet se conserva habilitado; no se alterna ni se cambia su matriz independiente.
Su coexistencia efectiva con SemWT y aplicaciones concretas debe validarse en S25.

Ante fallo/cancelación se intenta restaurar paleta/gray previos, terminar state=1
y reparar overlays incluso si otro paso de recuperación falla. Los fallos se
propagan; un binder muerto o permisos revocados hacen imposible garantizar la
escritura física. El backup pendiente permanece para recuperación posterior.
Reset restaura la paleta original y termina en 1, incluso si el snapshot inicial
tenía 0; no restaura deliberadamente un estado disabled. Si detecta una selección
externa posterior confirmada, la conserva y abandona la propiedad del backup.

## Anti-loop y concurrencia

`SamsungWallpaperGuard` usa AtomicReference y comparación de IDs del wallpaper
home/lock, más colores del wallpaper vivo. El baseline previo a aplicar sólo se
acepta si una extracción real coincide con los colores almacenados y el fingerprint
es estable antes/después. Un callback idéntico omite extracción y cambios de seed,
pero conserva cambios Tasker pendientes para pantalla apagada. No hay ventana
temporal; si no puede obtenerse fingerprint, no se suprime el evento.
Las llamadas Binder del fingerprint se ejecutan en IO.

La extracción estricta Samsung no usa fallback Monet: lee wallpaper vivo o archivo
de wallpaper con decodificación reducida. Sin acceso conserva el seed anterior.
También se usa al refrescar la lista de wallpaper durante recreación de Activity,
otro posible camino de sustitución del seed. Root/AOSP mantienen su extractor.
Un fallo de consulta de soporte durante apply es error, no permiso para cambiar a
la ruta JSON antigua. Los tests del núcleo comprueban baseline cambiado antes,
durante y después de la extracción; la integración Android queda para CI.

`OverlayManager` serializa apply/remove con un Mutex y ejecuta en Dispatchers.IO;
`PreviewController` serializa commits/apply y los callbacks esperan su finalización.
La transacción tiene además su mutex. La configuración sólo reaplica cuando cambia
night mode; los refresh internos reconstruyen UI sin escribir el tema seguro.
La serialización cubre las escrituras del bridge dentro de ColorBlendr; no controla
procesos Samsung ni aplicaciones externas. No se promete atomicidad de preferencias
frente a cualquier escritor externo.

## Tests y validación

Primera repetición tras SIGKILL: **31/31 JVM PASS**. Se reprodujeron tres regresiones
antes de corregirlas: fingerprint unavailable, reset desde 0 y fallo temprano
desde 0. La suite ampliada cubre además atomicidad del guard y recuperación de
SystemUI/G aunque otro overlay falle. Resultado final del núcleo: **37/37 PASS**,
sin fallos ni skips (Gradle BUILD SUCCESSFUL, 18 s). XML conservados en
`bridge-tests/build/test-results/test/`.

Comando local, sin configurar el proyecto Android:

```sh
./gradlew -p bridge-tests test --no-daemon --max-workers=1 \
  -Dorg.gradle.jvmargs=-Xmx384m -Pkotlin.compiler.execution.strategy=in-process
```

Cobertura JVM: tamaño/orden, signed ARGB, Samsung/no Samsung, requisito Shizuku,
gray, exclusión for_g/secure JSON, state recovery, overlay recovery, cancelación,
concurrencia, callbacks duplicados, backup persistente, reset externo e idempotencia
del resultado. Reaplicar explícitamente vuelve a regenerar SemWT; idempotencia
no significa ausencia de escrituras.

`SamsungGeneratedPaletteTest` ejercita el generador real con Robolectric: tuning,
preview/aplicación, ambos modos, commit de staging, seed manual/wallpaper, todos
los estilos, overrides y monochrome. No se cuentan como PASS sin ejecutarlos.

## CI y APK

Workflow: `.github/workflows/build-samsung-shizuku-bridge.yml`, ubuntu-latest
x86_64, JDK 17/21, Gradle cache, SDK nativo del runner, JVM core tests,
`:app:testDebugUnitTest`, `./gradlew assembleDebug` y upload-artifact.
Artifact: `ColorBlendr-Samsung-OneUI-Shizuku-Debug`. Ruta esperada del build:
`app/build/outputs/apk/debug/ColorBlendr v3.0.1.apk`.

Estado comprobado al preparar la entrega: la cuenta conectada `Jorgeprdz` tiene
`push=false` en `Mahmud0808/ColorBlendr`; `Jorgeprdz/ColorBlendr` devuelve 404.
No hay credencial Git CLI ni herramienta conectada para crear forks.
Se solicitó un fork escribible mientras continuaba el trabajo local.
Commit de implementación local: `50a566eea7185f39626fd8d3f56a1b25af0588bd`.
El intento de push sin interacción terminó con exit 128:
`could not read Username for 'https://github.com': terminal prompts disabled`.
**No hay todavía run CI, assembleDebug PASS ni APK construida para este cambio.**
La ruta anterior es esperada, no un archivo entregado. No se sustituirá la APK
por una de upstream ni se afirmará compilación sin evidencia.

## Validación manual en Galaxy S25

1. Instalar la APK de ese commit cuando CI finalice. Si la firma no coincide con
   la instalada, respaldar preferencias antes de cualquier desinstalación.
2. Iniciar Shizuku, conceder acceso a ColorBlendr, seleccionar SHIZUKU y activar
   theming. Verificar que los tres sliders están habilitados.
3. Elegir seed manual `#F3EDC8`, EXPRESSIVE, ajustes 100. Aplicar y guardar logcat
   con tag `SamsungPaletteBridge`. Observar QS y HeliBoard al instante, a 2 s y 30 s.
4. Cambiar Accent a 150 y aplicar. Comparar los 39 colores accent del setting y
   el preview. Repetir Background saturation y lightness, comparando los 26 neutral.
5. Leer (sólo diagnóstico) settings color/isgray/state y lista de overlays mediante
   shell Shizuku/ADB: 65 enteros, state=1, SystemUI/G no disabled. Guardar antes y
   después `wallpapertheme_color_for_g`: el bridge no debe haberlo escrito.
6. Abrir/cerrar panel QS, cambiar configuración y esperar: no debe reaparecer el
   viejo seed. Cambiar wallpaper real en modo wallpaper: sí debe actualizarse.
7. Probar claro/oscuro, monocromático, estilo personalizado, Apply repetido y reset.
8. Detener Shizuku durante una aplicación en un ensayo controlado: confirmar error,
   reiniciar Shizuku y restablecer; comprobar state=1 y overlays. No asumir que un
   proceso sin permisos puede recuperar el dispositivo.

## Changelog humano

- Conservado el trabajo previo y verificada de nuevo su suite JVM.
- Samsung recibe la paleta generada y ajustada por ColorBlendr, no sólo seed/style.
- Los tres sliders se ofrecen en Shizuku Samsung soportado.
- Se elimina el toggle destructivo de G Monet y se filtran callbacks idénticos.
- Apply/reset serializados, backups durables y recuperación independiente.
- Añadidos tests de regresión, CI x86_64 y este informe con límites explícitos.
