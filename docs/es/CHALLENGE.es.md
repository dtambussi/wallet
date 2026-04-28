**Idiomas:** [English — CHALLENGE.md](../CHALLENGE.md) · Español — CHALLENGE.es.md (este archivo)

# Challenge técnico: wallet multi-moneda cross-border

## 1. El escenario
Te sumás a una plataforma de dinero **cross-border**. Los usuarios mantienen saldos en múltiples monedas y se transfieren plata entre sí, a veces con conversión FX (Foreign Exchange).

**Contexto crítico:**
* La plata es real, los errores son caros.
* Los proveedores externos fallan más seguido de lo que nos gustaría.
* El objetivo es evaluar: **correctitud, resiliencia ante fallos y escalabilidad.**

---

## 2. Qué construir
Un servicio backend que exponga una API HTTP con las siguientes capacidades:

| Funcionalidad | Descripción / resultado |
| :--- | :--- |
| **Crear usuario** | Persiste el usuario y devuelve un `User ID`. |
| **Crear depósito** | Simula un depósito entrante (monto + moneda). |
| **Crear retiro** | Simula un retiro ejecutado vía API de terceros (proveedor mockeado). |
| **Obtener saldos** | Devuelve los saldos de un usuario en todas sus monedas. |
| **Historial de transacciones** | Listado de movimientos de un usuario (debe estar paginado). |
| **Cotizar intercambio** | Registra la cotización con un **TTL corto (30s)**. |
| **Realizar intercambio** | Ejecución interna del cambio usando la cotización adecuada. |
| **Transferencia entre usuarios** | Impacto en el balance de emisor y receptor. |

### Componentes adicionales
* **Proveedor FX mock:** un servicio que emita cotizaciones para las monedas soportadas.
* **Monedas soportadas:** `USD`, `ARS`, `CLP`, `BOB`, `BRL`.

---

## 3. Qué estamos evaluando
Buscamos una solución que demuestre entendimiento de las complejidades de administrar dinero real. Identificar los problemas es parte del ejercicio.

**Ejes principales:**
1. **Consistencia de estados:** integridad de los balances (evitar doble gasto, condiciones de carrera).
2. **Tolerancia al fallo:** estrategia ante errores de proveedores externos.
3. **Auditabilidad:** rastro claro de cada movimiento.
4. **Garantías senior:** no solo el *happy path*, sino cómo el sistema resiste intentos de rotura.

---

## 4. Entregables obligatorios

### 📄 DESIGN.md (máx. 3 páginas)
* Garantías que ofrece el sistema y cómo se logran.
* Modelo de datos y razonamiento detrás.
* Modos de fallo considerados vs. decisiones de no atacar ciertos problemas.
* **Evolución:** cómo cambia el diseño al pasar de **10K → 1M → 10M** de usuarios.

### 📄 DECISIONS.md (log de decisiones)
* Registro de cada decisión no obvia.
* Alternativas descartadas y el «por qué» en una oración.

### 💻 Código fuente
* Lenguaje/framework/DB a elección.
* **README:** instrucciones de setup y ejecución de tests.
* **Requisito:** debe correr local con un solo comando (ej. `docker compose up`).

### 🧪 Tests de garantía
* No solo tests unitarios básicos. Deben ejercitar las garantías descritas en [DESIGN.md](../DESIGN.md) bajo condiciones de estrés o fallo.

### 📑 RUNBOOK.md
* Métricas clave a exportar.
* **Top 3 alertas:** thresholds y razonamiento de por qué paginarían a un ingeniero.
* **Playbook:** guía de respuesta para la alerta más ruidosa.

---

## 5. Tiempo y entrega
* **Plazo:** 7 días corridos.
* **Filosofía:** *«Preferimos menos scope con razonamiento afilado por sobre más scope con vaguedades»*. Si decides recortar una funcionalidad para asegurar la calidad de las otras, documéntalo en [DESIGN.md](../DESIGN.md).
