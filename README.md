# MazeRunner

Mod de Fabric (Minecraft 1.21.1) que genera un laberinto **circular** estilo Maze Runner:

```
/maze <x> <y> <z>
/maze open
/maze close
```

- `/maze <x> <y> <z>`: construye el laberinto centrado en esas coordenadas. Si ya había
  uno construido en ese mundo, se borra primero.
- `/maze open`: abre las 4 puertas grandes (animación cubo a cubo, columna a columna).
- `/maze close`: cierra las 4 puertas grandes (misma animación, en sentido inverso).

Requiere nivel de permisos de operador (op nivel 2).

## Qué genera

- **Plaza central** cuadrada de 200x200 bloques (hub), con lago, árboles, vegetación y un
  par de estructuras en ruinas.
- **7 sectores**, cada uno con su propia sala cuadrada abierta marcada con su número
  grabado en la pared, repartidos en ángulo alrededor del centro con un radio garantizado
  desde la plaza.
- **Laberinto perfecto** (backtracking aleatorio) tallado sobre una rejilla cuadrada y
  recortado en círculo; pasillos de 8 bloques de ancho, paredes de 5 de grosor, 80 bloques
  de alto, con techo de barreras.
- **4 puertas grandes**, siempre en los puntos cardinales exactos, cerradas al terminar de
  generar el laberinto (usa `/maze open` para abrirlas). Cada una tiene un pequeño porche
  exterior siempre transitable.
- **Desierto de salida** justo más allá del porche de la puerta sur.
- Símbolos decorativos grabados en algunas paredes del interior del laberinto.

## El laberinto cambia solo, cada 120 segundos

Al estilo Maze Runner: una vez construido, cada 120 segundos varios tramos interiores del
laberinto se abren o se cierran solos. Si un jugador está justo donde va a levantarse una
pared nueva, se le aparta automáticamente. Las salas de sector y sus alrededores nunca se
tocan.

## Arañas gigantes

Cada ciclo de 120 segundos (en fase con el cambio de sector) aparecen unas pocas arañas
gigantes (escala, vida, daño y velocidad aumentados) repartidas por el interior del
laberinto; se retiran automáticamente pasados otros 120 segundos.

## Rendimiento

La construcción inicial son varios millones de bloques. Para no congelar el servidor se
reparte en varios ticks (`MazeBuildQueue`, 12000 bloques/tick). La animación de las puertas
usa una cola aparte (`AnimationQueue`) que coloca una columna cada pocos ticks para dar el
efecto visual de apertura/cierre gradual.

## Estructura del proyecto

```
maze/
├── MazeConfig.java        # Todas las medidas del laberinto en un solo sitio
├── GateGeometry.java       # Geometría de las 4 puertas grandes
├── CircularMazeGrid.java   # Rejilla lógica (backtracking aleatorio + hub + radios + sectores)
├── MazeBuilder.java        # Traduce la rejilla a bloques reales
├── MazeBuildQueue.java     # Coloca bloques repartidos en varios ticks, por lotes
├── AnimationQueue.java     # Animación columna a columna de las puertas
├── PixelFonts.java         # Bitmaps para los números de sector y símbolos decorativos
├── MazeInstance.java       # Laberinto ya construido: se remueve solo, puertas, arañas
└── MazeLiveManager.java    # Dispara los ciclos de 120s de cada mundo
```

Estructura de build (`build.gradle`, `gradle.properties`, `fabric.mod.json`, workflow de
GitHub Actions) calcada del proyecto de referencia, para evitar líos de versiones de
Yarn/Loom/Fabric API incompatibles entre sí.
