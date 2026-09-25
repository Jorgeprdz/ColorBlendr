# Samsung native palette engine — 2026-09-25

## Resultado y evidencia

Esta revisión implementa transporte real, no otra variante de `settings put`.
El dispositivo probó 0aef998: MAIN persistió diez segundos (`overwritten=false`),
pero QS, volumen y Material You conservaron recursos antiguos. Esto descarta que
una restauración tardía del setting explique esa ejecución. El código Samsung
publicado mantiene SS/GG en ThemePalette; `applyWallpaperColor(SS, GG, gray)`
actualiza ese estado, genera FRRO y después refleja las matrices en Settings.
Escribir sólo el espejo persistente no hizo ese trabajo en el S25.

No hay acceso físico al S25 desde este entorno. Los capability checks reales
quedan dentro de Apply y su resultado se registra. No se afirma que esta APK ya
haya cambiado recursos en BP4A.251205.006.S931BXXSCCZH1. Las fuentes Samsung
publicadas orientan la implementación; no se presentan como decompilación de ese
build exacto.

## Flujo implementado

1. MAIN procede del generador interno existente, con seed, estilo, sliders y
   overrides. No cambia el generador ni las rutas root/no Samsung.
2. El UserService Shizuku usa su UID 2000. `clearCallingIdentity` elimina la
   identidad entrante de la app, no adquiere UID 1000. Resuelve el Proxy real de
   `IOverlayManager` del firmware; no contiene números Binder hardcodeados.
3. Native probe comprueba métodos, firma plataforma y lectura `getLastPalette`.
   Samsung publicado comprueba firma y puede retornar sin hacer nada: por ello
   un retorno void nunca acredita éxito. Theme Park se rechaza antes de mutar,
   porque esta API borra sus objetos y no existe aquí un backup completo de ellos.
4. GG se genera independientemente mediante el camino OEM
   `ColorPalette(ColorScheme(seed, false, style)).getTable()`. Se aplican los
   mismos ColorModifiers, seeds secundarios/terciarios y overrides explícitos.
   No se copia MAIN ni se usa la paleta resuelta del framework como fuente.
   Actualmente native admite ColorSpec 0 y estilos presentes en el enum OEM;
   otras configuraciones pasan a fabricated sin sustituir estilos en silencio.
5. Native captura el par canónico, estado, overlays y recursos originales;
   escribe un journal duradero y llama `applyWallpaperColor`. No hay toggle
   state 0→1 ni writes directos a los settings para aplicar.
6. Si native no está disponible/permitido, o falla y su rollback queda
   verificado, se prueba fabricated. El probe registra/habilita/deshabilita/
   elimina overlays propios de prueba para ambos targets, dentro de una
   transacción y con limpieza final. No se ejecuta al abrir la app.
7. Fabricated usa únicamente IDs `com.android.shell:colorblendr_samsung_*`.
   Cubre 65 tonos framework más roles generados y el mapping SystemUI del
   firmware completo: MetaDataManager + TemplateManager + ThemePalette,
   variantes light/night y opacidades. No es una lista de dos colores QS.
   Reemplaza sus propios IDs; no elimina SemWT.
8. Verifica a 0/250/1000/2000/5000/10000 ms: par/estado native, overlays,
   QS, volumen, varios recursos adicionales SystemUI y tonos 300/500/900
   de las cinco familias en contexto third-party ColorBlendr. Native espera
   GG en ese contexto; SystemUI espera el mapping de MAIN. Fabricated espera
   MAIN en framework y conserva el estado Samsung subyacente. Una regresión
   tras coincidir también falla, aunque el último sample vuelva a coincidir.
9. Rollback native vuelve a invocar la API con el par anterior y restaura
   enablement; rollback fabricated repone el payload anterior o elimina sólo
   sus objetos. Reset usa el snapshot original. Un fallo de recuperación deja
   pending y bloquea otro backend. Las transacciones están serializadas.

## Límites comprobables

- El código OMS Samsung publicado rechaza fabricated desde shell con
  `Non-root shell cannot fabricate overlays`. El backend no evade esa política:
  la prueba runtime decidirá DENIED/SUPPORTED/UNAVAILABLE. No se garantiza que
  la alternativa sea utilizable en este firmware.
- La firma plataforma de Shell aparece en AOSP; la firma real del teléfono se
  comprueba en runtime. No se declara native SUPPORTED para el S25 sin ejecutarlo.
- No hay persistencia artificial con daemon o watcher. Samsung conserva el par
  nativo; la verificación acredita la ventana acotada, no prueba un reboot.
