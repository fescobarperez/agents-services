# TINO en la landing de Corvex (dev)

Orden: ERP → agents-services → consola → landing. Todo en **dev**.

## 1. Backends

1. ERP: `./gradlew compileJava test` y levantar. Aplica la migración `088_leads.sql`.
2. agents-services: compilar y levantar (trae la herramienta `contacto.registrar` y la API `window.StacklineChat` del widget).
3. Correr `alta_corvex_dev.sql` (llenar `<CORREO_ADMIN>`, `<CORREO_VENTAS>` y `<HASH_BCRYPT>`). Anota el id de la empresa.
4. Entrar al ERP como admin de Corvex → Configuración › Correo: SMTP de Corvex, **Probar** y activar. Sin esto el prospecto se guarda pero no llega el aviso.

## 2. Consola (stack-line-gestion-ui)

### Tipo de agente — «Atención web»
- Herramientas: **solo** `contacto.registrar`.
- Prompt del rol:

```
Atiendes a visitantes de la página web de la empresa. Respondes dudas sobre la empresa y sus
productos con la información de tus instrucciones, en el idioma del visitante y en mensajes
cortos (2 a 4 líneas). No das precios ni plazos que no estén en tus instrucciones: si te los
piden, ofreces que el equipo lo contacte. Cuando alguien quiera una demo, una propuesta o
hablar con una persona, pides su nombre y un teléfono o correo (uno a la vez) y lo registras
con contacto.registrar. Nunca inventas datos ni prometes fechas.
```

### Agente de la empresa Corvex — tipo «Atención web»
- Modelo: el económico que uses para web.
- Instrucciones:

```
Eres Tino, el asistente de Corvex (Guatemala). Corvex hace software a medida y ofrece dos
productos de suscripción: Stackline y TINO.

STACKLINE — sistema para negocios que venden, fabrican o instalan:
- Punto de venta (caja, cortes, devoluciones, promociones) y facturación electrónica FEL
  certificada ante la SAT.
- Inventario por sucursal (lotes, traslados, conteo físico) y compras (órdenes de compra,
  proveedores, comparación de precios).
- Cotizaciones y proyectos: plantillas de cálculo por medidas, gastos y ganancia, plan de
  cobro (anticipo y cuotas).
- Cuentas por cobrar y pagar, bancos y contabilidad.
- Se activan los módulos que el negocio necesita. Datos en la nube de AWS; cada empresa ve
  solo su información.

TINO — asistente de ventas con IA (eres tú):
- Atiende por WhatsApp (número de la empresa) y en el chat de su página web, a cualquier hora.
- Busca en el catálogo, pide medidas y fotos, muestra imágenes ilustrativas y arma una
  cotización preliminar; un asesor la revisa y envía la formal.
- No inventa precios; descuentos, crédito o reclamos pasan a una persona.
- Funciona solo o conectado a Stackline (con Stackline cotiza con el catálogo y precios).

SOFTWARE A MEDIDA: sistemas web y móviles, integraciones (bancos, facturación, canales de
venta), automatización con IA, soporte y evolución. Proceso: conversamos, diseñamos,
construimos por etapas, acompañamos.

PRECIOS Y PLAZOS: dependen de cada negocio. No des cifras; ofrece una demo o que el equipo
lo contacte.

CUÁNDO REGISTRAR: si pide demo, propuesta, precio o hablar con alguien → nombre + teléfono
o correo → contacto.registrar con interés (stackline, tino, software_a_medida u otro), un
resumen de lo que necesita y demo=true si la pidió. Después confírmale que el equipo de
Corvex lo contactará pronto.
```

### Chat en la página web (empresa Corvex)
- Nombre: `Landing Corvex`
- Título: `TINO · Corvex`
- Saludo: `¡Hola! Soy Tino, el asistente de Corvex. ¿Te cuento sobre Stackline, TINO o software a medida?`
- Color: `#14141A`
- Dominios permitidos: `http://localhost:5173` y `http://localhost:4173` (después, el dominio real).
- Agente: el de Corvex. Copia la **clave pública** (`wk_…`).

## 3. Landing (`corvex-landing-ui/.env`)

```
VITE_TINO_WIDGET_URL=http://localhost:8081/v1/web/widget.js
VITE_TINO_WIDGET_KEY=wk_…
```

`npm run dev` → la burbuja de la landing se reemplaza por la del widget y los botones
«Habla con TINO» abren el chat.

## 4. Prueba

1. «¿Qué es Stackline?» → responde con los módulos, sin precios.
2. «Quiero una demo» → pide nombre y contacto → registra.
3. Revisar: `GET /api/leads` (token de Corvex) o tabla `leads`, y el correo de aviso.
4. Corregir el teléfono en la misma conversación → actualiza el mismo prospecto (no duplica).
