# JavaME API stubs

Compile-time-only stand-ins for the JavaME platform classes the engine is
written against. They let `tools/build-jar.sh` produce `Quantum.jar` on a plain
desktop JDK, without Sun/Oracle's proprietary Wireless Toolkit or Java ME SDK.

**These classes are never shipped.** They are used as `-bootclasspath` while
compiling, so none of their bytecode ends up in the JAR. Method bodies are
empty stubs and return zero/null — they exist so the compiler can resolve
signatures, nothing else.

## Coverage

| API | Package | Source |
|---|---|---|
| CLDC 1.1 | `java.lang`, `java.util`, `java.io` | the subset the 117 engine sources actually reference |
| MIDP 2.0 | `javax.microedition.lcdui`, `.lcdui.game`, `.midlet`, `.rms` | MIDP 2.0 spec |
| JSR-75 | `javax.microedition.io`, `.io.file` | PDA Profile / FileConnection |
| JSR-135 | `javax.microedition.media`, `.media.control`, `.media.protocol` | MMAPI 1.1 |
| JSR-184 | `javax.microedition.m3g` | Mobile 3D Graphics 1.1 |

Which of these the engine touches is visible with:

```sh
grep -rhoE "^import +[a-z][a-zA-Z0-9_.]*;" src Utils/src UtilsFPS/src UtilsText/src \
    | grep -E "javax\.microedition" | sort -u
```

## Known divergence from the published API

`src/code/Rendering/Meshes/M3GParser.java:177` calls

```java
TriangleStripArray iIb = new TriangleStripArray( 0, stripLen );   // stripLen is int[]
```

JSR-184 1.1 documents only `TriangleStripArray(int[], int[])` and
`TriangleStripArray(VertexArray, int[])`, so that call does not match the
published API. A matching `TriangleStripArray(int, int[])` constructor is
declared here so the repository compiles **unmodified** — no engine source was
changed to get the build through. If the real `jsr184_1.1.jar` rejects that
line, it is a latent bug in `M3GParser`, and the fix belongs there (the call
almost certainly wants a `VertexArray` of indices as the first argument).

## What the stubs cannot give you

- **No preverification.** `preverify` is a native tool from the Wireless
  Toolkit. A CLDC device or the WTK emulator will refuse classes that have not
  been through it. Run it over `build/compiled` before shipping.
- **No API checking.** A stub is not a spec. A method that exists here but not
  in MIDP 2.0 would compile and then fail at runtime on a device.
- **No behaviour.** Anything run against these stubs does nothing, which is
  exactly why `tools/difftest` links the harness against them only for the
  engine's own integer code, never for the platform calls.