- Reset de un motor originalmente desactivado admite registros SemWT nuevos
  desactivados y espejos vacíos producidos por la API OEM, siempre que estado y
  recursos efectivos originales estén restaurados. No destruye registros OEM.
- Samsung aplica su paleta de forma global según el comportamiento de su API.
  No se ha validado un escenario multiusuario/Secure Folder en dispositivo.

## Archivos y tests

Nuevos `core/SamsungEngineCoordinator.kt`, `core/SamsungResourceMapping.kt` y
`engine/{SamsungFirmware,SamsungFirmwareMapping,SamsungFirmwareOverlays,
SamsungEnginePreferences,SamsungGooglePalette,ShizukuPaletteEngine}.kt`.
Integración en SamsungShizukuPaletteBridge, AIDL y ambos ShizukuConnection.
Se conserva la transacción diagnóstica y todos sus tests como regresión.

Tests nuevos: 14 del coordinador (allowed/denied, fallback, captura fallida,
no-op, stale, rollback fallido/pending, reset, reemplazo, concurrencia,
regresión temporal, GG ausente, validación); 3 de mapping; 5 Android de
GG independiente, tuning, overrides, seeds secundarios y spec no compatible.
La evidencia ejecutada está en el artifact Samsung-Bridge-Test-Results del
workflow Samsung One UI Shizuku Bridge. CI ejecuta todos los tests y assembleDebug.

Instalación: `scripts/reinstall-samsung-debug.sh "/ruta/ColorBlendr v3.0.1.apk"`
hace uninstall, install -g y pm path, sin backup. No intenta eludir una firma
incompatible con install -r. Este entorno no ejecutó esa instalación física.

## Fuentes y auditoría previa

La sección siguiente conserva el informe de 0aef998 como historial; sus
limitaciones de implementación settings-only corresponden a aquella revisión,
no al nuevo flujo descrito arriba.

# Samsung bridge: auditoría y verificación acotada (2026-09-25)

## Estado de esta revisión

**No es un arreglo definitivo del motor Samsung.** Corrige falsos éxitos y añade
instrumentación/recuperación comprobada. La aplicación efectiva de una paleta
ColorBlendr a MAIN, FOR_G y roles dinámicos en SM-S931B / Android 16 / One UI 8.5
BP4A.251205.006.S931BXXSCCZH1 sigue sin demostrarse. Un CI verde acredita tests y
compilación, no comportamiento en ese firmware. La APK debe tratarse como
instrumentada, no como solución de los ocho objetivos originales.

La evidencia del usuario se acepta: watchers externos eliminados, paleta antigua
estable 20 s, Apply cambia preview pero QS resuelve #ff7caee8 y volumen #ffc09ed6.
No se atribuye el problema a Termux ni se lanza ningún watcher.

## Acceso y fuentes

Checkout: Jorgeprdz/ColorBlendr, rama fix/samsung-oneui-shizuku-palette-bridge,
base 57c80cc. Se leyeron bridge, gateway, transaction, backup, wallpaper guard,
OverlayManager, PreviewController, BroadcastListener, tests y commits recientes.

Este entorno contiene /workspace/shizuku/rish pero no /system/bin/sh ni
/system/bin/app_process. Ejecutarlo devuelve exit 127. No hay conexión ADB
configurada disponible. No se han ejecutado pruebas, settings writes, reinicios
ni comandos de overlays en el teléfono durante esta revisión.

Fuentes de implementación consultadas, no equivalentes a decompilar el firmware
exacto del usuario:

