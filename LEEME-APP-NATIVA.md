# La Llave CRM — app Android para GPS en segundo plano

## Cómo fluye para el chofer (un solo QR)
1. Escanea el QR del vehículo. Se abre `gps.html` en Chrome.
2. La página intenta abrir la app (enlace `intent://`).
   - **App instalada** → se abre la app, entra directo a ese vehículo e inicia el viaje.
   - **No instalada** → Chrome lo manda solo a `instalar.html` (4 pasos con botón «Descargar app»).
3. Instala el APK, vuelve a la pantalla y toca «Abrir app e iniciar viaje».
4. **Primera vez:** pantalla «Un último paso» (permiso de ubicación, notificación, batería) y arranca el viaje.
5. Siguientes veces: QR → app → el viaje inicia solo. Puede bloquear el teléfono.
En iPhone no hay APK: se queda el GPS web de siempre (pantalla abierta).

## Armarlo (una sola vez, en tu computadora con Node 18+ y Android Studio)
1. Edita `capacitor.config.json` → `server.url` = la URL real de tu CRM (https, con `/` al final).
2. En esta carpeta:

       npm install
       npm run setup

   (crea el proyecto Android y le pone permisos y el enlace del QR solo).
3. `npx cap open android` → Android Studio → **Build ▸ Build APK(s)** (el APK «debug» sirve).
4. Renombra el APK a **`la-llave.apk`** y súbelo a la **raíz de tu hosting**, junto a `instalar.html`.
5. Sube también todo el zip nuevo del CRM (gps.html, instalar.html, native-bridge.js, index.html, sw.js).

Si cambias de dominio: edita `server.url`, `npm run sync`, genera un APK nuevo.

## Notas
- El APK es una app que abre tu CRM web: cualquier cambio al CRM se ve sin reinstalar. Solo hay que
  rehacer el APK si cambias el dominio o el plugin de GPS.
- Android pide permitir «instalar apps desconocidas» la primera vez (por no estar en Play Store).
  `instalar.html` ya lo explica.
- Permiso de ubicación: «Mientras la app está en uso» alcanza porque hay notificación fija activa.
  Si ves huecos en la ruta, cámbialo a «Siempre» y pon la batería en «Sin restricciones».
- Opcional más adelante: Android App Links (archivo `assetlinks.json`) para que el QR abra la app sin
  ningún toque extra.

## Respaldo por SMS ("LA LLAVE")
Si al telefono del chofer llega un SMS que dice exactamente `LA LLAVE` (sin importar mayusculas/espacios),
la app responde por SMS con la ubicacion actual (enlace de Google Maps). Funciona con la pantalla bloqueada.
- Permisos SMS: la app los pide la primera vez que el chofer inicia el GPS (aceptar "Enviar y ver SMS").
- Android 13+ (APK instalado fuera de Play Store): si no responde, Ajustes > Apps > La Llave > menu (⋮) > "Permitir ajustes restringidos".
- Opcional: limitar quien puede pedir la ubicacion con `GpsUploader.setSmsConfig({numbers:['6861234567']})`.
- Maximo 1 respuesta cada 30 s. Cada respuesta es un SMS que cuesta del saldo del telefono del chofer.
