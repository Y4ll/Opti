# Android Optimizer

Optimizador para Android en dos versiones y un módulo Magisk.

| Archivo | Para quién |
|---|---|
| `AndroidOptimizer-Lite.apk` | Cualquier teléfono, sin root |
| `AndroidOptimizer-Root.apk` | Teléfonos con root |
| `AndroidOptimizer-Magisk.zip` | Módulo de Magisk (se instala desde Magisk, no es un APK) |

## Descargar

Los archivos se compilan solos con cada cambio y quedan en
[Releases](../../releases/latest). Enlaces directos al último build:

- `https://github.com/Y4ll/Opti/releases/latest/download/AndroidOptimizer-Lite.apk`
- `https://github.com/Y4ll/Opti/releases/latest/download/AndroidOptimizer-Root.apk`

Para instalar un APK hay que permitir "instalar apps de fuentes desconocidas" en el teléfono.

## Carpetas

- `lite/`: app sin root. Libera RAM, borra archivos basura, modo juego y atajos a Ajustes.
- `root/`: app con root. Limpieza profunda, ajustes de memoria y almacenamiento, red bbr, modo juego con CPU y GPU.
- `magisk/`: módulo de Magisk con botón de acción.

## Advertencias

Código sin probar en teléfonos reales. Las versiones con root cambian ajustes del sistema: úsalas bajo tu riesgo.