- [SemWallpaperThemeManagerWrapper](https://github.com/488315/samsung_framework/blob/30cd25b68f67be792ab78fb46fb9f63af0964522/services/sources/com/android/server/om/wallpapertheme/SemWallpaperThemeManagerWrapper.java)
- [SemWallpaperThemeManager](https://github.com/488315/samsung_framework/blob/30cd25b68f67be792ab78fb46fb9f63af0964522/services/sources/com/android/server/om/wallpapertheme/SemWallpaperThemeManager.java)
- [OverlayGenerator](https://github.com/488315/samsung_framework/blob/30cd25b68f67be792ab78fb46fb9f63af0964522/services/sources/com/android/server/om/wallpapertheme/OverlayGenerator.java)
- [ThemePalette](https://github.com/488315/samsung_framework/blob/30cd25b68f67be792ab78fb46fb9f63af0964522/framework/sources/android/content/om/wallpapertheme/ThemePalette.java)
- [SemWallpaperThemeOverlayPolicy](https://github.com/488315/samsung_framework/blob/30cd25b68f67be792ab78fb46fb9f63af0964522/services/sources/com/android/server/om/wallpapertheme/SemWallpaperThemeOverlayPolicy.java)
- [ThemeOverlayController](https://github.com/488315/samsung_framework/blob/30cd25b68f67be792ab78fb46fb9f63af0964522/SystemUI-Jadx/sources/com/android/systemui/theme/ThemeOverlayController.java)
- [IOverlayManager One UI 8.0](https://github.com/488315/android_samsung_frameworks_base/blob/d7c13fe69a1ad46f8dff0254f1abd32596fef5cd/src/android/content/om/IOverlayManager.java)
- [ColorPaletteCreator One UI 8.0](https://github.com/488315/android_samsung_frameworks_base/blob/d7c13fe69a1ad46f8dff0254f1abd32596fef5cd/src/com/samsung/android/wallpaper/colortheme/ColorPaletteCreator.java)

## Diagnóstico y seis hipótesis

1. **Orden state=0 y escritura:** no demostrado. La implementación publicada
   genera FRRO desde ThemePalette en memoria; escribir Settings no actualiza esa
   estructura. Mover un delay no resuelve por sí mismo esta diferencia. Se conserva
   el orden anterior, explícitamente sin presentarlo como validado.
2. **state=1 como petición de regeneración:** no acreditado. En el código Samsung
   examinado, saveWallpaperThemeState es una salida del commit del motor, no una
   API que acepte la matriz arbitraria escrita por otra app.
3. **API real:** existe IOverlayManager.applyWallpaperColor(List MAIN, List GG,
   boolean isGray), también en las interfaces publicadas de One UI 8.0. El wrapper
   comprueba checkSignatures(1000, callingUid), actualiza ThemePalette, registra y
   habilita FRRO, hace commit y guarda settings. El rechazo puede retornar sin
   lanzar excepción. Su autorización bajo Shizuku y comportamiento en el build
   objetivo no están verificados. No se han hardcodeado transacciones Binder ni
   añadido una llamada mutante sin un par de paletas justificado.
4. **Otra fuente canónica:** demostrada en el código publicado. ThemePalette
   mantiene SS/GG en memoria y writeLastPalette guarda ambas matrices y gray en
   /data/overlays/wallpapertheme/last_palette.txt. El wrapper recupera esa paleta.
   saveWallpaperThemeColor escribe ambos settings desde memoria y puede reintentarlo
   un segundo después ante excepción. Es compatible con el síntoma, pero no prueba
   qué escritor restauró MAIN en el S25. Hace falta evidencia del build real para
   atribuir causalidad exacta.
5. **SystemUI dynamic:** el controlador crea un overlay propiedad de
   com.android.systemui dirigido a android con roles system_*_light/dark. Esto no
   prueba que escriba los recursos QS citados. Tiene condiciones relativas a
   wallpapertheme_state; alternar state puede abrir la ruta Monet. Su existencia
   aislada no permite culparlo de la restauración. Ahora se registra su estado.
6. **MAIN/FOR_G:** ambas son matrices 5x13. OverlayGenerator usa SS para
   SemWT_MonetPalette y GG para SemWT_G_MonetPalette. La política conserva GG para
   paquetes ajenos a los metadatos Samsung, mientras android y paquetes Samsung
   usan SS. Ignorar GG no permite garantizar HeliBoard. Los generadores Samsung
   publicados ofrecen caminos distintos HSL/Monet; no definen una transformación
   universal de una matriz ColorBlendr arbitraria con overrides y todos sus estilos.
   **FOR_G no se escribe ni se copia desde MAIN.** No se inventa una transformación.

La causa demostrada en ColorBlendr es el criterio de éxito insuficiente. La causa
exacta de la reconciliación en el firmware objetivo sigue sin identificar. El
informe anterior afirmaba demasiado al tratar state 0→1 como regeneración probada;
esta revisión reemplaza esa afirmación.

## Auditoría del flujo

Apply hace commitStaged, actualiza monetLastUpdated y llama OverlayManager. Esa
marca no es un listener interno que restaure Samsung. OverlayManager serializa
apply/remove con un Mutex. Samsung usa PreviewController.buildPreviewColors;
se conservan generador, seed, estilo, sliders, overrides y selección light/dark.
La ruta Samsung evita el JSON seguro y no cambia el algoritmo del generador.

PreviewController tiene un scope propio independiente de Activity y un mutex de
commit. Antes ignoraba false devuelto por OverlayManager; ahora un fallo Samsung
Shizuku no registra un apply comunitario exitoso. El error visible sigue siendo
el que ya publica OverlayManager. Root y otras marcas conservan su flujo.

BroadcastListener puede programar otro apply forzado por wallpaper y esperar el
commitMutex. El guard por fingerprint evita duplicados; el baseline ahora también
se acepta en seed manual si los IDs son estables, aunque la extracción no coincida
con colores guardados (el seed manual no depende de ellos). Cambios reales de IDs
siguen atravesando el guard. Configuración reaplica solo cuando cambia night mode.
No se ha demostrado la ausencia de todos los posibles duplicados en el dispositivo.

No se encontró un job autónomo que restaure SamsungBackupPreferences segundos
después del éxito. Rollback está dentro del catch de apply; restore se llama desde
removeIfOwned. Backup pending no tiene un watcher. El mutex de la transacción era
por instancia aunque se construye una instancia por apply: ahora es compartido.

## Flujo implementado y límites

1. Capturar settings y recursos QS, volumen y Material A1/A2 desde contexto de la
   app; guardar backup durable pendiente antes de escribir.
2. Mantener el transporte actual: MAIN, gray, state=0, 100ms, state=1 y reparación
   acotada de overlays presentes. **Este paso aún no es la vía definitiva del motor.**
3. Observar a intervalos programados 0,250,1000,2000,5000,10000ms; los comandos
   añaden latencia. Se registran tiempo monotónico real, tx PID-secuencia, seed,
   estilo, tres sliders, SHA-256 UTF-8 de MAIN esperado/real y FOR_G real, state,
   gray, cuatro SemWT, dynamic y colores resueltos. MAIN se lee también justo
   después de escribir. No hay tareas programadas después del retorno.
4. Exigir MAIN igual en todas las muestras; al final exigir state=1, gray correcto,
   los cuatro SemWT habilitados, QS=A1_300, volumen=A2_300 y esos dos tonos framework
   resueltos en ColorBlendr iguales al generador. La última comprobación detecta
   GG antiguo en contexto de terceros, pero no sustituye verificar todos los roles
   dinámicos o HeliBoard real. Sólo entonces confirmar backup y devolver éxito.
5. Ante fallo, restaurar settings anteriores, state=1 y overlays; verificar otros
   diez segundos incluyendo recursos Material históricos antes de borrar/reponer
   backup. Si falla recuperación, conservar pending y adjuntar errores al original.
   Cancelación del caller no interrumpe ese cleanup. Un Binder bloqueado no tiene
   timeout nuevo: las pausas son acotadas, no toda llamada remota arbitraria.
6. Reset observa también diez segundos. Backups legacy sin recursos históricos
   intentan recuperar settings/state, pero se conservan pendientes y se informa
   verificación incompleta. No se inventa evidencia de recuperación.

No garantiza persistencia tras reboot, todos los roles Material, HeliBoard ni
aplicación nueva a QS. Es deliberado reportar fallo con la evidencia aportada,
en vez de aceptar nuevamente un FRRO habilitado con datos antiguos.

## Tests y entrega

Se reprodujeron primero dos fallos con la implementación anterior: no esperar
la ventana y aceptar una paleta restaurada a los 2s. La suite existente cubre
65 colores/orden, estilos/tuning (Robolectric), ruta Samsung/Shizuku, fallback,
callbacks y cancelación. Nuevos tests cubren reconciliación tardía/transitoria,
QS antiguo, Material antiguo, regeneración de recursos retrasada, overlay GG
faltante, serialización entre instancias, rollback incompleto y backup legacy.

Comando núcleo local:

```sh
./gradlew -p bridge-tests test --no-daemon --max-workers=1 \
  -Dorg.gradle.jvmargs=-Xmx384m -Pkotlin.compiler.execution.strategy=in-process
```

Workflow existente: .github/workflows/build-samsung-shizuku-bridge.yml. Ejecuta
núcleo JVM, :app:testDebugUnitTest y assembleDebug. Artifact APK:
ColorBlendr-Samsung-OneUI-Shizuku-Debug. Los resultados finales, run, commit y
SHA-256 se proporcionan en la entrega después de descargarlos, no se anticipan.

Para actualización sobre la APK actual, se comprobará primero el certificado
contra /storage/emulated/0/Download/ColorBlendr-Samsung-OneUI-Shizuku-Debug.apk.
Instalación prevista con firmas iguales: abrir la APK y elegir Actualizar, o
`adb install -r ColorBlendr-Samsung-OneUI-Shizuku-Debug.apk`. No requiere borrar
datos. Esta revisión no instala automáticamente nada en el teléfono.
